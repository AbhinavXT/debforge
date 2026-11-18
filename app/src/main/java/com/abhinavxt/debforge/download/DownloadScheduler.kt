package com.abhinavxt.debforge.download

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.abhinavxt.debforge.data.local.DownloadDao
import com.abhinavxt.debforge.download.conditions.Block
import com.abhinavxt.debforge.download.conditions.DownloadConditions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Decides which Android component hosts the download queue and starts it.
 *
 *  Android 14+ : a user-initiated data transfer job ([DownloadJobService]).
 *                No 6-hour daily cap. Must be scheduled while the app is
 *                visible — true for every enqueue/resume/retry tap.
 *  Otherwise   : the dataSync foreground service ([DownloadService]). On
 *                Android 11-13 there is no time limit; on 15+ it's only used
 *                if the job couldn't be scheduled, and handles onTimeout.
 *
 * If neither can start (e.g. called from the background), the rows simply
 * stay QUEUED and [ensureRunning] picks them up the next time the app opens.
 */
@Singleton
class DownloadScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val runner: DownloadQueueRunner,
    private val downloadDao: DownloadDao,
    private val conditions: DownloadConditions
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val recoveryPending = AtomicBoolean(false)
    // Serialises ensureRunning: its checks span suspending calls, so two callers
    // (app start + kick, alarm + unblock) could otherwise both schedule, and the
    // second would cancel the job the first just started.
    private val ensureLock = Mutex()
    @Volatile private var lastScheduledAt = 0L

    init {
        // Pending work must not sit idle after a condition clears (network
        // back, Wi-Fi joined, charger plugged, window opened). Only the
        // blocked -> unblocked transition counts: the initial value is not a
        // change, and ensureRunning on a job that is just starting could
        // restart it.
        scope.launch {
            var previous: Block? = conditions.currentBlock()
            conditions.blocked.collect { now ->
                if (shouldRestartOnUnblock(previous, now)) {
                    Log.i(TAG, "Condition cleared (was $previous); checking the queue")
                    runCatching { ensureRunning() }.onFailure { Log.w(TAG, "ensureRunning failed", it) }
                }
                previous = now
            }
        }
    }

    /**
     * After the system stopped the job: give its own retry (with backoff) a
     * moment, then, if no job is pending and nothing runs, start one. Skipped
     * when the user stopped the app. One check in flight at a time, so a
     * system that keeps stopping the job can't make this loop.
     */
    fun recoverAfterStop() {
        if (!recoveryPending.compareAndSet(false, true)) return
        scope.launch {
            try {
                delay(RECOVERY_DELAY_MS)
                if (!shouldRecover(runner.offerWork(), downloadDao.hasPendingWork())) return@launch
                // A pending system retry is left alone by ensureRunning. When
                // nothing can start from the background, say so and let the
                // user resume with a tap.
                Log.i(TAG, "No job running after a stop; rescheduling")
                if (!ensureRunning()) DownloadNotifications.notifyStoppedBySystem(context)
            } catch (e: Exception) {
                Log.w(TAG, "recoverAfterStop failed", e)
            } finally {
                recoveryPending.set(false)
            }
        }
    }

    /**
     * Re-arms the window alarm after Android dropped it (reboot, time or
     * time-zone change). Starting a download from a boot broadcast isn't
     * allowed on recent Android, so if the window is already open this sets
     * the alarm a few seconds out: an exact alarm is allowed to start it.
     */
    suspend fun rearmScheduleAlarm() {
        val rules = conditions.freshRules()
        if (!rules.scheduleEnabled || !downloadDao.hasPendingWork()) return
        val now = System.currentTimeMillis()
        val at = if (DownloadConditions.inWindow(rules, now)) now + 10_000 else conditions.nextWindowStart(rules, now)
        ScheduleAlarm.set(context, at)
    }

    /**
     * Safe to call any time; no-op if nothing is queued or the queue already
     * runs. Returns false only when there IS work but Android wouldn't let us
     * start it right now (it stays queued and starts when the app opens).
     *
     * [foreground] = the app is visible (UIDT scheduling allowed) and the user
     * can see the queue: a job pending for the system's own retry or
     * optimisation is replaced by a fresh one, provided nothing blocks downloads.
     */
    suspend fun ensureRunning(foreground: Boolean = false): Boolean = ensureLock.withLock {
        if (runner.offerWork()) return@withLock true
        if (!downloadDao.hasPendingWork()) {
            DownloadNotifications.cancelStoppedBySystem(context)
            return@withLock true
        }

        // Outside the download schedule: don't start, wake up at window start.
        // Fresh evaluation, not the once-a-minute snapshot: when the window
        // alarm fires at 01:00:05 the snapshot may still say "outside", which
        // would push the alarm to tomorrow; and a rule just changed in
        // Settings may not have reached the snapshot yet.
        val rules = conditions.freshRules()
        val block = conditions.freshBlock()
        if (block != null && !block.waitInPlace) {
            ScheduleAlarm.set(context, conditions.nextWindowStart(rules))
            return@withLock true
        }
        // Allowed to run now: a pending window alarm has nothing left to do.
        if (!rules.scheduleEnabled) ScheduleAlarm.cancel(context)

        // The suspending calls above left a gap in which a host may have started.
        if (runner.offerWork()) return@withLock true

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && scheduleJob(foreground, block)) return@withLock true
        startForegroundService()
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private suspend fun scheduleJob(foreground: Boolean, block: Block?): Boolean {
        val js = context.getSystemService(JobScheduler::class.java) ?: return false
        if (!js.canRunUserInitiatedJobs()) return false
        // Already scheduled or running: scheduling again with the same id
        // would STOP a running job, so only a job that is demonstrably waiting
        // on the system's retry is replaced.
        val pending = js.getPendingJob(JOB_ID)
        if (pending != null) {
            val reason = js.getPendingJobReason(JOB_ID)
            val recent = System.currentTimeMillis() - lastScheduledAt < RECENT_SCHEDULE_MS
            val replace = shouldReplacePending(reason, runner.hasLiveSession(), block, foreground, recent)
            // Left over from builds that made JobScheduler enforce Wi-Fi-only:
            // it never starts on mobile data, even after the user turns the
            // rule off, and it made every new download wait behind it.
            val stale = pending.requiresUnmetered() && reason != JobScheduler.PENDING_JOB_REASON_EXECUTING && !runner.hasLiveSession()
            if (!replace && !stale) return true
            if (replace) Log.i(TAG, "Replacing pending job (reason $reason) so it starts now")
            js.cancel(JOB_ID)
        }

        val remaining = downloadDao.remainingPendingBytes()
        val job = JobInfo.Builder(JOB_ID, ComponentName(context, DownloadJobService::class.java))
            .setUserInitiated(true)
            // Always ANY, never UNMETERED. Wi-Fi-only is enforced by the engine
            // itself (DownloadConditions: it shows "Waiting for Wi-Fi" and
            // resumes the moment the rule or the network changes). A JobScheduler
            // network constraint is frozen at schedule time, so turning the rule
            // off would leave the job stuck until the next Wi-Fi connection.
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setEstimatedNetworkBytes(
                // NETWORK_BYTES_UNKNOWN is an Int constant; the API takes Long.
                if (remaining > 0) remaining else JobInfo.NETWORK_BYTES_UNKNOWN.toLong(),
                0L
            )
            .build()
        return try {
            (js.schedule(job) == JobScheduler.RESULT_SUCCESS).also { if (it) lastScheduledAt = System.currentTimeMillis() }
        } catch (e: RuntimeException) {
            // Not visible / not allowed right now — fall back to the service.
            Log.w(TAG, "User-initiated job not scheduled", e)
            false
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun JobInfo.requiresUnmetered(): Boolean =
        requiredNetwork?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true

    private fun startForegroundService(): Boolean = try {
        context.startForegroundService(Intent(context, DownloadService::class.java))
        true
    } catch (e: IllegalStateException) {
        // ForegroundServiceStartNotAllowedException (app in background).
        // Rows stay QUEUED; MainActivity.onStart calls ensureRunning().
        Log.w(TAG, "Foreground service not allowed right now", e)
        false
    }

    companion object {
        private const val TAG = "DownloadScheduler"
        private const val JOB_ID = 4201
        private const val RECOVERY_DELAY_MS = 30_000L
        private const val RECENT_SCHEDULE_MS = 15_000L

        /** True when a blocker just cleared (was set, now none). */
        fun shouldRestartOnUnblock(previous: Block?, now: Block?): Boolean = previous != null && now == null

        /** After a stop: act only when no host runs and there is work left. */
        fun shouldRecover(hostRunning: Boolean, hasPendingWork: Boolean): Boolean = !hostRunning && hasPendingWork

        /**
         * Whether a pending job should be replaced by a fresh one so it starts
         * now. Only for a visible app, with nothing blocking, no live session,
         * and a pending reason that means "waiting for the system's retry /
         * optimisation". A job we scheduled in the last 15 s is left alone
         * unless it is in backoff (MINIMUM_LATENCY: JobScheduler reports backoff
         * after a stop as an earliest-run time; job 4201 never sets a minimum
         * latency itself, so that reason always means backoff). Constraint,
         * quota, thermal, device-state and executing reasons are left alone,
         * and so is any job when the reason is unknown (below API 34).
         */
        fun shouldReplacePending(reason: Int?, sessionLive: Boolean, block: Block?, foreground: Boolean, recentlyScheduled: Boolean = false): Boolean {
            if (!foreground || sessionLive || block != null || reason == null) return false
            // Backoff after a stop is an earliest-run time the system keeps even
            // though we scheduled the job moments ago (pause, then resume), so the
            // recent-schedule guard does not apply to it.
            if (reason == JobScheduler.PENDING_JOB_REASON_CONSTRAINT_MINIMUM_LATENCY) return true
            if (recentlyScheduled) return false
            return reason == JobScheduler.PENDING_JOB_REASON_UNDEFINED ||
                reason == JobScheduler.PENDING_JOB_REASON_JOB_SCHEDULER_OPTIMIZATION
        }
    }
}
