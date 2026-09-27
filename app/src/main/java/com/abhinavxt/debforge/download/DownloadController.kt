package com.abhinavxt.debforge.download

import com.abhinavxt.debforge.data.local.DownloadDao
import com.abhinavxt.debforge.data.local.DownloadEntity
import com.abhinavxt.debforge.data.prefs.SettingsStore
import com.abhinavxt.debforge.domain.DownloadItem
import com.abhinavxt.debforge.domain.DownloadState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import com.abhinavxt.debforge.domain.MediaKind
import com.abhinavxt.debforge.domain.ReleaseNameParser
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The app's entry point for download actions (called by the ViewModel in
 * Phase 3). It writes queue state to Room and asks [DownloadScheduler] to run
 * the queue (or talks to [DownloadQueueRunner] directly for pause/cancel); it
 * never touches the network or files directly beyond resolving a unique path.
 */
@Singleton
class DownloadController @Inject constructor(
    private val downloadDao: DownloadDao,
    private val settingsStore: SettingsStore,
    private val runner: DownloadQueueRunner,
    private val scheduler: DownloadScheduler
) {
    /**
     * Serializes path resolution + insert. Without it, two quick enqueues of
     * same-named files (common with TorBox: many torrents contain "Sample.mkv"
     * or identically named episodes) could both pick the same free path.
     */
    private val enqueueLock = Mutex()

    /**
     * Queues an item for download. Resolves the destination path now (so it's
     * stable even if the user later changes the folder setting) and starts the
     * service. No-op if this id is already present.
     */
    suspend fun enqueue(item: DownloadItem) {
        enqueueLock.withLock {
            if (downloadDao.getById(item.id) != null) {
                // Already tracked — just make sure the engine is running.
                scheduler.ensureRunning()
                return
            }
            val root = settingsStore.downloadDirFlow.first()
            val name = sanitizeFilename(item.filename)
            // Optional Plex/Jellyfin layout, decided from the release name.
            val sub = if (settingsStore.organizeLibraryFlow.first()) libraryFolderFor(item.filename) else ""
            val finalPath = uniquePath(root, sub, name)
            val partPath = DownloadFiles.partPathFor(finalPath)

            downloadDao.upsert(
                DownloadEntity(
                    id = item.id,
                    provider = item.provider,
                    filename = name,
                    sourceRef = item.sourceRef,
                    // Empty = resolve lazily in the engine (TorBox-style providers).
                    downloadUrl = item.downloadUrl.orEmpty(),
                    host = item.host,
                    filesize = item.filesize,
                    chunksAllowed = item.maxConnections.coerceAtLeast(1),
                    state = DownloadState.QUEUED,
                    partFilePath = partPath,
                    finalFilePath = finalPath
                )
            )
        }
        scheduler.ensureRunning()
    }

    fun pause(id: String) = runner.pause(id)

    fun cancel(id: String) = runner.cancel(id)

    /**
     * Pauses everything queued or running (widget, Quick Settings tile).
     * Queued rows are marked first, so the runner can't pick the next one up
     * while the current download is being interrupted. Safe from the
     * background: it only stops work. Returns how many were paused.
     */
    suspend fun pauseAll(): Int {
        val queued = downloadDao.pauseQueued()
        val running = downloadDao.downloadingIds()
        running.forEach { runner.pause(it) }
        return queued + running.size
    }

    /**
     * Re-queues every paused download and starts the engine. Android only
     * allows starting it while DebForge is visible, so callers outside the
     * app open MainActivity with [com.abhinavxt.debforge.AppActions.RESUME_ALL].
     */
    suspend fun resumeAll(): Int {
        val n = downloadDao.requeuePaused()
        if (n > 0) scheduler.ensureRunning()
        return n
    }

    suspend fun resume(id: String) {
        downloadDao.updateState(id, DownloadState.QUEUED)
        scheduler.ensureRunning()
    }

    /** Retry a FAILED download: clear the error, re-queue, kick the engine. */
    suspend fun retry(id: String) {
        downloadDao.setFailed(id, message = null, state = DownloadState.QUEUED)
        scheduler.ensureRunning()
    }

    // --- helpers -----------------------------------------------------------

    /**
     * "Shows/The Bear/Season 02", "Movies/Oppenheimer (2023)", or "" for
     * anything unrecognised (stays in the root folder).
     */
    private fun libraryFolderFor(filename: String): String {
        val info = ReleaseNameParser.parse(filename)
        if (!info.isVideo) return ""
        return when (info.kind) {
            MediaKind.SHOW -> {
                val show = sanitizeFilename(info.title)
                if (info.season != null) "Shows/$show/Season %02d".format(Locale.ROOT, info.season)
                else "Shows/$show"
            }
            MediaKind.MOVIE -> "Movies/" + sanitizeFilename(if (info.year != null) "${info.title} (${info.year})" else info.title)
            MediaKind.OTHER -> ""
        }
    }

    /** Strip path separators and characters illegal on common filesystems. */
    private fun sanitizeFilename(raw: String): String {
        val cleaned = raw.replace(Regex("[/\\\\:*?\"<>|]"), "_").trim()
        return cleaned.ifBlank { "download_${System.currentTimeMillis()}" }
    }

    /**
     * If "dir/name" is taken, insert " (1)", " (2)", ... before the extension.
     * "Taken" means: a file (or its .part) exists on disk, OR another tracked
     * download already targets that path. The DB check matters because queued
     * items don't create their .part until they start — previously two queued
     * files with the same name got the same path and the second one to finish
     * silently replaced the first (verifyAndPromote deletes an existing final).
     *
     * [root] is a plain folder path or a folder picked with the system picker
     * (tree URI); [sub] is the optional library subfolder ("" for none).
     */
    private suspend fun uniquePath(root: String, sub: String, name: String): String {
        fun pathFor(fileName: String): String =
            if (SafPaths.isTreeUri(root)) {
                SafPaths.join(root, listOf(sub, fileName).filter { it.isNotEmpty() }.joinToString("/"))
            } else {
                File(if (sub.isEmpty()) File(root) else File(root, sub), fileName).absolutePath
            }

        suspend fun taken(path: String): Boolean =
            withContext(Dispatchers.IO) {
                DownloadFiles.exists(path) || DownloadFiles.exists(DownloadFiles.partPathFor(path))
            } || downloadDao.isFinalPathClaimed(path)

        val base = pathFor(name)
        if (!taken(base)) return base
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (true) {
            val candidate = pathFor("$stem ($i)$ext")
            if (!taken(candidate)) return candidate
            i++
        }
    }
}
