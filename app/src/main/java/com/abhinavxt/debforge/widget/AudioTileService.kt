package com.abhinavxt.debforge.widget

import android.annotation.SuppressLint
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.abhinavxt.debforge.AppActions
import com.abhinavxt.debforge.R
import com.abhinavxt.debforge.player.AudioPlayer
import com.abhinavxt.debforge.player.WidgetAction
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * Quick Settings tile "DebForge audio": a tap plays or pauses the current audio queue, or the saved one when the
 * process is new, the way the player widget's Play button does ([AudioPlayer.playFromWidget]). On (active) while
 * playing. When playback cannot start from here (nothing saved, or Android refuses), a tap opens the app instead.
 */
@AndroidEntryPoint
class AudioTileService : TileService() {

    @Inject lateinit var audio: AudioPlayer

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var listening: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        listening?.cancel()
        listening = scope.launch {
            audio.state.map { it.isPlaying }.distinctUntilChanged().collect { render(it) }
        }
    }

    override fun onStopListening() {
        listening?.cancel()
        listening = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        scope.launch {
            val started = withTimeoutOrNull(START_TIMEOUT_MS) { audio.playFromWidget(WidgetAction.TOGGLE) }
            if (started != true) openApp() // refused, nothing to play, or too slow
            render(audio.state.value.isPlaying)
        }
    }

    // Below Android 14 the Intent overload is the only one there is.
    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openApp() {
        val go = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startActivityAndCollapse(AppActions.pending(this, AppActions.CONTINUE_LISTENING))
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(AppActions.intent(this, AppActions.CONTINUE_LISTENING))
            }
        }
        if (isLocked) unlockAndRun { go() } else go()
    }

    private fun render(playing: Boolean) {
        val tile = qsTile ?: return
        tile.label = getString(R.string.tile_audio_label)
        tile.state = if (playing) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.subtitle = getString(if (playing) R.string.tile_audio_playing else R.string.tile_audio_paused)
        tile.updateTile()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val START_TIMEOUT_MS = 8_000L
    }
}
