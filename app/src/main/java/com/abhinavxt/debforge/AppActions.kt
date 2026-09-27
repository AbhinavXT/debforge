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

    fun intent(context: Context, action: String? = null): Intent =
        Intent(context, MainActivity::class.java)
            .setAction(action ?: Intent.ACTION_MAIN)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    /** Distinct request codes so the system keeps one PendingIntent per action. */
    fun pending(context: Context, action: String?): PendingIntent =
        PendingIntent.getActivity(
            context,
            when (action) { ADD -> 1; RESUME_ALL -> 2; else -> 0 },
            intent(context, action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
}
