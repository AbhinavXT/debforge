@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.abhinavxt.debforge.player.controls

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.media3.common.TrackGroup

/** How the picture fills the screen. */
enum class Fill { FIT, CROP, STRETCH }

/** A side panel over the video. */
enum class Panel { AUDIO, SUBTITLES, ONLINE_SUBTITLES, SPEED, MORE }

/** Searching OpenSubtitles from the Subtitles panel. */
sealed interface OnlineSubs {
    data object Idle : OnlineSubs
    data object Searching : OnlineSubs
    /** [anyLanguage]: searched every language, not just the preferred one. */
    data class Results(val subs: List<com.abhinavxt.debforge.data.subtitles.OnlineSubtitle>, val anyLanguage: Boolean) : OnlineSubs
    /** Fetching the one picked (the list stays). */
    data class Downloading(val subs: List<com.abhinavxt.debforge.data.subtitles.OnlineSubtitle>, val anyLanguage: Boolean, val fileId: Long) : OnlineSubs
    data class Failed(val message: Int, val anyLanguage: Boolean) : OnlineSubs
}

/** One audio or subtitle track the user can pick. */
data class TrackChoice(val group: TrackGroup, val index: Int, val label: String, val selected: Boolean)

/** What the player is doing, read a few times a second while the controls show. */
data class PlaybackSnapshot(
    val isPlaying: Boolean = false,
    val buffering: Boolean = false,
    val positionMs: Long = 0,
    val bufferedMs: Long = 0,
    /** 0 while unknown. */
    val durationMs: Long = 0,
    val speed: Float = 1f
)

/**
 * State of DebForge's own player controls (phones and tablets; Android TV
 * keeps Media3's controller, which is built for the remote). The activity
 * owns it and changes it from gestures; the controls read it.
 */
class ControlsState {
    var visible by mutableStateOf(true)
    var locked by mutableStateOf(false)
    /** Locked, and the user tapped: show "Slide to unlock" for a moment. */
    var unlockPromptAt by mutableLongStateOf(0L)
    var panel by mutableStateOf<Panel?>(null)
    var title by mutableStateOf("")
    var hasNext by mutableStateOf(false)
    var pipAvailable by mutableStateOf(false)
    /** Right-hand time shows "−12:34" (left) instead of the length. */
    var showRemaining by mutableStateOf(false)
    var fill by mutableStateOf(Fill.FIT)
    var subStyle by mutableStateOf(com.abhinavxt.debforge.player.tracks.SubtitleStyle())
    /** OpenSubtitles is set up (Settings): the Subtitles panel offers "Search online". */
    var onlineSubsAvailable by mutableStateOf(false)
    var onlineSubs by mutableStateOf<OnlineSubs>(OnlineSubs.Idle)
    /** Sleep timer: pause at this elapsedRealtime (0 = off)… */
    var sleepEndsAt by mutableLongStateOf(0L)
    /** …or at the end of this episode (no autoplay of the next). */
    var sleepAtEnd by mutableStateOf(false)
    /** A-B loop points (ms); B set = looping. */
    var loopA by mutableStateOf<Long?>(null)
    var loopB by mutableStateOf<Long?>(null)
    /** Keep playing the sound after leaving the player. */
    var background by mutableStateOf(false)
    /** Dragging the seek bar: don't hide meanwhile. */
    var scrubbing by mutableStateOf(false)
    var snapshot by mutableStateOf(PlaybackSnapshot())
    /** Bumped when the tracks change, so an open panel re-reads them. */
    var tracksVersion by mutableIntStateOf(0)
    /** Last tap on a control: the auto-hide timer restarts from here. */
    var lastInteraction by mutableLongStateOf(0L)

    /** Bounds (window pixels) of the tappable parts, so touches there skip the gestures. */
    internal val controlBounds = HashMap<String, Rect>()

    fun toggle() {
        if (locked) {
            unlockPromptAt = android.os.SystemClock.uptimeMillis()
            return
        }
        visible = !visible
        if (visible) touch()
    }

    fun touch() {
        lastInteraction = android.os.SystemClock.uptimeMillis()
    }

    /**
     * Should a touch at ([x], [y]) go to the controls rather than the
     * gestures? Always while locked or a panel is open (they handle every
     * touch); otherwise only on a button or the seek bar.
     */
    fun isOnControl(x: Float, y: Float): Boolean {
        if (locked || panel != null) return true
        if (!visible) return false
        return controlBounds.values.any { it.contains(androidx.compose.ui.geometry.Offset(x, y)) }
    }
}
