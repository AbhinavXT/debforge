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
    private const val READY_ID = 1003
    private const val FOLLOW_ID = 1004

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
                .apply { description = context.getString(R.string.notif_channel_desc) }
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
                .setContentText(context.getString(R.string.notif_preparing))
                .setProgress(0, 0, true)
            progress.waitingReasonRes != null -> builder
                .setContentText(
                    context.getString(R.string.notif_waiting_resume, context.getString(progress.waitingReasonRes))
                )
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
            .setContentTitle(context.getString(R.string.notif_paused_title))
            .setContentText(context.getString(R.string.notif_paused_body))
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context))
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(INFO_ID, n) }
    }

    /** Schedule window opened but Android didn't let us start in the background. */
    fun notifyScheduledWaiting(context: Context) {
        ensureChannel(context)
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_download)
            .setContentTitle(context.getString(R.string.notif_sched_title))
            .setContentText(context.getString(R.string.notif_sched_body))
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context))
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(INFO_ID, n) }
    }

    /** "Your torrent is ready" after a background check queued its files. */
    fun notifyReady(context: Context, names: List<String>) {
        ensureChannel(context)
        val title = if (names.size == 1) {
            context.getString(R.string.notif_ready_one, names.first())
        } else {
            context.resources.getQuantityString(R.plurals.notif_ready_many, names.size, names.size)
        }
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_download)
            .setContentTitle(title)
            .setContentText(context.getString(R.string.notif_ready_body))
            .setStyle(NotificationCompat.BigTextStyle().bigText(names.joinToString("\n")))
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context))
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(READY_ID, n) }
    }

    /** New episodes of followed shows were queued in the background. */
    fun notifyFollowed(context: Context, labels: List<String>) {
        ensureChannel(context)
        val title = if (labels.size == 1) {
            context.getString(R.string.notif_follow_one, labels.first())
        } else {
            context.resources.getQuantityString(R.plurals.notif_follow_many, labels.size, labels.size)
        }
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_download)
            .setContentTitle(title)
            .setContentText(context.getString(R.string.notif_follow_body))
            .setStyle(NotificationCompat.BigTextStyle().bigText(labels.joinToString("\n")))
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context))
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(FOLLOW_ID, n) }
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
