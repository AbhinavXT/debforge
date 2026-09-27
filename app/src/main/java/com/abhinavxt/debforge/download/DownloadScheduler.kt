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
import com.abhinavxt.debforge.download.conditions.DownloadConditions
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
    /**
     * Safe to call any time; no-op if nothing is queued or the queue already
     * runs. Returns false only when there IS work but Android wouldn't let us
     * start it right now (it stays queued and starts when the app opens).
     */
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

    suspend fun ensureRunning(): Boolean {
        if (runner.offerWork()) return true
        if (!downloadDao.hasPendingWork()) return true

        // Outside the download schedule: don't start, wake up at window start.
        // Fresh evaluation, not the once-a-minute snapshot: when the window
        // alarm fires at 01:00:05 the snapshot may still say "outside", which
        // would push the alarm to tomorrow; and a rule just changed in
        // Settings may not have reached the snapshot yet.
        val rules = conditions.freshRules()
        val block = conditions.freshBlock()
        if (block != null && !block.waitInPlace) {
            ScheduleAlarm.set(context, conditions.nextWindowStart(rules))
            return true
        }
        // Allowed to run now: a pending window alarm has nothing left to do.
        if (!rules.scheduleEnabled) ScheduleAlarm.cancel(context)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && scheduleJob()) return true
        return startForegroundService()
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private suspend fun scheduleJob(): Boolean {
        val js = context.getSystemService(JobScheduler::class.java) ?: return false
        if (!js.canRunUserInitiatedJobs()) return false
        // Already scheduled or running: scheduling again with the same id
        // would STOP a running job, so leave it alone. (It isn't running here:
        // offerWork() would have returned true.)
        val pending = js.getPendingJob(JOB_ID)
        if (pending != null) {
            if (!pending.requiresUnmetered()) return true
            // Left over from builds that made JobScheduler enforce Wi-Fi-only:
            // it never starts on mobile data, even after the user turns the
            // rule off, and it made every new download wait behind it.
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
            js.schedule(job) == JobScheduler.RESULT_SUCCESS
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

    private companion object {
        const val TAG = "DownloadScheduler"
        const val JOB_ID = 4201
    }
}
