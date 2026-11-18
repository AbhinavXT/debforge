package com.abhinavxt.debforge.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.view.View
import android.widget.RemoteViews
import com.abhinavxt.debforge.AppActions
import com.abhinavxt.debforge.R
import com.abhinavxt.debforge.player.AudioState
import com.abhinavxt.debforge.player.BackgroundPlayback

/**
 * What the player widget shows. [live] is whether the buttons can be sent straight to this process's
 * player; otherwise Play opens the app (see [PlayerWidgetRenderer.render]).
 */
data class PlayerWidgetState(
    val title: String,
    val artist: String?,
    val isPlaying: Boolean,
    val hasPrevious: Boolean,
    val hasNext: Boolean,
    val art: Bitmap?,
    val live: Boolean,
    val empty: Boolean
)

/** Nothing queued -> the "Nothing playing" state; otherwise the current track. */
fun stateOf(audio: AudioState, art: Bitmap?, live: Boolean): PlayerWidgetState {
    val track = audio.track
        ?: return PlayerWidgetState("", null, false, false, false, null, live, empty = true)
    return PlayerWidgetState(track.title, track.artist, audio.isPlaying, audio.hasPrevious, audio.hasNext, art, live, empty = false)
}

/** Previous restarts the track when it cannot go back, so it only needs a player to talk to. */
fun showPrevious(s: PlayerWidgetState) = !s.empty && s.live
fun showNext(s: PlayerWidgetState) = !s.empty && s.live && s.hasNext
fun showPlay(s: PlayerWidgetState) = !s.empty

/** Builds the widget's RemoteViews from a [PlayerWidgetState] and pushes them. */
object PlayerWidgetRenderer {

    fun widgetIds(context: Context): IntArray =
        AppWidgetManager.getInstance(context)
            .getAppWidgetIds(ComponentName(context, PlayerWidget::class.java))

    /** Updates every placed widget. Cheap no-op when none is placed. */
    fun push(context: Context, s: PlayerWidgetState) {
        val ids = widgetIds(context)
        if (ids.isEmpty()) return
        AppWidgetManager.getInstance(context).updateAppWidget(ids, render(context, s))
    }

    fun render(context: Context, s: PlayerWidgetState): RemoteViews {
        val v = RemoteViews(context.packageName, R.layout.widget_player)

        if (s.empty) {
            v.setTextViewText(R.id.widget_title, context.getString(R.string.widget_player_idle))
            v.setTextViewText(R.id.widget_artist, context.getString(R.string.widget_player_idle_hint))
        } else {
            v.setTextViewText(R.id.widget_title, s.title)
            v.setTextViewText(R.id.widget_artist, s.artist.orEmpty())
        }
        v.setViewVisibility(R.id.widget_artist, if (!s.empty && s.artist.isNullOrBlank()) View.GONE else View.VISIBLE)

        if (s.art != null && !s.empty) {
            v.setImageViewBitmap(R.id.widget_art, s.art)
            v.setViewVisibility(R.id.widget_art, View.VISIBLE)
        } else {
            v.setViewVisibility(R.id.widget_art, View.GONE)
        }

        v.setViewVisibility(R.id.widget_play, if (showPlay(s)) View.VISIBLE else View.GONE)
        // Invisible, not gone: Play stays where it is whether or not Previous / Next are there.
        v.setViewVisibility(R.id.widget_prev, if (showPrevious(s)) View.VISIBLE else if (s.empty) View.GONE else View.INVISIBLE)
        v.setViewVisibility(R.id.widget_next, if (showNext(s)) View.VISIBLE else if (s.empty) View.GONE else View.INVISIBLE)

        v.setImageViewResource(R.id.widget_play, if (s.isPlaying) R.drawable.ic_action_pause else R.drawable.ic_action_play)
        v.setContentDescription(
            R.id.widget_play,
            context.getString(if (s.isPlaying) R.string.audio_pause else R.string.audio_play)
        )

        val open = if (s.empty) AppActions.pending(context, null) else openPlayer(context, playRestored = false)
        v.setOnClickPendingIntent(R.id.widget_root, open)
        v.setOnClickPendingIntent(R.id.widget_art_frame, open)
        v.setOnClickPendingIntent(R.id.widget_text, open)
        if (s.live) {
            v.setOnClickPendingIntent(R.id.widget_play, broadcast(context, PlayerWidget.ACTION_PLAY_PAUSE, 21))
            v.setOnClickPendingIntent(R.id.widget_prev, broadcast(context, PlayerWidget.ACTION_PREVIOUS, 22))
            v.setOnClickPendingIntent(R.id.widget_next, broadcast(context, PlayerWidget.ACTION_NEXT, 23))
        } else {
            // No player here, or Android refused to start playback from the background: an activity
            // started by a widget tap is always allowed, and it starts the restored queue itself.
            v.setOnClickPendingIntent(R.id.widget_play, openPlayer(context, playRestored = !s.isPlaying))
        }
        return v
    }

    private fun openPlayer(context: Context, playRestored: Boolean): PendingIntent =
        PendingIntent.getActivity(
            context,
            if (playRestored) 12 else 11,
            AppActions.intent(context)
                .putExtra(BackgroundPlayback.EXTRA_EXPAND_NOW_PLAYING, true)
                .putExtra(PlayerWidget.EXTRA_PLAY_RESTORED, playRestored),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun broadcast(context: Context, action: String, code: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            code,
            Intent(context, PlayerWidget::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
}
