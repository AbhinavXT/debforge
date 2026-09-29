package com.abhinavxt.debforge.player

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

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
class BackgroundPlaybackService : MediaSessionService() {

    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = BackgroundPlayback.player
        if (player == null) {
            stopSelf()
            return
        }
        // Tapping the notification returns to the player where it left off.
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, PlayerActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        session = MediaSession.Builder(this, player)
            .setSessionActivity(open)
            .build()
            .also { addSession(it) }
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
        super.onDestroy()
    }
}

/** Hand-over of the activity's player to [BackgroundPlaybackService]. */
object BackgroundPlayback {
    @Volatile var player: Player? = null
}
