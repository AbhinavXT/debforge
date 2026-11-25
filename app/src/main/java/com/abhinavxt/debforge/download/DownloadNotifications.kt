package com.abhinavxt.debforge.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import com.abhinavxt.debforge.AppActions
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
    private const val SUBSCRIPTION_ID = 1010
    private const val FEED_GONE_BASE = 20000
    private const val STOPPED_ID = 1005
    private const val STORAGE_ID = 1006
    private const val PAUSED_ID = 1007
    private const val CANCEL_ASK_ID = 1008
    private const val CANCEL_ASK_TIMEOUT_MS = 30_000L

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
            .addAction(0, context.getString(R.string.notif_act_pause), actionIntent(context, DownloadActionReceiver.ACTION_PAUSE, 10))
            .addAction(0, context.getString(R.string.notif_act_cancel), actionIntent(context, DownloadActionReceiver.ACTION_CANCEL_ASK, 11))

        var percent: Int? = null
        when {
            progress == null -> builder
                .setContentText(context.getString(R.string.notif_preparing))
                .setProgress(0, 0, true)
            progress.finishing -> builder
                .setContentText(context.getString(R.string.dl_finishing))
                .setProgress(0, 0, true)
            progress.waitingReasonRes != null -> builder
                .setContentText(
                    context.getString(R.string.notif_waiting_resume, context.getString(progress.waitingReasonRes))
                )
                .setProgress(0, 0, true)
            else -> {
                val pct = (progress.fraction * 100).toInt()
                percent = pct
                builder
                    .setContentText("$pct%  •  ${formatSpeed(progress.bytesPerSecond)}")
                    .setProgress(100, pct, false)
            }
        }
        val n = builder.build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) liveUpdate(context, n, percent) else n
    }

    /**
     * Android 16 Live Update: the same notification, promoted, with the system
     * progress style and the percent as the short chip text. Needs
     * POST_PROMOTED_NOTIFICATIONS (manifest); if the system declines to promote
     * it, it stays an ordinary ongoing notification. Built from the compat
     * result because the pinned androidx.core predates these APIs.
     */
    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private fun liveUpdate(context: Context, n: Notification, percent: Int?): Notification {
        val style = Notification.ProgressStyle()
        if (percent == null) style.setProgressIndeterminate(true) else style.setProgress(percent)
        return Notification.Builder.recoverBuilder(context, n)
            .setStyle(style)
            .setRequestPromotedOngoing(true)
            .setShortCriticalText(percent?.let { "$it%" })
            .build()
    }

    /** Everything was paused from the notification: a dismissible one with Resume (opens the app, the only place downloads may restart). */
    fun notifyPausedByUser(context: Context) {
        ensureChannel(context)
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_download)
            .setContentTitle(context.getString(R.string.notif_paused_title))
            .setContentText(context.getString(R.string.notif_paused_all_body))
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context))
            .addAction(0, context.getString(R.string.notif_act_resume), AppActions.pending(context, AppActions.RESUME_ALL))
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(PAUSED_ID, n) }
    }

    fun cancelPausedByUser(context: Context) {
        runCatching { context.getSystemService(NotificationManager::class.java).cancel(PAUSED_ID) }
    }

    /** Second step of Cancel: nothing is discarded unless the user taps Yes. Times out on its own. */
    fun notifyCancelAsk(context: Context) {
        ensureChannel(context)
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_download)
            .setContentTitle(context.getString(R.string.notif_cancel_title))
            .setContentText(context.getString(R.string.notif_cancel_body))
            .setTimeoutAfter(CANCEL_ASK_TIMEOUT_MS)
            .setAutoCancel(true)
            .addAction(0, context.getString(R.string.notif_act_yes), actionIntent(context, DownloadActionReceiver.ACTION_CANCEL_YES, 12))
            .addAction(0, context.getString(R.string.notif_act_keep), actionIntent(context, DownloadActionReceiver.ACTION_KEEP, 13))
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(CANCEL_ASK_ID, n) }
    }

    fun dismissCancelAsk(context: Context) {
        runCatching { context.getSystemService(NotificationManager::class.java).cancel(CANCEL_ASK_ID) }
    }

    /** Broadcast to our own non-exported receiver; the request code keeps one PendingIntent per button. */
    private fun actionIntent(context: Context, action: String, requestCode: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, DownloadActionReceiver::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

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
            .addAction(0, context.getString(R.string.notif_act_retry), AppActions.pending(context, AppActions.RESUME_ALL))
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(INFO_ID, n) }
    }

    /**
     * Android stopped the downloads and nothing could restart them from the
     * background. One notification, updated in place; cancelled when the queue
     * runs again. Tapping opens the app, which resumes the queue.
     */
    fun notifyStoppedBySystem(context: Context) {
        ensureChannel(context)
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_download)
            .setContentTitle(context.getString(R.string.notif_stopped_title))
            .setContentText(context.getString(R.string.notif_stopped_body))
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context))
            .addAction(0, context.getString(R.string.notif_act_retry), AppActions.pending(context, AppActions.RESUME_ALL))
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(STOPPED_ID, n) }
    }

    fun cancelStoppedBySystem(context: Context) {
        runCatching { context.getSystemService(NotificationManager::class.java).cancel(STOPPED_ID) }
    }

    /** The storage limit is holding the queue. One notification, updated in place. */
    fun notifyStorageLimit(context: Context) {
        ensureChannel(context)
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_download)
            .setContentTitle(context.getString(R.string.notif_storage_title))
            .setContentText(context.getString(R.string.notif_storage_text))
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context))
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(STORAGE_ID, n) }
    }

    fun cancelStorageLimit(context: Context) {
        runCatching { context.getSystemService(NotificationManager::class.java).cancel(STORAGE_ID) }
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

    /** A followed podcast feed keeps failing for good (gone or not a feed any more). */
    fun notifyFeedGone(context: Context, title: String) {
        ensureChannel(context)
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_download)
            .setContentTitle(context.getString(R.string.notif_feed_gone, title))
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context))
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(FEED_GONE_BASE + (title.hashCode() and 0xFFFF), n) }
    }

    /** New uploads from followed channels were found in the background. */
    fun notifyNewUploads(context: Context, labels: List<String>) {
        ensureChannel(context)
        val title = if (labels.size == 1) {
            context.getString(R.string.notif_sub_one, labels.first())
        } else {
            context.resources.getQuantityString(R.plurals.notif_sub_many, labels.size, labels.size)
        }
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_download)
            .setContentTitle(title)
            .setContentText(context.getString(R.string.notif_sub_body))
            .setStyle(NotificationCompat.BigTextStyle().bigText(labels.take(8).joinToString("\n")))
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context))
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(SUBSCRIPTION_ID, n) }
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

    internal fun openAppIntent(context: Context): PendingIntent =
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
