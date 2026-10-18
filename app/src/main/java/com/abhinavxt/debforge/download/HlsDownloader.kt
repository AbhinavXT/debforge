package com.abhinavxt.debforge.download

import com.abhinavxt.debforge.data.local.ChunkDao
import com.abhinavxt.debforge.data.local.ChunkEntity
import com.abhinavxt.debforge.data.local.DownloadDao
import com.abhinavxt.debforge.data.local.DownloadEntity
import com.abhinavxt.debforge.data.usage.UsageRecorder
import com.abhinavxt.debforge.di.DownloadHttpClient
import com.abhinavxt.debforge.domain.DownloadState
import com.abhinavxt.debforge.domain.HlsPlaylist
import com.abhinavxt.debforge.download.conditions.DownloadConditions
import com.abhinavxt.debforge.download.conditions.RateLimiter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/**
 * Downloads an HLS stream (the row's [DownloadEntity.downloadUrl] is its
 * media playlist) by joining its pieces, in order, into one file. Pieces are
 * fetched [PARALLEL] at a time; AES-128 ones are decrypted on the way.
 *
 * Resume: each piece written is a complete chunk row (its byte range in the
 * file), so a pause or failure continues at the next piece; anything written
 * after the last row is cut off first.
 *
 * shortcut: pieces are joined as they are (.ts or fragmented .mp4), not
 * remuxed; remux with Media3's muxer if players struggle to seek in them.
 */
@Singleton
class HlsDownloader @Inject constructor(
    @DownloadHttpClient private val client: OkHttpClient,
    private val downloadDao: DownloadDao,
    private val chunkDao: ChunkDao,
    private val progressTracker: DownloadProgressTracker,
    private val conditions: DownloadConditions,
    private val rateLimiter: RateLimiter,
    private val usage: UsageRecorder
) {
    private companion object {
        const val PARALLEL = 4
    }

    suspend fun download(entity: DownloadEntity, partPath: String, finalPath: String): DownloadOutcome = withContext(Dispatchers.IO) {
        val playlist = try {
            val call = client.newCall(entity.request(entity.downloadUrl).build())
            cancellableCall(call) {
                call.execute().use { resp ->
                    if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                    // Pieces are relative to where the playlist ended up after redirects.
                    HlsPlaylist.parse(resp.body?.string().orEmpty(), resp.request.url.toString())
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: IOException) {
            // Signed playlist links expire, and there's no page to get a new one from.
            return@withContext DownloadOutcome.Failure("Couldn't load the stream (${e.message}). Find the video in the Browser tab again.")
        }
        if (playlist !is HlsPlaylist.Media) return@withContext DownloadOutcome.Failure("Not a stream playlist")
        if (playlist.drm) return@withContext DownloadOutcome.Failure("This stream is DRM-protected and can't be downloaded")
        if (playlist.live) return@withContext DownloadOutcome.Failure("Live streams can't be downloaded")
        val pieces = listOfNotNull(playlist.init) + playlist.segments
        if (pieces.isEmpty()) return@withContext DownloadOutcome.Failure("The stream is empty")

        var done = chunkDao.getForDownload(entity.id)
        // The playlist changed since the last attempt: start over.
        if (done.size > pieces.size || done.withIndex().any { (i, c) -> c.index != i }) {
            chunkDao.deleteForDownload(entity.id)
            done = emptyList()
        }
        var written = done.sumOf { it.bytesWritten }
        val keys = ConcurrentHashMap<String, ByteArray>()

        try {
            DownloadFiles.preallocate(partPath, written)
            DownloadFiles.openAt(partPath, written).use { handle ->
                pieces.withIndex().drop(done.size).chunked(PARALLEL).forEach { batch ->
                    coroutineContext.ensureActive()
                    conditions.currentBlock()?.let { throw IOException(it.message) }
                    val bodies = coroutineScope {
                        batch.map { (_, piece) -> async { fetchPiece(entity, piece, keys) } }.awaitAll()
                    }
                    val rows = batch.zip(bodies) { (index, _), bytes ->
                        val buffer = ByteBuffer.wrap(bytes)
                        while (buffer.hasRemaining()) handle.channel.write(buffer)
                        ChunkEntity(entity.id, index, written, written + bytes.size - 1, bytes.size.toLong(), complete = true)
                            .also { written += bytes.size }
                    }
                    chunkDao.insertAll(rows)
                    val batchBytes = bodies.sumOf { it.size }
                    usage.add(entity.provider, batchBytes)
                    // The total is only known at the end: estimate it from the pieces so far.
                    val estimate = written * pieces.size / (batch.last().index + 1)
                    progressTracker.update(entity.id, written, estimate, DownloadState.DOWNLOADING)
                    val throttleMs = rateLimiter.reserve(batchBytes)
                    if (throttleMs > 0) delay(throttleMs)
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            coroutineContext.ensureActive()
            return@withContext DownloadOutcome.Failure(e.message ?: "Download interrupted")
        } finally {
            withContext(NonCancellable) { downloadDao.updateBytes(entity.id, written) }
        }

        if (!DownloadFiles.verifyAndPromote(partPath, finalPath, written)) {
            return@withContext DownloadOutcome.Failure("Size verification failed")
        }
        withContext(NonCancellable) {
            downloadDao.updateLink(entity.id, entity.downloadUrl, 1, written)
            downloadDao.updateBytes(entity.id, written)
            downloadDao.updateState(entity.id, DownloadState.COMPLETED)
        }
        progressTracker.update(entity.id, written, written, DownloadState.COMPLETED)
        progressTracker.clear(entity.id)
        DownloadOutcome.Success
    }

    private suspend fun fetchPiece(entity: DownloadEntity, piece: HlsPlaylist.Segment, keys: MutableMap<String, ByteArray>): ByteArray {
        val bytes = fetch(entity, piece.url, piece.offset, piece.length)
        val key = piece.key ?: return bytes
        // Fetched twice at worst, when two pieces in a batch start a new key.
        val secret = keys[key.url] ?: fetch(entity, key.url, null, null).also { keys[key.url] = it }
        if (secret.size != 16) throw IOException("Bad stream key")
        return Cipher.getInstance("AES/CBC/PKCS5Padding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(secret, "AES"), IvParameterSpec(key.iv))
            doFinal(bytes)
        }
    }

    /** The whole body of [url] (or [length] bytes at [offset]), with the row's Referer / User-Agent. */
    private suspend fun fetch(entity: DownloadEntity, url: String, offset: Long?, length: Long?): ByteArray {
        val req = entity.request(url)
        if (offset != null && length != null) req.header("Range", "bytes=$offset-${offset + length - 1}")
        val call = client.newCall(req.build())
        return cancellableCall(call) {
            call.execute().use { resp ->
                val expected = if (offset != null && length != null) 206 else 200
                if (resp.code != expected) throw IOException("Server returned HTTP ${resp.code}")
                resp.body?.bytes() ?: throw IOException("Empty response body")
            }
        }
    }
}
