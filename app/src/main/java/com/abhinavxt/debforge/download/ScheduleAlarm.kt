package com.abhinavxt.debforge.download

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Wakes the queue at the start of the user's download window.
 *
 * With the "Alarms & reminders" permission granted this is an exact alarm,
 * which also lets Android start the download service from the background.
 * Without it the alarm is inexact and Android may refuse the background
 * start; the queue then starts the next time the app is opened, and the user
 * gets a notification saying so.
 */
object ScheduleAlarm {
    private const val REQUEST = 4203

    fun canBeExact(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    fun set(context: Context, atMillis: Long) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = pendingIntent(context)
        if (canBeExact(context)) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
        }
    }

    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java)?.cancel(pendingIntent(context))
    }

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, REQUEST, Intent(context, ScheduleAlarmReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )
}

@AndroidEntryPoint
class ScheduleAlarmReceiver : BroadcastReceiver() {

    @Inject lateinit var scheduler: DownloadScheduler

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                if (!scheduler.ensureRunning()) DownloadNotifications.notifyScheduledWaiting(context)
            } finally {
                pending.finish()
            }
        }
    }
}

/**
 * Android forgets alarms on reboot, and a time or time-zone change moves the
 * local hour the window alarm was computed for. Re-arm it in those cases.
 */
@AndroidEntryPoint
class ScheduleRearmReceiver : BroadcastReceiver() {

    @Inject lateinit var scheduler: DownloadScheduler

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED -> Unit
            else -> return
        }
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                scheduler.rearmScheduleAlarm()
            } finally {
                pending.finish()
            }
        }
    }
}
