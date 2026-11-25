package com.abhinavxt.debforge.player

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import com.abhinavxt.debforge.data.local.DownloadDao
import com.abhinavxt.debforge.domain.DownloadState
import com.abhinavxt.debforge.download.LocalFiles
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Collections

/**
 * A frame from a downloaded video near where it was left off, for Continue watching cards that have
 * no artwork. Frames are kept as small JPEGs in the cache folder (capped, oldest dropped first).
 * Streamed items have no file, so they get null and the card keeps its generated art.
 */
object ContinueThumbnails {
    private const val MAX_FILES = 60
    private const val WIDTH = 480
    private const val HEIGHT = 270

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps { fun downloadDao(): DownloadDao }

    // One decode at a time, off the main thread: a row of cards would otherwise open many retrievers at once.
    private val lock = Mutex()
    private val failed: MutableSet<String> = Collections.synchronizedSet(HashSet())

    /** One file per item and preview spot; the spot moves only as far as [Thumbnails.bucket] says. */
    fun cacheName(itemId: String, positionMs: Long, durationMs: Long): String =
        "${itemId.hashCode().toUInt().toString(16)}_${itemId.length}_${Thumbnails.bucket(positionMs, durationMs)}.jpg"

    /** Names to delete so at most [max] stay: the least recently modified go first. */
    fun evictions(files: List<Pair<String, Long>>, max: Int): List<String> =
        files.sortedByDescending { it.second }.drop(max).map { it.first }

    suspend fun load(context: Context, itemId: String, positionMs: Long, durationMs: Long): File? {
        val name = cacheName(itemId, positionMs, durationMs)
        if (name in failed) return null
        return withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "continue_thumbs").apply { mkdirs() }
            val out = File(dir, name)
            if (out.length() > 0) return@withContext out.also { it.setLastModified(System.currentTimeMillis()) }
            lock.withLock {
                if (out.length() > 0) return@withLock out
                val file = runCatching { decode(context, itemId, positionMs, durationMs, out) }.getOrNull()
                if (file == null) failed += name else trim(dir)
                file
            }
        }
    }

    private suspend fun decode(context: Context, itemId: String, positionMs: Long, durationMs: Long, out: File): File? {
        val dao = EntryPointAccessors.fromApplication(context.applicationContext, Deps::class.java).downloadDao()
        val row = dao.getById(itemId)?.takeIf { it.state == DownloadState.COMPLETED } ?: return null
        val path = row.finalFilePath?.takeIf { LocalFiles.exists(it) } ?: return null
        val uri = LocalFiles.uriFor(context, path) ?: return null
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val at = Thumbnails.bucket(positionMs, durationMs)
            val frame = retriever.getScaledFrameAtTime(at * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, WIDTH, HEIGHT)
                ?: return null
            out.outputStream().use { frame.compress(Bitmap.CompressFormat.JPEG, 80, it) }
            return out.takeIf { it.length() > 0 }
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun trim(dir: File) {
        val files = dir.listFiles().orEmpty().map { it.name to it.lastModified() }
        evictions(files, MAX_FILES).forEach { File(dir, it).delete() }
    }
}
