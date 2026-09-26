package com.abhinavxt.debforge.download

import android.app.job.JobParameters
import android.app.job.JobService
import android.os.Build
import androidx.annotation.RequiresApi
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * User-initiated data transfer (UIDT) job host for [DownloadQueueRunner] on
 * Android 14+. This is Google's recommended mechanism for large downloads the
 * user explicitly started: unlike a dataSync foreground service it has no
 * fixed 6-hour daily cap on Android 15.
 *
 * Lifecycle:
 *  - onStartJob: attach the progress notification (required for UIDT) and
 *    start the queue. Return true = work continues asynchronously.
 *  - Queue drains: jobFinished(reschedule = false).
 *  - onStopJob (network lost, system pressure, user stopped it in Task
 *    Manager): yield the active file back to QUEUED with progress saved.
 *    Reschedule unless the USER stopped it.
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
@AndroidEntryPoint
class DownloadJobService : JobService() {

    @Inject lateinit var runner: DownloadQueueRunner
    @Inject lateinit var progressTracker: DownloadProgressTracker
    @Inject lateinit var scheduler: DownloadScheduler

    private val hostScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var session: DownloadQueueRunner.Session? = null
    private var observer: Job? = null
    private var stopped = false

    override fun onStartJob(params: JobParameters): Boolean {
        stopped = false
        DownloadNotifications.ensureChannel(this)
        setNotification(
            params,
            DownloadNotifications.ONGOING_ID,
            DownloadNotifications.ongoing(this, null, runner.active.value?.filename),
            JOB_END_NOTIFICATION_POLICY_REMOVE
        )
        val s = runner.start { cause ->
            hostScope.launch { onQueueIdle(params, cause) }
        } ?: return false // another host already runs the queue

        session = s
        observer = DownloadNotifications.observe(this, hostScope, runner, progressTracker)
        return true
    }

    private suspend fun onQueueIdle(params: JobParameters, cause: DownloadQueueRunner.StopCause) {
        session = null
        observer?.cancel()
        if (stopped) return // onStopJob already told the system what to do
        jobFinished(params, false)
        if (cause == DownloadQueueRunner.StopCause.QUEUE_EMPTY) scheduler.ensureRunning()
    }

    override fun onStopJob(params: JobParameters): Boolean {
        stopped = true
        session?.let(runner::stop)
        session = null
        observer?.cancel()
        // Let the system retry (e.g. when the network returns) unless the user
        // deliberately stopped DebForge from the Task Manager.
        return params.stopReason != JobParameters.STOP_REASON_USER
    }

    override fun onDestroy() {
        hostScope.cancel()
        super.onDestroy()
    }
}
