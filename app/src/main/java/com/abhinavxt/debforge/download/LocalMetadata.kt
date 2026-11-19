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
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/** Pure decisions of the metadata pass (unit-tested). */
object LocalMetadataLogic {
    /** Files checked per batch; a run keeps taking batches until none are left. */
    const val MAX_PER_PASS = 60

    /** Longest side of a saved picture, in pixels. */
    const val ART_SIZE = 512

    /** How long one file may take before the pass moves on. */
    const val FILE_TIMEOUT_MS = 15_000L

    /**
     * A LOCAL row is inserted with a blank title (and no artist), so a blank title means "not checked yet".
     * A file that opened but has no tags gets the cleaned file name; one that failed to open stays blank
     * (retried on a later scan, skipped for the rest of the current run).
     */
    fun needsCheck(title: String?): Boolean = title.isNullOrBlank()

    /** The title to store: the tag when it has text, else [fallback]. */
    fun titleFor(tag: String?, fallback: String): String =
        tag?.trim()?.takeIf { it.isNotEmpty() } ?: fallback.ifBlank { "-" }

    /** Title to store after a read: null (leave blank) when the file could not be opened. */
    fun titleToStore(opened: Boolean, tag: String?, fallback: String): String? =
        if (opened) titleFor(tag, fallback) else null

    fun artistFor(tag: String?): String? = tag?.trim()?.takeIf { it.isNotEmpty() }

    /** Rows to look at next, in order, never more than [MAX_PER_PASS], without those in [skipped]. Empty means the run is done. */
    fun <T> batch(unchecked: List<T>, skipped: Set<T> = emptySet()): List<T> =
        unchecked.filter { it !in skipped }.take(MAX_PER_PASS)

    /** Rows to ask the database for so skipped ones can't crowd out the rest. */
    fun fetchLimit(skippedCount: Int): Int = MAX_PER_PASS + skippedCount

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
    private val pending = AtomicBoolean(false)

    /** Returns at once. A kick during a run makes the run look again before it ends. */
    fun kick() {
        pending.set(true)
        if (!running.compareAndSet(false, true)) return
        scope.launch {
            try {
                val skipped = HashSet<String>() // failed to open: not retried until the next run
                while (pending.getAndSet(false)) runBatches(skipped)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Throwable) {
                Log.w(TAG, "Metadata pass failed", e)
            } finally {
                running.set(false)
                if (pending.get()) kick() // a kick landed between the last check and the reset
            }
        }
    }

    private suspend fun runBatches(skipped: MutableSet<String>) {
        while (true) {
            val unchecked = dao.localUnchecked(LocalMetadataLogic.fetchLimit(skipped.size))
            val todo = LocalMetadataLogic.batch(unchecked.map { it.id }, skipped)
            if (todo.isEmpty()) return
            val byId = unchecked.associateBy { it.id }
            for (id in todo) {
                kotlin.coroutines.coroutineContext.ensureActive()
                val row = byId.getValue(id)
                val read = readWithTimeout(row)
                if (read == null) { skipped += id; continue }
                val fallback = releaseLabels(row.filename).title
                val title = LocalMetadataLogic.titleToStore(true, read.title, fallback) ?: continue
                dao.setLocalMeta(id, title, LocalMetadataLogic.artistFor(read.artist))
                // The row may have been removed while the picture was written: don't leave it behind.
                if (dao.getById(id) == null) artworkFile(context, id).delete()
            }
        }
    }

    /**
     * Reads on its own coroutine so a file that hangs can't block the pass. The retriever call can't be
     * interrupted: on timeout the pass moves on and that thread finishes (and releases the retriever) on its own.
     */
    private suspend fun readWithTimeout(row: DownloadEntity): Tags? {
        val job = scope.async { runCatching { read(row) }.getOrNull() }
        val result = withTimeoutOrNull(LocalMetadataLogic.FILE_TIMEOUT_MS) { job.await() }
        if (result == null) job.cancel()
        return result
    }

    private class Tags(val title: String?, val artist: String?)

    /** Tags of the file, also saving the artwork. Null when the file can't be opened. */
    private fun read(row: DownloadEntity): Tags? {
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
            return Tags(title, artist)
        } catch (e: Exception) {
            return null
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
        // minSdk 30, so the scaled variant (API 27) is always there: no full-size frame is decoded.
        return r.getScaledFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, max, max)
    }

    private fun saveArtwork(id: String, bitmap: Bitmap) {
        val file = artworkFile(context, id).also { it.parentFile?.mkdirs() }
        val tmp = File(file.path + ".tmp")
        try {
            val max = LocalMetadataLogic.ART_SIZE
            val scale = minOf(1f, max.toFloat() / maxOf(bitmap.width, bitmap.height))
            val out = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true) else bitmap
            try {
                tmp.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            } finally {
                if (out !== bitmap) out.recycle()
            }
            if (!tmp.renameTo(file)) tmp.delete()
        } finally {
            bitmap.recycle()
            tmp.delete()
        }
    }

    private companion object { const val TAG = "LocalMetadata" }
}
