package com.abhinavxt.debforge.player

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext

/**
 * Small frames for the seek preview, from Android's MediaMetadataRetriever:
 * a downloaded file directly, a stream over HTTP (range requests, so each
 * new spot costs part of the video's data). One retriever per file, one
 * frame at a time; frames are cached per [Thumbnails.bucket], so dragging
 * back and forth doesn't fetch the same spot twice.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ThumbnailLoader(private val context: Context) {

    private val worker = Dispatchers.IO.limitedParallelism(1)
    private var retriever: MediaMetadataRetriever? = null
    private var source: Uri? = null
    private val cache = LruCache<Long, Bitmap>(CACHE_FRAMES)

    /** This source gave no frame (format, server or device): stop asking. */
    @Volatile var failed = false
        private set

    /** The frame near [positionMs], or null (not available, or failed). */
    suspend fun frame(uri: Uri, positionMs: Long, durationMs: Long): Bitmap? = withContext(worker) {
        val at = Thumbnails.bucket(positionMs, durationMs)
        if (uri != source) open(uri)
        cache.get(at)?.let { return@withContext it }
        if (failed) return@withContext null
        val r = retriever ?: return@withContext null
        val frame = runCatching {
            r.getScaledFrameAtTime(at * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, WIDTH, HEIGHT)
        }.getOrNull()
        if (frame == null) failed = true else cache.put(at, frame)
        frame
    }

    /** Before the next file. */
    suspend fun reset() = withContext(worker) { close() }

    /** Leaving the player: let go of the retriever without waiting (it may be mid-frame). */
    fun release() {
        CoroutineScope(worker).launch { close() }
    }

    private fun open(uri: Uri) {
        close()
        source = uri
        retriever = runCatching {
            MediaMetadataRetriever().apply {
                if (uri.scheme == "http" || uri.scheme == "https") setDataSource(uri.toString(), emptyMap())
                else setDataSource(context, uri)
            }
        }.getOrNull()
        failed = retriever == null
    }

    private fun close() {
        retriever?.let { runCatching { it.release() } }
        retriever = null
        source = null
        failed = false
        cache.evictAll()
    }

    private companion object {
        /** Preview size; the height is the most it can be, the picture keeps its shape. */
        const val WIDTH = 320
        const val HEIGHT = 240
        const val CACHE_FRAMES = 40
    }
}

/** Where previews are taken: pure, unit-tested in ThumbnailsTest. */
object Thumbnails {
    /** Never closer together than this. */
    private const val MIN_STEP_MS = 2_000L
    /** About this many spots across a whole file. */
    private const val SPOTS = 300

    /** [positionMs] rounded down to its preview spot. */
    fun bucket(positionMs: Long, durationMs: Long): Long {
        val step = maxOf(MIN_STEP_MS, if (durationMs > 0) durationMs / SPOTS else MIN_STEP_MS)
        return (positionMs.coerceAtLeast(0) / step) * step
    }
}
