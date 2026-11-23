package com.abhinavxt.debforge.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import androidx.core.app.NotificationCompat
import com.abhinavxt.debforge.R
import com.abhinavxt.debforge.data.prefs.SettingsStore
import com.abhinavxt.debforge.data.repository.AuthRepository
import com.abhinavxt.debforge.domain.DataResult
import com.abhinavxt.debforge.domain.ProviderId
import com.abhinavxt.debforge.domain.ExpiryReminder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Twice a day: tells the user when premium on any signed-in service is about
 * to run out (setting: [ExpiryReminder]). A separate job from the follow
 * check, which is only scheduled while something is followed.
 */
@AndroidEntryPoint
class ExpiryJobService : JobService() {

    @Inject lateinit var auth: AuthRepository
    @Inject lateinit var settings: SettingsStore

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var work: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        work = scope.launch {
            try {
                check()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("ExpiryJob", "Expiry check failed", e)
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    private suspend fun check() {
        val days = settings.expiryReminderDaysFlow.first()
        val nm = getSystemService(NotificationManager::class.java)
        // Not allowed to notify: skip without recording, so granting later still delivers.
        if (days <= 0 || !nm.areNotificationsEnabled()) return
        for (id in auth.signedInProviders.first()) {
            val account = (auth.account(id) as? DataResult.Success)?.data ?: continue
            val until = account.premiumUntil ?: continue
            if (!ExpiryReminder.shouldNotify(until, days, settings.expiryNotified(id), System.currentTimeMillis())) continue
            val left = account.daysLeft?.toInt() ?: continue
            val name = auth.info(id).displayName
            val text = if (left == 0) getString(R.string.expiry_today, name)
            else resources.getQuantityString(R.plurals.expiry_in_days, left, left, name)
            notify(this, id, name, text)
            settings.setExpiryNotified(id, until)
        }
    }

    override fun onStopJob(params: JobParameters): Boolean {
        work?.cancel()
        return true
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val JOB_ID = 4204
        private const val CHANNEL_ID = "debforge_account"
        private const val NOTIF_BASE = 30000

        private fun notify(context: Context, id: ProviderId, name: String, text: String) {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, context.getString(R.string.notif_channel_account), NotificationManager.IMPORTANCE_DEFAULT)
                )
            }
            val n = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_download)
                .setContentTitle(context.getString(R.string.expiry_notif_title, name))
                .setContentText(text)
                .setAutoCancel(true)
                .setContentIntent(DownloadNotifications.openAppIntent(context))
                .build()
            runCatching { nm.notify(NOTIF_BASE + id.ordinal, n) }
        }

        fun schedule(context: Context) {
            val js = context.getSystemService(JobScheduler::class.java) ?: return
            if (js.getPendingJob(JOB_ID) != null) return
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, ExpiryJobService::class.java))
                .setPeriodic(TimeUnit.HOURS.toMillis(12))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .build()
            runCatching { js.schedule(job) }
        }
    }
}
