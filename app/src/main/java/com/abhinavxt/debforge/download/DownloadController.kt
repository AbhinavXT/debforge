package com.abhinavxt.debforge.download

import android.content.Context
import android.content.Intent
import android.os.Build
import com.abhinavxt.debforge.data.local.DownloadDao
import com.abhinavxt.debforge.data.local.DownloadEntity
import com.abhinavxt.debforge.data.prefs.SettingsStore
import com.abhinavxt.debforge.domain.DownloadItem
import com.abhinavxt.debforge.domain.DownloadState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The app's entry point for download actions (called by the ViewModel in
 * Phase 3). It writes queue state to Room and signals [DownloadService]; it
 * never touches the network or files directly beyond resolving a unique path.
 */
@Singleton
class DownloadController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val downloadDao: DownloadDao,
    private val settingsStore: SettingsStore
) {
    /**
     * Queues an item for download. Resolves the destination path now (so it's
     * stable even if the user later changes the folder setting) and starts the
     * service. No-op if this id is already present.
     */
    suspend fun enqueue(item: DownloadItem) {
        if (downloadDao.getById(item.id) != null) {
            // Already tracked — just make sure the engine is running.
            signal(DownloadService.ACTION_PROCESS)
            return
        }
        val dir = settingsStore.downloadDirFlow.first()
        val name = sanitizeFilename(item.filename)
        val finalPath = withContext(Dispatchers.IO) { uniquePath(dir, name) }
        val partPath = DownloadFiles.partPathFor(finalPath)

        downloadDao.upsert(
            DownloadEntity(
                rdId = item.id,
                filename = name,
                originalLink = item.originalLink,
                downloadUrl = item.downloadUrl,
                host = item.host,
                filesize = item.filesize,
                chunksAllowed = item.chunks.coerceAtLeast(1),
                state = DownloadState.QUEUED,
                partFilePath = partPath,
                finalFilePath = finalPath
            )
        )
        signal(DownloadService.ACTION_PROCESS)
    }

    suspend fun pause(id: String) = signal(DownloadService.ACTION_PAUSE, id)

    suspend fun cancel(id: String) = signal(DownloadService.ACTION_CANCEL, id)

    suspend fun resume(id: String) {
        downloadDao.updateState(id, DownloadState.QUEUED)
        signal(DownloadService.ACTION_PROCESS)
    }

    /** Retry a FAILED download: clear the error, re-queue, kick the engine. */
    suspend fun retry(id: String) {
        downloadDao.setFailed(id, message = null, state = DownloadState.QUEUED)
        signal(DownloadService.ACTION_PROCESS)
    }

    // --- helpers -----------------------------------------------------------

    private fun signal(action: String, id: String? = null) {
        val intent = Intent(context, DownloadService::class.java).apply {
            this.action = action
            if (id != null) putExtra(DownloadService.EXTRA_ID, id)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    /** Strip path separators and characters illegal on common filesystems. */
    private fun sanitizeFilename(raw: String): String {
        val cleaned = raw.replace(Regex("[/\\\\:*?\"<>|]"), "_").trim()
        return cleaned.ifBlank { "download_${System.currentTimeMillis()}" }
    }

    /** If "dir/name" exists, insert " (1)", " (2)", ... before the extension. */
    private fun uniquePath(dir: String, name: String): String {
        val base = File(dir, name)
        if (!base.exists()) return base.absolutePath
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (true) {
            val candidate = File(dir, "$stem ($i)$ext")
            if (!candidate.exists()) return candidate.absolutePath
            i++
        }
    }
}
