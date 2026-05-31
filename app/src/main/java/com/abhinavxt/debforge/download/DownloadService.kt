package com.abhinavxt.debforge.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.abhinavxt.debforge.R
import com.abhinavxt.debforge.data.local.ChunkDao
import com.abhinavxt.debforge.data.local.DownloadDao
import com.abhinavxt.debforge.data.local.DownloadEntity
import com.abhinavxt.debforge.domain.DownloadProgress
import com.abhinavxt.debforge.domain.DownloadState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Foreground service that runs the download queue, one file at a time.
 *
 * Concurrency model:
 *  - A single [queueJob] loops: pull oldest QUEUED row -> mark DOWNLOADING ->
 *    run it as a child [currentJob] -> handle outcome -> repeat -> stop when the
 *    queue empties.
 *  - Pause/cancel of the ACTIVE item cancels only [currentJob]; the loop stays
 *    alive and advances to the next item. The intent handler sets [interrupt]
 *    so the loop knows whether the cancellation meant PAUSE or CANCEL.
 *  - Non-active pause/cancel is a direct DB edit.
 *
 * Cooperative cancellation in [ChunkedDownloader] persists per-chunk progress in
 * a NonCancellable block, so a paused/cancelled item leaves a resumable .part.
 */
@AndroidEntryPoint
class DownloadService : Service() {

    @Inject lateinit var downloader: ChunkedDownloader
    @Inject lateinit var downloadDao: DownloadDao
    @Inject lateinit var chunkDao: ChunkDao
    @Inject lateinit var progressTracker: DownloadProgressTracker

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var queueJob: Job? = null
    private var currentJob: Job? = null

    @Volatile private var currentId: String? = null
    @Volatile private var currentFilename: String? = null
    @Volatile private var interrupt: Interrupt? = null

    private enum class Interrupt { PAUSE, CANCEL }

    private val notificationManager by lazy {
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startNotificationUpdates()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must promote to foreground promptly after startForegroundService().
        startForeground(NOTIF_ID, buildNotification(null, currentFilename))
        when (intent?.action) {
            ACTION_PAUSE -> intent.getStringExtra(EXTRA_ID)?.let { pause(it) }
            ACTION_CANCEL -> intent.getStringExtra(EXTRA_ID)?.let { cancel(it) }
            else -> ensureQueueRunning() // ACTION_PROCESS or sticky restart (null)
        }
        return START_STICKY
    }

    // --- queue loop --------------------------------------------------------

    private fun ensureQueueRunning() {
        if (queueJob?.isActive == true) return
        queueJob = serviceScope.launch {
            // Recover items interrupted by a previous process death.
            downloadDao.requeueOrphans()
            try {
                while (isActive) {
                    val next = downloadDao.nextInState(DownloadState.QUEUED) ?: break
                    runOne(next)
                }
            } finally {
                withContext(NonCancellable) {
                    currentId = null
                    currentFilename = null
                    stopForegroundCompat()
                    stopSelf()
                }
            }
        }
    }

    private suspend fun runOne(entity: DownloadEntity) {
        interrupt = null
        currentId = entity.rdId
        currentFilename = entity.filename
        downloadDao.updateState(entity.rdId, DownloadState.DOWNLOADING)
        progressTracker.update(
            entity.rdId, entity.bytesDownloaded, entity.filesize, DownloadState.DOWNLOADING
        )

        val result = arrayOfNulls<DownloadOutcome>(1)
        val job = serviceScope.launch {
            result[0] = try {
                downloader.download(entity)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                DownloadOutcome.Failure(t.message ?: "Unexpected error")
            }
        }
        currentJob = job
        try {
            job.join()
        } finally {
            currentJob = null
        }

        when (interrupt) {
            Interrupt.PAUSE -> withContext(NonCancellable) {
                downloadDao.updateState(entity.rdId, DownloadState.PAUSED)
                progressTracker.clear(entity.rdId)
            }
            Interrupt.CANCEL -> withContext(NonCancellable) {
                chunkDao.deleteForDownload(entity.rdId)
                downloadDao.delete(entity.rdId)
                DownloadFiles.deletePart(entity.partFilePath)
                progressTracker.clear(entity.rdId)
            }
            null -> when (val outcome = result[0]) {
                is DownloadOutcome.Failure -> withContext(NonCancellable) {
                    downloadDao.setFailed(entity.rdId, outcome.message)
                    progressTracker.clear(entity.rdId)
                }
                else -> { /* Success: downloader already persisted COMPLETED */ }
            }
        }
        currentId = null
        currentFilename = null
    }

    // --- control -----------------------------------------------------------

    private fun pause(id: String) {
        if (id == currentId) {
            interrupt = Interrupt.PAUSE
            currentJob?.cancel()
        } else {
            serviceScope.launch { downloadDao.updateState(id, DownloadState.PAUSED) }
        }
    }

    private fun cancel(id: String) {
        if (id == currentId) {
            interrupt = Interrupt.CANCEL
            currentJob?.cancel()
        } else {
            serviceScope.launch {
                val entity = downloadDao.getById(id)
                chunkDao.deleteForDownload(id)
                downloadDao.delete(id)
                DownloadFiles.deletePart(entity?.partFilePath)
                progressTracker.clear(id)
            }
        }
    }

    // --- notifications -----------------------------------------------------

    private fun startNotificationUpdates() {
        serviceScope.launch {
            progressTracker.progress.collect { map ->
                val id = currentId ?: return@collect
                val p = map[id] ?: return@collect
                if (p.state == DownloadState.DOWNLOADING) {
                    notificationManager.notify(NOTIF_ID, buildNotification(p, currentFilename))
                }
            }
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Downloads",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Active download progress" }
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * Notification layout: filename as the title (gets ellipsized by the
     * system if long — the BigText style would expand it, but for an ongoing
     * progress notif a single line is the convention). Body text describes
     * current state: percent + speed when downloading, "Waiting for network"
     * when stalled, "Preparing" before [runOne] sets the filename.
     */
    private fun buildNotification(progress: DownloadProgress?, filename: String?): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        val title = filename ?: "DebForge"

        when {
            progress == null -> {
                builder.setContentTitle(title)
                    .setContentText("Preparing download")
                    .setProgress(0, 0, true)
            }
            progress.waitingForNetwork -> {
                builder.setContentTitle(title)
                    .setContentText("Waiting for network — will resume automatically")
                    .setProgress(0, 0, true)
            }
            else -> {
                val pct = (progress.fraction * 100).toInt()
                builder.setContentTitle(title)
                    .setContentText("$pct%  •  ${formatSpeed(progress.bytesPerSecond)}")
                    .setProgress(100, pct, false)
            }
        }
        return builder.build()
    }

    private fun formatSpeed(bps: Long): String = when {
        bps <= 0 -> "—"
        bps < 1024 -> "$bps B/s"
        bps < 1024 * 1024 -> "${bps / 1024} KB/s"
        else -> String.format("%.1f MB/s", bps / (1024.0 * 1024.0))
    }

    @Suppress("DEPRECATION")
    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    companion object {
        const val CHANNEL_ID = "debforge_downloads"
        const val NOTIF_ID = 1001

        const val ACTION_PROCESS = "com.abhinavxt.debforge.action.PROCESS"
        const val ACTION_PAUSE = "com.abhinavxt.debforge.action.PAUSE"
        const val ACTION_CANCEL = "com.abhinavxt.debforge.action.CANCEL"
        const val EXTRA_ID = "extra_download_id"
    }
}
