package com.abhinavxt.debforge.download

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.abhinavxt.debforge.data.local.DownloadDao
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
    private val downloadDao: DownloadDao
) {
    /** Safe to call any time; no-op if nothing is queued or the queue already runs. */
    suspend fun ensureRunning() {
        if (runner.offerWork()) return
        if (!downloadDao.hasPendingWork()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && scheduleJob()) return
        startForegroundService()
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private suspend fun scheduleJob(): Boolean {
        val js = context.getSystemService(JobScheduler::class.java) ?: return false
        if (!js.canRunUserInitiatedJobs()) return false
        // Already scheduled or running: scheduling again with the same id
        // would STOP a running job, so leave it alone.
        if (js.getPendingJob(JOB_ID) != null) return true

        val remaining = downloadDao.remainingPendingBytes()
        val job = JobInfo.Builder(JOB_ID, ComponentName(context, DownloadJobService::class.java))
            .setUserInitiated(true)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setEstimatedNetworkBytes(
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

    private fun startForegroundService() {
        try {
            context.startForegroundService(Intent(context, DownloadService::class.java))
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException (app in background).
            // Rows stay QUEUED; MainActivity.onStart calls ensureRunning().
            Log.w(TAG, "Foreground service not allowed right now", e)
        }
    }

    private companion object {
        const val TAG = "DownloadScheduler"
        const val JOB_ID = 4201
    }
}
