package com.abhinavxt.debforge.download

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import com.abhinavxt.debforge.data.local.DownloadDao
import com.abhinavxt.debforge.data.local.DownloadEntity
import com.abhinavxt.debforge.ui.components.releaseLabels
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/** Pure decisions of the metadata pass (unit-tested). */
object LocalMetadataLogic {
    /** Files checked per pass; the rest wait for the next scan. */
    const val MAX_PER_PASS = 60

    /** Longest side of a saved picture, in pixels. */
    const val ART_SIZE = 512

    /**
     * A LOCAL row is inserted with a blank title (and no artist), so a blank title means "not checked yet".
     * Checking always stores a non-blank title (the file's tag, else the cleaned file name), so a file
     * without tags, or one that can't be read, is not checked again.
     */
    fun needsCheck(title: String?): Boolean = title.isNullOrBlank()

    /** The title to store: the tag when it has text, else [fallback]. */
    fun titleFor(tag: String?, fallback: String): String =
        tag?.trim()?.takeIf { it.isNotEmpty() } ?: fallback.ifBlank { "-" }

    fun artistFor(tag: String?): String? = tag?.trim()?.takeIf { it.isNotEmpty() }

    /** Rows to look at this pass, in order, never more than [MAX_PER_PASS]. */
    fun <T> batch(unchecked: List<T>): List<T> = unchecked.take(MAX_PER_PASS)

    /** Sample size so a decoded picture isn't much larger than [ART_SIZE]. */
    fun sampleSize(width: Int, height: Int): Int {
        var s = 1
        val longest = maxOf(width, height)
        while (longest / (s * 2) >= ART_SIZE) s *= 2
        return s
    }
}

/**
 * Reads title, artist and artwork of imported files in the background. Never blocks a scan:
 * [kick] returns at once and at most one pass runs at a time.
 */
@Singleton
class LocalMetadata @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: DownloadDao
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)

    fun kick() {
        if (!running.compareAndSet(false, true)) return
        scope.launch {
            try {
                pass()
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Throwable) {
                Log.w(TAG, "Metadata pass failed", e)
            } finally {
                running.set(false)
            }
        }
    }

    private suspend fun pass() {
        val todo = LocalMetadataLogic.batch(dao.localUnchecked(LocalMetadataLogic.MAX_PER_PASS))
        for (row in todo) {
            kotlin.coroutines.coroutineContext.ensureActive()
            val read = runCatching { read(row) }.getOrNull()
            val fallback = releaseLabels(row.filename).title
            dao.setLocalMeta(row.id, LocalMetadataLogic.titleFor(read?.first, fallback), LocalMetadataLogic.artistFor(read?.second))
        }
    }

    /** (title tag, artist tag); also saves the artwork. Null when the file can't be read. */
    private fun read(row: DownloadEntity): Pair<String?, String?>? {
        val path = row.finalFilePath ?: row.sourceRef
        val retriever = MediaMetadataRetriever()
        try {
            if (SafPaths.isSaf(path)) {
                val uri: Uri = LocalFiles.uriFor(context, path) ?: return null
                retriever.setDataSource(context, uri)
            } else {
                retriever.setDataSource(path)
            }
            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
            val hasVideo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
            val bitmap = runCatching { picture(retriever, hasVideo) }.getOrNull()
            if (bitmap != null) saveArtwork(row.id, bitmap)
            return title to artist
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun picture(r: MediaMetadataRetriever, hasVideo: Boolean): Bitmap? {
        val max = LocalMetadataLogic.ART_SIZE
        val bytes = r.embeddedPicture
        if (bytes != null) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val opts = BitmapFactory.Options().apply { inSampleSize = LocalMetadataLogic.sampleSize(bounds.outWidth, bounds.outHeight) }
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        }
        if (!hasVideo) return null
        return r.getScaledFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, max, max)
            ?: r.getFrameAtTime(-1, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
    }

    private fun saveArtwork(id: String, bitmap: Bitmap) {
        val file = artworkFile(context, id).also { it.parentFile?.mkdirs() }
        val tmp = File(file.path + ".tmp")
        try {
            val max = LocalMetadataLogic.ART_SIZE
            val scale = minOf(1f, max.toFloat() / maxOf(bitmap.width, bitmap.height))
            val out = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true) else bitmap
            tmp.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            if (!tmp.renameTo(file)) tmp.delete()
        } finally {
            tmp.delete()
        }
    }

    private companion object { const val TAG = "LocalMetadata" }
}
