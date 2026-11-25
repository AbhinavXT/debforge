package com.abhinavxt.debforge

import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * Things the home-screen widget, the Quick Settings tile and the launcher
 * shortcuts ask the app to do. Each opens [MainActivity], which handles the
 * action in [MainActivity.handleAppAction].
 *
 * "Resume all" goes through the app on purpose: Android only lets DebForge
 * start downloading while it's visible (see DownloadScheduler). Pausing works
 * from anywhere, so the widget and tile do that in place.
 */
object AppActions {
    const val ADD = "com.abhinavxt.debforge.action.ADD"
    const val RESUME_ALL = "com.abhinavxt.debforge.action.RESUME_ALL"
    /** Resume only the downloads listed in [EXTRA_IDS] (the notification's Resume after Pause). */
    const val RESUME_IDS = "com.abhinavxt.debforge.action.RESUME_IDS"
    const val EXTRA_IDS = "com.abhinavxt.debforge.extra.IDS"
    const val CONTINUE_LISTENING = "com.abhinavxt.debforge.action.CONTINUE_LISTENING"
    const val SEARCH = "com.abhinavxt.debforge.action.SEARCH"

    fun intent(context: Context, action: String? = null): Intent =
        Intent(context, MainActivity::class.java)
            .setAction(action ?: Intent.ACTION_MAIN)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    /** Distinct request codes so the system keeps one PendingIntent per action. */
    fun pending(context: Context, action: String?): PendingIntent =
        PendingIntent.getActivity(
            context,
            when (action) { ADD -> 1; RESUME_ALL -> 2; RESUME_IDS -> 3; CONTINUE_LISTENING -> 4; SEARCH -> 5; else -> 0 },
            intent(context, action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
}
