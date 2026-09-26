package com.abhinavxt.debforge.download

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground-service host for [DownloadQueueRunner]. Used on Android 11-13
 * (no user-initiated jobs there) and as a fallback on 14+ when the job can't
 * be scheduled (see [DownloadScheduler]).
 *
 * Android 15+ gives dataSync foreground services ~6 hours per 24h while the
 * app is in the background. When that runs out the system calls [onTimeout]
 * and we MUST stop within seconds or the app is crashed. We yield: the active
 * file keeps its progress and goes back to QUEUED, the user gets a
 * notification, and the queue resumes when the app is next opened.
 */
@AndroidEntryPoint
class DownloadService : Service() {

    @Inject lateinit var runner: DownloadQueueRunner
    @Inject lateinit var progressTracker: DownloadProgressTracker
    @Inject lateinit var scheduler: DownloadScheduler

    private val hostScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var session: DownloadQueueRunner.Session? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        DownloadNotifications.ensureChannel(this)
        DownloadNotifications.observe(this, hostScope, runner, progressTracker)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must promote to foreground promptly after startForegroundService().
        // minSdk 30, so the typed overload (API 29) is always available.
        startForeground(
            DownloadNotifications.ONGOING_ID,
            DownloadNotifications.ongoing(this, null, runner.active.value?.filename),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
        if (session == null) {
            session = runner.start { cause ->
                hostScope.launch { onQueueIdle(cause) }
            }
            // Another host (the job) already runs the queue — nothing to do.
            if (session == null) shutdown()
        }
        return START_STICKY
    }

    private suspend fun onQueueIdle(cause: DownloadQueueRunner.StopCause) {
        session = null
        shutdown()
        // Close the tiny window where an enqueue saw "running" just as the
        // loop decided to exit.
        // Deferred -> ensureRunning sets the schedule wake-up alarm.
        if (cause != DownloadQueueRunner.StopCause.HOST_STOPPED) scheduler.ensureRunning()
    }

    /** Android 15+: daily dataSync budget exhausted while in the background. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        session?.let(runner::stop)
        session = null
        DownloadNotifications.notifyTimeLimitReached(this)
        shutdown()
    }

    private fun shutdown() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        // If the system destroys us without onTimeout (rare), still hand the
        // active file back to the queue instead of leaving it DOWNLOADING.
        session?.let(runner::stop)
        session = null
        hostScope.cancel()
        super.onDestroy()
    }
}
