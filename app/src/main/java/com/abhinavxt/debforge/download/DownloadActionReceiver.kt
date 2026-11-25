package com.abhinavxt.debforge.download

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.abhinavxt.debforge.domain.DownloadState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What a notification button does; kept apart from the receiver so it is unit-testable. */
enum class NotifStep { PAUSE_ALL, ASK_CANCEL, CANCEL_RUNNING, KEEP, IGNORE }

/**
 * Buttons on the download notifications. Not exported, so only our own
 * PendingIntents reach it, and it works with the app process dead (the
 * notification may outlive it). It only ever stops work or asks; starting
 * downloads is not allowed from the background, so Resume and Retry open the app.
 *
 * Cancel discards progress, so it is two steps: the ongoing notification's
 * Cancel posts a separate "Cancel download?" notification, and only its Yes cancels.
 */
@AndroidEntryPoint
class DownloadActionReceiver : BroadcastReceiver() {

    @Inject lateinit var controller: DownloadController

    override fun onReceive(context: Context, intent: Intent) {
        val step = stepFor(intent.action)
        if (step == NotifStep.IGNORE) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                when (step) {
                    NotifStep.PAUSE_ALL -> {
                        val paused = controller.pauseAllTracked()
                        if (paused.isNotEmpty()) DownloadNotifications.notifyPausedByUser(context, paused)
                    }
                    NotifStep.ASK_CANCEL -> {
                        val running = controller.running()
                        if (running.isNotEmpty()) DownloadNotifications.notifyCancelAsk(context, running)
                    }
                    NotifStep.CANCEL_RUNNING -> {
                        DownloadNotifications.dismissCancelAsk(context)
                        controller.cancelAsked(intent.getStringArrayExtra(EXTRA_IDS)?.toList().orEmpty())
                    }
                    NotifStep.KEEP -> DownloadNotifications.dismissCancelAsk(context)
                    NotifStep.IGNORE -> Unit
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_PAUSE = "com.abhinavxt.debforge.action.NOTIF_PAUSE"
        const val ACTION_CANCEL_ASK = "com.abhinavxt.debforge.action.NOTIF_CANCEL_ASK"
        const val ACTION_CANCEL_YES = "com.abhinavxt.debforge.action.NOTIF_CANCEL_YES"
        const val EXTRA_IDS = "com.abhinavxt.debforge.extra.CANCEL_IDS"
        const val ACTION_KEEP = "com.abhinavxt.debforge.action.NOTIF_KEEP"

        fun stepFor(action: String?): NotifStep = when (action) {
            ACTION_PAUSE -> NotifStep.PAUSE_ALL
            ACTION_CANCEL_ASK -> NotifStep.ASK_CANCEL
            ACTION_CANCEL_YES -> NotifStep.CANCEL_RUNNING
            ACTION_KEEP -> NotifStep.KEEP
            else -> NotifStep.IGNORE
        }
    }
}

/** Of the ids the user was asked about, those still safe to cancel: downloading or queued; finished, paused, failed or deleted ones are not. */
fun idsToCancel(asked: List<String>, states: Map<String, DownloadState?>): List<String> =
    asked.distinct().filter { states[it] == DownloadState.DOWNLOADING || states[it] == DownloadState.QUEUED }

/** Of the ids a Pause put on hold, those still paused: rows resumed, finished or deleted since are left alone, and rows paused earlier were never in the list. */
fun idsToResume(marked: List<String>, states: Map<String, DownloadState?>): List<String> =
    marked.distinct().filter { states[it] == DownloadState.PAUSED }
