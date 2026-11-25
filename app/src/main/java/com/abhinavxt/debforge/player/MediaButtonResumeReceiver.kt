package com.abhinavxt.debforge.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.KeyEvent
import androidx.core.content.IntentCompat
import com.abhinavxt.debforge.player.session.VideoSession
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * A headset, Bluetooth or lock-screen Play pressed when no session of ours is alive: resumes the saved audio queue the
 * way the player widget does. Media3 finds this receiver by querying the package (exactly one may handle MEDIA_BUTTON,
 * or building a session throws) and registers it as what the system calls once the session is gone; a live session
 * gets its keys directly. Exported, so nothing but the key itself is read from the intent.
 */
@AndroidEntryPoint
class MediaButtonResumeReceiver : BroadcastReceiver() {

    @Inject lateinit var audio: AudioPlayer
    @Inject lateinit var video: VideoSession

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MEDIA_BUTTON) return
        if (BackgroundPlayback.service != null) return // its live session handles keys; never act twice
        // Unparcelling another app's extras may throw.
        val key = runCatching { IntentCompat.getParcelableExtra(intent, Intent.EXTRA_KEY_EVENT, KeyEvent::class.java) }.getOrNull() ?: return
        // A video has a player exactly while it has a session of its own, or the service's.
        val action = mediaButtonAction(key.keyCode, key.action, key.repeatCount, audio.state.value.isPlaying, video.player != null) ?: return
        val pending = goAsync()
        scope.launch {
            try {
                // Under goAsync's ~10 s: a streamed track may need its link looked up first.
                val started = withTimeoutOrNull(START_TIMEOUT_MS) { audio.playFromMediaKey(action) }
                if (started != true) audio.abandonFailedStart()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("MediaButtonResume", "Could not resume", e)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val START_TIMEOUT_MS = 8_000L
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }
}

/** What a media key sent to [MediaButtonResumeReceiver] does; null: nothing. Only a first press of a play key counts. */
fun mediaButtonAction(key: Int, action: Int, repeat: Int, playing: Boolean, videoActive: Boolean): WidgetAction? {
    if (action != KeyEvent.ACTION_DOWN || repeat != 0 || videoActive) return null
    return when (key) {
        KeyEvent.KEYCODE_MEDIA_PLAY -> if (playing) null else WidgetAction.TOGGLE
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> WidgetAction.TOGGLE
        else -> null
    }
}
