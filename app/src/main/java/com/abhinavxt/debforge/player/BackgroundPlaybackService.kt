package com.abhinavxt.debforge.player

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import com.abhinavxt.debforge.R
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.abhinavxt.debforge.data.prefs.SettingsStore
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the player's sound going after the user leaves it (Home, another
 * app, screen off) when "Keep playing in the background" is on.
 *
 * The player itself stays owned by [PlayerActivity], which hands it over in
 * [BackgroundPlayback.player] before starting this service and takes it back
 * (stopping the service) when it returns. The service only adds a media
 * session around it: Media3 then shows the media notification, keeps the
 * process in the foreground while playing, and wires lock-screen, Bluetooth
 * and headset controls.
 */
@OptIn(UnstableApi::class)
@AndroidEntryPoint
class BackgroundPlaybackService : MediaSessionService() {

    @Inject lateinit var settings: SettingsStore
    @Inject lateinit var audio: AudioPlayer

    private var session: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        val player = BackgroundPlayback.player
        if (player == null) {
            stopSelf()
            return
        }
        // Tapping the notification returns to the player where it left off
        // (audio: the app with "now playing" open).
        val open = PendingIntent.getActivity(
            this, 0,
            BackgroundPlayback.openIntent
                ?: Intent(this, PlayerActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        // Only the audio player (it sets openIntent) gets skip buttons; video keeps the plain session.
        val isAudio = BackgroundPlayback.openIntent != null
        val builder = MediaSession.Builder(this, player).setSessionActivity(open)
        if (isAudio) builder.setCallback(SkipCallback())
        session = builder.build().also { addSession(it) }
        if (isAudio) scope.launch {
            settings.audioSkipSecondsFlow.collect { seconds ->
                session?.setMediaButtonPreferences(skipButtons(seconds))
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** Swiped away from recents: stop the sound with it. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        session?.player?.pause()
        stopSelf()
    }

    override fun onDestroy() {
        // The player belongs to the activity: release only the session.
        session?.let {
            removeSession(it)
            it.release()
        }
        session = null
        scope.cancel()
        super.onDestroy()
    }

    /** Lets every controller (notification, lock screen, Bluetooth) use the two skip commands. */
    private inner class SkipCallback : MediaSession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(SkipCommands.BACK).add(SkipCommands.FORWARD).build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .setMediaButtonPreferences(skipButtons(currentSeconds))
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession, controller: MediaSession.ControllerInfo, customCommand: SessionCommand, args: Bundle
        ): ListenableFuture<SessionResult> {
            val sign = when (customCommand.customAction) {
                SkipCommands.BACK.customAction -> -1
                SkipCommands.FORWARD.customAction -> 1
                else -> return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
            }
            audio.seekBy(sign * skipMs(currentSeconds))
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    @Volatile private var currentSeconds = DEFAULT_SKIP_SECONDS

    private fun skipButtons(seconds: Int): ImmutableList<CommandButton> {
        val n = validSkipSeconds(seconds)
        currentSeconds = n
        return ImmutableList.of(
            CommandButton.Builder(skipIcon(n, forward = false))
                .setDisplayName(getString(R.string.audio_back_n, n))
                .setSessionCommand(SkipCommands.BACK)
                .setSlots(CommandButton.SLOT_OVERFLOW)
                .build(),
            CommandButton.Builder(skipIcon(n, forward = true))
                .setDisplayName(getString(R.string.audio_forward_n, n))
                .setSessionCommand(SkipCommands.FORWARD)
                .setSlots(CommandButton.SLOT_OVERFLOW)
                .build()
        )
    }
}

private object SkipCommands {
    val BACK = SessionCommand("com.abhinavxt.debforge.SKIP_BACK", Bundle.EMPTY)
    val FORWARD = SessionCommand("com.abhinavxt.debforge.SKIP_FORWARD", Bundle.EMPTY)
}

@OptIn(UnstableApi::class)
private fun skipIcon(seconds: Int, forward: Boolean): Int = when (seconds) {
    5 -> if (forward) CommandButton.ICON_SKIP_FORWARD_5 else CommandButton.ICON_SKIP_BACK_5
    15 -> if (forward) CommandButton.ICON_SKIP_FORWARD_15 else CommandButton.ICON_SKIP_BACK_15
    30 -> if (forward) CommandButton.ICON_SKIP_FORWARD_30 else CommandButton.ICON_SKIP_BACK_30
    else -> if (forward) CommandButton.ICON_SKIP_FORWARD_10 else CommandButton.ICON_SKIP_BACK_10
}

/** Hand-over of the activity's player to [BackgroundPlaybackService]. */
object BackgroundPlayback {
    @Volatile var player: Player? = null

    /** What the notification opens instead of [PlayerActivity] (audio sets it). */
    @Volatile var openIntent: Intent? = null

    /** Boolean extra on that intent: open the app with "now playing" expanded. */
    const val EXTRA_EXPAND_NOW_PLAYING = "com.abhinavxt.debforge.EXPAND_NOW_PLAYING"
}
