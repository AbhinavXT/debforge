package com.abhinavxt.debforge.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import com.abhinavxt.debforge.player.AudioPlayer
import com.abhinavxt.debforge.player.WidgetAction
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * Home-screen player widget: artwork, title, artist and Previous / Play-Pause / Next. [PlayerWidgetSync]
 * keeps it current while the app process is alive; a button tap cold-starts the process when it is not.
 */
@AndroidEntryPoint
class PlayerWidget : AppWidgetProvider() {

    @Inject lateinit var audio: AudioPlayer
    @Inject lateinit var sync: PlayerWidgetSync

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent) // Hilt injects here; also dispatches onUpdate
        val action = when (intent.action) {
            ACTION_PLAY_PAUSE -> WidgetAction.TOGGLE
            ACTION_NEXT -> WidgetAction.NEXT
            ACTION_PREVIOUS -> WidgetAction.PREVIOUS
            else -> return
        }
        inBackground {
            // Under goAsync's ~10 s: a streamed track may need its link looked up first.
            val started = withTimeoutOrNull(START_TIMEOUT_MS) { audio.playFromWidget(action) }
            if (started != true) sync.markStartBlocked() // refused, nothing to play, or too slow: open the app instead
            sync.refresh()
        }
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        inBackground { sync.refresh() }
    }

    /** Keeps the process alive until [block] finishes (goAsync allows ~10 s). */
    private fun inBackground(block: suspend () -> Unit) {
        val pending = goAsync()
        scope.launch {
            try {
                block()
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_PLAY_PAUSE = "com.abhinavxt.debforge.widget.PLAYER_PLAY_PAUSE"
        const val ACTION_NEXT = "com.abhinavxt.debforge.widget.PLAYER_NEXT"
        const val ACTION_PREVIOUS = "com.abhinavxt.debforge.widget.PLAYER_PREVIOUS"

        /** Boolean extra on the activity intent: restore the saved queue and start playing it. */
        const val EXTRA_PLAY_RESTORED = "com.abhinavxt.debforge.PLAY_RESTORED"

        private const val START_TIMEOUT_MS = 8_000L
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
