package com.abhinavxt.debforge.download

import com.abhinavxt.debforge.data.local.ChunkDao
import com.abhinavxt.debforge.data.local.ChunkEntity
import com.abhinavxt.debforge.data.local.DownloadDao
import com.abhinavxt.debforge.data.local.DownloadEntity
import com.abhinavxt.debforge.data.usage.UsageRecorder
import com.abhinavxt.debforge.di.DownloadHttpClient
import com.abhinavxt.debforge.domain.DownloadState
import com.abhinavxt.debforge.data.youtube.YouTubeException
import com.abhinavxt.debforge.domain.HlsPlaylist
import com.abhinavxt.debforge.domain.YouTubeLinks
import com.abhinavxt.debforge.download.conditions.DownloadConditions
import com.abhinavxt.debforge.download.conditions.RateLimiter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import android.system.Os
import android.util.Log
import java.io.File
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
 * fetched [PARALLEL] at a time; AES-128 ones are decrypted on the way. A
 * stream whose audio is apart ([DownloadEntity.audioUrl]) has its audio
 * joined into "<part>.audio", then both go into one MP4 ([StreamMuxer]). A
 * fragmented-MP4 stream is rewritten the same way, on its own: joined as it
 * is, players see only its first piece's length.
 *
 * Resume: each piece written is a complete chunk row (its byte range in its
 * file; video pieces first, then audio), so a pause or failure continues at
 * the next piece; anything written after the last row is cut off first.
 *
 * shortcut: a .ts stream without separate audio is joined as it is, not
 * remuxed; send it through [StreamMuxer] too if players struggle to seek in it.
 */
@Singleton
class HlsDownloader @Inject constructor(
    @DownloadHttpClient private val client: OkHttpClient,
    private val downloadDao: DownloadDao,
    private val chunkDao: ChunkDao,
    private val progressTracker: DownloadProgressTracker,
    private val conditions: DownloadConditions,
    private val rateLimiter: RateLimiter,
    private val usage: UsageRecorder,
    private val youtubeSource: YouTubeSource
) {
    private companion object {
        const val PARALLEL = 4
    }

    /** One playlist's pieces and the file they're joined into. */
    private class Track(val media: HlsPlaylist.Media, val path: String) {
        val pieces = listOfNotNull(media.init) + media.segments
    }

    suspend fun download(entity: DownloadEntity, partPath: String, finalPath: String): DownloadOutcome = withContext(Dispatchers.IO) {
        val yt = YouTubeLinks.isSourceRef(entity.sourceRef)
        val tracks0 = try {
            if (yt) youtubeSource.tracks(entity, partPath).mapIndexed { i, m ->
                Track(m, if (i == 0) partPath else "$partPath.audio")
            } else listOfNotNull(
                Track(media(entity, entity.downloadUrl), partPath),
                entity.audioUrl?.let { Track(media(entity, it), "$partPath.audio") }
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: IOException) {
            return@withContext DownloadOutcome.Failure(e.message ?: "Couldn't load the stream")
        } catch (e: YouTubeException) {
            // Retrying can't fix these; a dropped connection can.
            val permanent = e is YouTubeException.SignInRequired || e is YouTubeException.Unavailable || e is YouTubeException.PlaylistOnly
            val msg = if (e is YouTubeException.Network) "Couldn't reach YouTube. Check your connection, then retry." else e.message ?: "Couldn't load the video"
            return@withContext DownloadOutcome.Failure(msg, permanent)
        }
        // Audio for a picked folder: downloaded into app-private storage, fixed there, then copied in one go
        // (header fix and the muxer are slow or impossible through the folder picker).
        val audioKind = if (yt && tracks0.size == 1) AudioFinish.kindFor(finalPath, 1, AudioFinish.isSingleProgressive(tracks0[0].media)) else null
        val privatePart = if (AudioFinish.needsPrivatePart(audioKind, finalPath)) DownloadFiles.pendingPathFor(entity.id) else null
        val tracks = if (privatePart == null) tracks0 else listOf(Track(tracks0[0].media, privatePart))
        val total = tracks.sumOf { it.pieces.size }
        val allPieces = tracks.flatMap { it.pieces }
        // YouTube pieces have exact lengths: the total is known, and a piece plan that changed (new length) is detected.
        val ytBytes = if (yt) allPieces.sumOf { it.length ?: 0L } else 0L

        var done = chunkDao.getForDownload(entity.id)
        // shortcut: a YouTube stream that grew in its last track (only full pieces done) isn't noticed, a schema column for the stream length would; add one if streams are seen changing mid-download.
        // The playlists changed since the last attempt: start over.
        if (done.size > total || done.withIndex().any { (i, c) -> c.index != i || (yt && (c.bytesWritten != allPieces[i].length || c.startByte != allPieces[i].offset)) }) {
            chunkDao.deleteForDownload(entity.id)
            done = emptyList()
        }
        if (privatePart != null) {
            val have = File(privatePart).takeIf { it.exists() }?.length()
            if (AudioFinish.mustRestart(done.sumOf { it.bytesWritten }, have)) {
                // An older version's half-download in the folder, or a cleared private file: restart privately.
                chunkDao.deleteForDownload(entity.id)
                done = emptyList()
                DownloadFiles.deletePart(partPath)
            }
            val need = ytBytes - done.sumOf { it.bytesWritten }
            val free = DownloadFiles.availableBytes(privatePart)
            if (need > 0 && free != null && free < need) {
                return@withContext DownloadOutcome.Failure("Not enough free space on the device to download this")
            }
        }
        var downloaded = done.sumOf { it.bytesWritten }
        val sizes = mutableListOf<Long>()
        val keys = ConcurrentHashMap<String, ByteArray>()

        try {
            var first = 0 // chunk index of this track's first piece
            for (track in tracks) {
                val mine = done.filter { it.index - first in track.pieces.indices }
                var written = mine.sumOf { it.bytesWritten }
                DownloadFiles.preallocate(track.path, written)
                DownloadFiles.openAt(track.path, written).use { handle ->
                    track.pieces.withIndex().drop(mine.size).chunked(PARALLEL).forEach { batch ->
                        coroutineContext.ensureActive()
                        conditions.currentBlock()?.let { throw IOException(it.message) }
                        val bodies = coroutineScope {
                            batch.map { (_, piece) -> async { fetchPiece(entity, piece, keys) } }.awaitAll()
                        }
                        val rows = batch.zip(bodies) { (index, _), bytes ->
                            val buffer = ByteBuffer.wrap(bytes)
                            while (buffer.hasRemaining()) handle.channel.write(buffer)
                            ChunkEntity(entity.id, first + index, written, written + bytes.size - 1, bytes.size.toLong(), complete = true)
                                .also { written += bytes.size }
                        }
                        chunkDao.insertAll(rows)
                        val batchBytes = bodies.sumOf { it.size }
                        downloaded += batchBytes
                        usage.add(entity.provider, batchBytes)
                        // The total is only known at the end: estimate it from the pieces so far.
                        val estimate = if (yt) ytBytes else downloaded * total / (first + batch.last().index + 1)
                        progressTracker.update(entity.id, downloaded, estimate, DownloadState.DOWNLOADING)
                        val throttleMs = rateLimiter.reserve(batchBytes)
                        if (throttleMs > 0) delay(throttleMs)
                    }
                }
                sizes += written
                first += track.pieces.size
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            coroutineContext.ensureActive()
            return@withContext DownloadOutcome.Failure(e.message ?: "Download interrupted")
        } finally {
            withContext(NonCancellable) { downloadDao.updateBytes(entity.id, downloaded) }
        }

        // The join below can take a minute or more with the bar at 100%: say so.
        progressTracker.update(entity.id, downloaded, downloaded, DownloadState.DOWNLOADING, finishing = true)
        val size = try {
            when {
                tracks.size == 2 -> combine(partPath, finalPath, separateAudio = true)
                // Audio only: YouTube's M4A / WebM are already complete files, but a fragmented M4A often has
                // zero header durations. A file that passes the header check is fixed in place (M4A) or kept
                // as is (WebM); anything else is re-copied through the muxer.
                yt -> finishAudio(tracks[0].media, partPath, finalPath, sizes.single(), privatePart, coroutineContext[Job])
                    ?: combine(partPath, finalPath, separateAudio = false, audioOnly = true, input = privatePart ?: partPath)
                tracks[0].media.init != null -> try {
                    combine(partPath, finalPath, separateAudio = false)
                } catch (e: IOException) {
                    // Still the whole video, just a wrong length shown; better than nothing.
                    sizes.single().takeIf { DownloadFiles.verifyAndPromote(partPath, finalPath, it) }
                        ?.also { DownloadFiles.deletePart(partPath) } // the half-written .mux
                }
                else -> sizes.single().takeIf { DownloadFiles.verifyAndPromote(partPath, finalPath, it) }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: IOException) {
            return@withContext DownloadOutcome.Failure(e.message ?: "Finalize failed")
        } ?: return@withContext DownloadOutcome.Failure("Size verification failed")

        withContext(NonCancellable) {
            downloadDao.updateLink(entity.id, entity.downloadUrl, 1, size)
            downloadDao.updateBytes(entity.id, size)
            downloadDao.updateState(entity.id, DownloadState.COMPLETED)
            // Only now: killed before this point, the finished private file is still there for the retry.
            // A failed delete is harmless (it is also removed when the download is deleted).
            if (privatePart != null) DownloadFiles.deletePending(entity.id)
        }
        progressTracker.update(entity.id, size, size, DownloadState.COMPLETED)
        progressTracker.clear(entity.id)
        DownloadOutcome.Success
    }

    /** The media playlist at [url]; a clear IOException when it can't be downloaded. */
    private suspend fun media(entity: DownloadEntity, url: String): HlsPlaylist.Media {
        val call = client.newCall(entity.request(url).build())
        val playlist = try {
            cancellableCall(call) {
                call.execute().use { resp ->
                    if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                    // Pieces are relative to where the playlist ended up after redirects.
                    HlsPlaylist.parse(resp.body?.string().orEmpty(), resp.request.url.toString())
                }
            }
        } catch (e: IOException) {
            // Signed playlist links expire, and there's no page to get a new one from.
            throw IOException("Couldn't load the stream (${e.message}). Find the video in the Browser tab again.", e)
        }
        if (playlist !is HlsPlaylist.Media) throw IOException("Not a stream playlist")
        if (playlist.drm) throw IOException("This stream is DRM-protected and can't be downloaded")
        if (playlist.live) throw IOException("Live streams can't be downloaded")
        if (playlist.segments.isEmpty()) throw IOException("The stream is empty")
        return playlist
    }

    /**
     * The fast audio finish: header-checked (and for M4A patched in place) .part promoted as the final
     * file. Null when it doesn't apply or fails, so the caller runs [combine]. Rerun-safe: the patch is
     * idempotent, [DownloadFiles.verifyAndPromote] replaces an existing final file, and a stale .part.mux
     * is never promoted (it is deleted with the rest after the promote, or truncated by [combine]).
     */
    private fun finishAudio(
        media: HlsPlaylist.Media, partPath: String, finalPath: String, written: Long,
        privatePart: String? = null, job: Job? = null
    ): Long? {
        val tag = "AudioFinish"
        val start = System.nanoTime()
        fun ms() = (System.nanoTime() - start) / 1_000_000
        val kind = AudioFinish.kindFor(finalPath, 1, AudioFinish.isSingleProgressive(media))
        // A picked-folder download not taken private (e.g. an older version's) can't be fixed in place.
        if (kind == null || (privatePart == null && (SafPaths.isSaf(partPath) || SafPaths.isSaf(finalPath)))) {
            Log.i(tag, "combine: not a plain progressive file (${ms()} ms to decide)")
            return null
        }
        val workPath = privatePart ?: partPath
        try {
            val file = File(workPath)
            val expected = media.durationSec.toLong().takeIf { it > 0 }
            val reason = when (kind) {
                AudioFinish.Kind.M4A -> when (val r = Fmp4Header.check(file, expected)) {
                    is Fmp4Header.Result.Ok -> { Fmp4Header.patch(file, r.plan); null }
                    is Fmp4Header.Result.Unsupported -> r.reason
                }
                AudioFinish.Kind.WEBM -> when (val r = WebmHeader.check(file, expected)) {
                    is WebmHeader.Result.Ok -> null
                    is WebmHeader.Result.Unsupported -> r.reason
                }
            }
            if (reason != null) {
                Log.i(tag, "combine: header check said $reason (${ms()} ms)")
                return null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.i(tag, "combine: fast path failed (${e.message}) after ${ms()} ms")
            return null
        }
        if (privatePart != null) {
            // The finished file is kept on failure, so a retry only repeats the copy.
            val patchMs = ms()
            val copyStart = System.nanoTime()
            try {
                DownloadFiles.copyTo(File(privatePart), partPath) { job?.ensureActive() }
                if (!DownloadFiles.verifyAndPromote(partPath, finalPath, written)) throw DownloadFiles.copyFailed("Promoted file is not $written bytes")
            } catch (e: Exception) {
                runCatching { DownloadFiles.deletePart(partPath) }
                if (e is CancellationException) throw e
                Log.i(tag, "copy to the chosen folder failed (${e.cause?.message ?: e.message})")
                throw if (e is IOException) e else IOException(e.message, e)
            }
            Log.i(tag, "fast via private copy: patch $patchMs ms, copy ${(System.nanoTime() - copyStart) / 1_000_000} ms")
            return written
        }
        return try {
            if (!DownloadFiles.verifyAndPromote(partPath, finalPath, written)) {
                Log.i(tag, "combine: promote failed (${ms()} ms)")
                return null
            }
            // a stale .part.mux or .audio; the file is already in place, so never fail on this
            runCatching { DownloadFiles.deletePart(partPath) }
            Log.i(tag, "fast: $kind promoted without remux in ${ms()} ms")
            written
        } catch (e: Exception) {
            Log.i(tag, "combine: fast path failed (${e.message}) after ${ms()} ms")
            null
        }
    }

    /**
     * Video (.part) and its [separateAudio] (.part.audio) into one MP4 (or WebM, by [finalPath]'s extension)
     * (.part.mux), promoted to [finalPath]; the joined inputs are deleted
     * after. Returns its size.
     */
    private suspend fun combine(partPath: String, finalPath: String, separateAudio: Boolean, audioOnly: Boolean = false, input: String = partPath): Long? {
        val muxPath = "$partPath.mux"
        val size = DownloadFiles.openFd(input).use { video ->
            val audio = if (separateAudio) DownloadFiles.openFd("$partPath.audio") else null
            audio.use {
                DownloadFiles.openFd(muxPath).use { out ->
                    // A combine cut short last time leaves its bytes behind.
                    Os.ftruncate(out.fileDescriptor, 0)
                    if (audioOnly) StreamMuxer.mux(null, video.fileDescriptor, out.fileDescriptor, StreamMuxer.Output.of(finalPath))
                    else StreamMuxer.mux(video.fileDescriptor, audio?.fileDescriptor, out.fileDescriptor, StreamMuxer.Output.of(finalPath))
                    out.statSize
                }
            }
        }
        if (!DownloadFiles.verifyAndPromote(muxPath, finalPath, size)) return null
        // Only the joined inputs are left: .part and .part.audio.
        DownloadFiles.deletePart(partPath)
        if (input != partPath) runCatching { File(input).delete() }
        return size
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
                val bytes = resp.body?.bytes() ?: throw IOException("Empty response body")
                if (expected == 206 && bytes.size.toLong() != length) throw IOException("Short piece: ${bytes.size} of $length bytes")
                bytes
            }
        }
    }
}
