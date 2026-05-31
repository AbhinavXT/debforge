package com.abhinavxt.debforge.download

import com.abhinavxt.debforge.data.local.ChunkDao
import com.abhinavxt.debforge.data.local.ChunkEntity
import com.abhinavxt.debforge.data.local.DownloadDao
import com.abhinavxt.debforge.data.local.DownloadEntity
import com.abhinavxt.debforge.data.repository.DownloadsRepository
import com.abhinavxt.debforge.di.DownloadHttpClient
import com.abhinavxt.debforge.domain.DataResult
import com.abhinavxt.debforge.domain.DownloadState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlin.math.min

/** Terminal result of a single file download (cancellation is thrown, not returned). */
sealed interface DownloadOutcome {
    data object Success : DownloadOutcome
    data class Failure(val message: String) : DownloadOutcome
}

/**
 * Downloads ONE file with parallel HTTP Range chunks, resume, integrity checks,
 * and a single-stream fallback. Cooperatively cancellable: chunk read loops
 * check the coroutine's active state every buffer, and progress is persisted in
 * a NonCancellable finally so a pause/cancel leaves a resumable .part file.
 *
 * Returns [DownloadOutcome.Success]/[DownloadOutcome.Failure] for terminal
 * states; rethrows [CancellationException] so the caller (service) can tell a
 * pause/cancel apart from a network failure.
 */
@Singleton
class ChunkedDownloader @Inject constructor(
    @DownloadHttpClient private val client: OkHttpClient,
    private val downloadDao: DownloadDao,
    private val chunkDao: ChunkDao,
    private val downloadsRepository: DownloadsRepository,
    private val progressTracker: DownloadProgressTracker,
    private val networkMonitor: NetworkMonitor
) {
    private companion object {
        const val BUFFER = 256 * 1024
        const val PERSIST_INTERVAL = 4L * 1024 * 1024 // persist chunk progress every 4 MiB
        const val PROGRESS_PUSH_MS = 200L              // UI progress cadence
        val DEAD_LINK_CODES = setOf(401, 403, 404, 410)

        /**
         * Total tries (including the first). After 3 attempts a real failure
         * is surfaced to the UI, where the user can hit Retry manually. Total
         * wall-clock added by the backoffs below is at most 7s, so a short
         * Wi-Fi blip is invisible to the user while a sustained outage
         * surfaces quickly.
         */
        const val MAX_ATTEMPTS = 3
        val BACKOFF_MS = longArrayOf(2_000L, 5_000L) // between attempts 1→2 and 2→3
    }

    /**
     * Public entry. Loop semantics:
     *  - If we don't have validated internet, we suspend on the network monitor
     *    instead of failing. The DB row stays DOWNLOADING but the live progress
     *    surfaces a "waitingForNetwork" flag so the UI can show "Waiting for
     *    network…" rather than a stuck progress bar.
     *  - The retry budget ([MAX_ATTEMPTS]) is consumed only by failures that
     *    happen WHILE online. Network recovery resets the counter — every time
     *    you come back online you get a fresh budget. This way a phone bouncing
     *    in and out of dead zones (commute, elevator, basement) never gives up.
     *  - Between attempts we evict the OkHttp connection pool so the retry
     *    doesn't pick up zombie sockets the OS killed during the outage.
     *
     * CancellationException always propagates — pause/cancel by the user must
     * not be confused with a network blip. `delay()` and `first { it }` are
     * cancellable, so pause-during-wait exits promptly.
     */
    suspend fun download(entity: DownloadEntity): DownloadOutcome {
        var consecutiveOnlineFailures = 0
        while (true) {
            // Block until we have validated internet. Pushes the waiting flag
            // so the UI knows to switch its subtitle.
            if (!networkMonitor.online.value) {
                progressTracker.update(
                    id = entity.rdId,
                    bytesDownloaded = entity.bytesDownloaded,
                    totalBytes = entity.filesize,
                    state = DownloadState.DOWNLOADING,
                    waitingForNetwork = true
                )
                networkMonitor.online.first { it }
                // Brief settle so DNS/route stabilizes before we hammer.
                delay(500)
                // Coming back online deserves a fresh budget.
                consecutiveOnlineFailures = 0
            }

            val outcome = try {
                attemptDownload(entity)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                DownloadOutcome.Failure(t.message ?: "Unexpected error")
            }

            when (outcome) {
                DownloadOutcome.Success -> return outcome
                is DownloadOutcome.Failure -> {
                    client.connectionPool.evictAll()
                    // If the failure happened because the network dropped
                    // mid-attempt, don't count it — loop back and wait.
                    if (!networkMonitor.online.value) continue
                    consecutiveOnlineFailures++
                    if (consecutiveOnlineFailures >= MAX_ATTEMPTS) return outcome
                    delay(BACKOFF_MS[consecutiveOnlineFailures - 1])
                }
            }
        }
    }

    private suspend fun attemptDownload(entity: DownloadEntity): DownloadOutcome {
        val partPath = entity.partFilePath
        val finalPath = entity.finalFilePath
        if (partPath == null || finalPath == null) {
            return DownloadOutcome.Failure("Missing destination path")
        }

        // 1) Resolve a working URL and learn whether ranges are supported.
        val probe = try {
            resolveAndProbe(entity)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            return DownloadOutcome.Failure(e.message ?: "Could not start download")
        }
        val totalSize = probe.totalSize
        if (totalSize <= 0) return DownloadOutcome.Failure("Unknown file size")

        // 2) Prepare the .part file (idempotent on resume).
        try {
            withContext(Dispatchers.IO) { DownloadFiles.preallocate(partPath, totalSize) }
        } catch (e: IOException) {
            return DownloadOutcome.Failure("Storage error: ${e.message}")
        }

        // 3) Build or restore the chunk plan.
        val ranges = if (probe.supportsRanges) {
            ChunkPlanner.plan(totalSize, entity.chunksAllowed)
        } else {
            listOf(ChunkRange(0, 0, totalSize - 1)) // single stream
        }
        val chunkRows = ensureChunkRows(entity.rdId, ranges, totalSize)

        // A non-range server can't resume — force a clean restart of the single
        // stream so on-disk bytes and the progress counter agree.
        if (!probe.supportsRanges) {
            withContext(Dispatchers.IO) { chunkDao.updateProgress(entity.rdId, 0, 0L) }
        }

        // Seed the aggregate counter with bytes already on disk (range resume only).
        val alreadyDownloaded = if (probe.supportsRanges) chunkRows.sumOf { it.bytesWritten } else 0L
        val totalDownloaded = AtomicLong(alreadyDownloaded)
        val lastPushMs = AtomicLong(0)

        fun reportDelta(delta: Int) {
            val now = totalDownloaded.addAndGet(delta.toLong())
            val prev = lastPushMs.get()
            val t = System.currentTimeMillis()
            if (t - prev >= PROGRESS_PUSH_MS && lastPushMs.compareAndSet(prev, t)) {
                progressTracker.update(entity.rdId, now, totalSize, DownloadState.DOWNLOADING)
            }
        }

        // 4) Download all chunks. coroutineScope (not supervisor): the first
        //    chunk failure cancels siblings, their finally persists progress,
        //    and the exception surfaces here as a Failure to be retried later.
        try {
            coroutineScope {
                ranges.forEach { range ->
                    launch(Dispatchers.IO) {
                        downloadChunk(
                            entity = entity,
                            range = range,
                            url = probe.url,
                            supportsRange = probe.supportsRanges,
                            partPath = partPath,
                            onDelta = ::reportDelta
                        )
                    }
                }
            }
        } catch (ce: CancellationException) {
            throw ce // pause/cancel from the service — leave .part for resume
        } catch (e: Exception) {
            return DownloadOutcome.Failure(e.message ?: "Download interrupted")
        }

        // 5) Verify size and atomically promote .part -> final.
        val promoted = try {
            withContext(Dispatchers.IO) {
                DownloadFiles.verifyAndPromote(partPath, finalPath, totalSize)
            }
        } catch (e: IOException) {
            return DownloadOutcome.Failure("Finalize failed: ${e.message}")
        }
        if (!promoted) return DownloadOutcome.Failure("Size verification failed")

        // 6) Mark complete (durable) and update the live view, then drop it.
        withContext(NonCancellable) {
            downloadDao.updateBytes(entity.rdId, totalSize)
            downloadDao.updateState(entity.rdId, DownloadState.COMPLETED)
        }
        progressTracker.update(entity.rdId, totalSize, totalSize, DownloadState.COMPLETED)
        progressTracker.clear(entity.rdId)
        return DownloadOutcome.Success
    }

    // --- internals ---------------------------------------------------------

    private data class Probe(val url: String, val supportsRanges: Boolean, val totalSize: Long)

    /**
     * Probes the current download URL with a 1-byte ranged request. On a dead
     * link (4xx/410) it re-unrestricts the original hoster link once and retries.
     */
    private suspend fun resolveAndProbe(entity: DownloadEntity): Probe = withContext(Dispatchers.IO) {
        var url = entity.downloadUrl
        repeat(2) { attempt ->
            coroutineContext.ensureActive()
            val req = Request.Builder().url(url).header("Range", "bytes=0-0").get().build()
            val resp = client.newCall(req).execute()
            try {
                when {
                    resp.code == 206 -> {
                        val total = parseContentRangeTotal(resp.header("Content-Range"))
                            ?: entity.filesize
                        return@withContext Probe(url, supportsRanges = true, totalSize = total)
                    }
                    resp.code == 200 -> {
                        val total = resp.body?.contentLength()?.takeIf { it > 0 } ?: entity.filesize
                        return@withContext Probe(url, supportsRanges = false, totalSize = total)
                    }
                    resp.code in DEAD_LINK_CODES && attempt == 0 -> {
                        // fall through to refresh below
                    }
                    else -> throw IOException("Server returned HTTP ${resp.code}")
                }
            } finally {
                resp.close()
            }
            // Dead link: mint a fresh direct URL from the original hoster link.
            when (val refreshed = downloadsRepository.refreshDownloadUrl(entity.originalLink)) {
                is DataResult.Success -> {
                    url = refreshed.data.url
                    downloadDao.update(
                        entity.copy(
                            downloadUrl = refreshed.data.url,
                            chunksAllowed = refreshed.data.chunks.coerceAtLeast(1),
                            filesize = refreshed.data.filesize.takeIf { it > 0 } ?: entity.filesize
                        )
                    )
                }
                is DataResult.Error -> throw IOException(refreshed.message)
            }
        }
        throw IOException("Could not obtain a working download link")
    }

    /**
     * Loads persisted chunk rows; (re)creates them if absent or if their total
     * coverage no longer matches [totalSize] (e.g. size changed after refresh).
     */
    private suspend fun ensureChunkRows(
        downloadId: String,
        ranges: List<ChunkRange>,
        totalSize: Long
    ): List<ChunkEntity> = withContext(Dispatchers.IO) {
        val existing = chunkDao.getForDownload(downloadId)
        val coverageOk = existing.isNotEmpty() &&
            existing.sumOf { it.endByte - it.startByte + 1 } == totalSize
        if (coverageOk) return@withContext existing

        if (existing.isNotEmpty()) chunkDao.deleteForDownload(downloadId)
        val rows = ranges.map {
            ChunkEntity(
                downloadId = downloadId,
                index = it.index,
                startByte = it.startByte,
                endByte = it.endByte,
                bytesWritten = 0L,
                complete = false
            )
        }
        chunkDao.insertAll(rows)
        rows
    }

    /**
     * Streams one range to its absolute offset. Writes strictly within
     * [ChunkRange.startByte]..[ChunkRange.endByte] and never past it, even if the
     * stream yields more. Persists progress on a 4 MiB throttle and, critically,
     * in a NonCancellable finally so pause/cancel/failure all leave a resumable
     * offset on disk.
     */
    private suspend fun downloadChunk(
        entity: DownloadEntity,
        range: ChunkRange,
        url: String,
        supportsRange: Boolean,
        partPath: String,
        onDelta: (Int) -> Unit
    ) {
        // Resume: pick up where this chunk left off (range mode only — a
        // non-range server forces a restart from 0).
        val resumeFrom = if (supportsRange) {
            chunkDao.getForDownload(entity.rdId)
                .firstOrNull { it.index == range.index }
                ?.takeIf { !it.complete }
                ?.bytesWritten ?: 0L
        } else 0L

        if (supportsRange && resumeFrom >= range.size) {
            chunkDao.markComplete(entity.rdId, range.index, range.size)
            return
        }

        val writeOffset = range.startByte + resumeFrom
        val reqBuilder = Request.Builder().url(url).get()
        if (supportsRange) {
            reqBuilder.header("Range", "bytes=$writeOffset-${range.endByte}")
        }
        val resp = client.newCall(reqBuilder.build()).execute()

        var written = resumeFrom
        try {
            // In range mode the server MUST honor the range (206). A 200 here
            // would mean it's resending from byte 0 — writing that at our offset
            // would corrupt the file, so we reject it.
            if (supportsRange && resp.code != 206) {
                throw IOException("Range not honored (HTTP ${resp.code})")
            }
            if (!supportsRange && resp.code != 200) {
                throw IOException("Unexpected HTTP ${resp.code}")
            }
            val body = resp.body ?: throw IOException("Empty response body")
            // BufferedSource lets us read directly into a direct ByteBuffer,
            // skipping the byte[] intermediate that body.byteStream() forces.
            val source = body.source()
            val maxForChunk = range.size

            // RandomAccessFile gives us a seekable FileChannel; writing through
            // the channel with a direct ByteBuffer keeps the bytes off the JVM
            // heap on the network → disk path. Per-chunk fsync removed — the
            // single fsync in verifyAndPromote (before rename) is the only one
            // that matters for the rename-atomic guarantee. Worst case on crash
            // is re-downloading a few MB of in-flight bytes from the page cache;
            // chunk OFFSETS are still persisted to Room every 4 MiB.
            RandomAccessFile(partPath, "rw").use { raf ->
                raf.seek(writeOffset)
                val channel = raf.channel
                val buffer = ByteBuffer.allocateDirect(BUFFER)
                var sincePersist = 0L
                while (written < maxForChunk) {
                    coroutineContext.ensureActive() // cooperative cancel point
                    buffer.clear()
                    val remaining = maxForChunk - written
                    val cap = min(buffer.capacity().toLong(), remaining).toInt()
                    buffer.limit(cap)
                    val n = source.read(buffer)
                    if (n == -1) break
                    buffer.flip()
                    while (buffer.hasRemaining()) channel.write(buffer)
                    written += n
                    sincePersist += n
                    onDelta(n)
                    if (sincePersist >= PERSIST_INTERVAL) {
                        chunkDao.updateProgress(entity.rdId, range.index, written)
                        sincePersist = 0L
                    }
                }
            }

            if (written >= maxForChunk) {
                chunkDao.markComplete(entity.rdId, range.index, written)
            } else {
                // Short read without hitting our boundary → the stream ended
                // early; treat as failure so the whole download retries/resumes.
                throw IOException("Truncated chunk ${range.index}: $written/${maxForChunk}")
            }
        } finally {
            // Persist the latest offset even under cancellation/failure.
            withContext(NonCancellable) {
                chunkDao.updateProgress(entity.rdId, range.index, written)
            }
            resp.close()
        }
    }

    /** Parses the total size from a "Content-Range: bytes 0-0/123456" header. */
    private fun parseContentRangeTotal(header: String?): Long? {
        val total = header?.substringAfter('/', "")?.trim()
        return total?.toLongOrNull()?.takeIf { it > 0 }
    }
}
