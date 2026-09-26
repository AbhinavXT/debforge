package com.abhinavxt.debforge.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.abhinavxt.debforge.MainActivity
import com.abhinavxt.debforge.R
import com.abhinavxt.debforge.domain.DownloadProgress
import com.abhinavxt.debforge.domain.DownloadState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Notification building shared by both queue hosts ([DownloadJobService] and
 * [DownloadService]) so the user sees the same thing whichever one runs.
 */
object DownloadNotifications {

    const val CHANNEL_ID = "debforge_downloads"
    const val ONGOING_ID = 1001
    private const val INFO_ID = 1002

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Active download progress" }
        )
    }

    /**
     * Ongoing progress notification. Filename as title; body is percent +
     * speed, "Waiting for network", or "Preparing" before the first file.
     */
    fun ongoing(context: Context, progress: DownloadProgress?, filename: String?): Notification {
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openAppIntent(context))
            .setContentTitle(filename ?: "DebForge")

        when {
            progress == null -> builder
                .setContentText("Preparing download")
                .setProgress(0, 0, true)
            progress.waitingForNetwork -> builder
                .setContentText("Waiting for network — will resume automatically")
                .setProgress(0, 0, true)
            else -> {
                val pct = (progress.fraction * 100).toInt()
                builder
                    .setContentText("$pct%  •  ${formatSpeed(progress.bytesPerSecond)}")
                    .setProgress(100, pct, false)
            }
        }
        return builder.build()
    }

    /**
     * Shown when Android stops the foreground-service host at its daily time
     * limit. Tapping opens the app, which resumes the queue (the limit resets
     * once the app is in the foreground).
     */
    fun notifyTimeLimitReached(context: Context) {
        ensureChannel(context)
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_download)
            .setContentTitle("Downloads paused")
            .setContentText("Android's background time limit was reached. Tap to continue.")
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context))
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(INFO_ID, n) }
    }

    /**
     * Keeps [ONGOING_ID] in sync with the runner's active file + live progress
     * until [scope] is cancelled.
     */
    fun observe(
        context: Context,
        scope: CoroutineScope,
        runner: DownloadQueueRunner,
        tracker: DownloadProgressTracker
    ): Job = scope.launch {
        val nm = context.getSystemService(NotificationManager::class.java)
        combine(runner.active, tracker.progress) { active, map -> active to map }
            .collect { (active, map) ->
                val active1 = active ?: return@collect
                val p = map[active1.id]
                if (p == null || p.state == DownloadState.DOWNLOADING) {
                    // notify() can throw SecurityException if the user revoked
                    // notifications mid-run; the download must not die for it.
                    runCatching { nm.notify(ONGOING_ID, ongoing(context, p, active1.filename)) }
                }
            }
    }

    private fun openAppIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun formatSpeed(bps: Long): String = when {
        bps <= 0 -> "—"
        bps < 1024 -> "$bps B/s"
        bps < 1024 * 1024 -> "${bps / 1024} KB/s"
        else -> String.format("%.1f MB/s", bps / (1024.0 * 1024.0))
    }
}
