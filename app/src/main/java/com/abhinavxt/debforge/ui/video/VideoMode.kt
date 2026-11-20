package com.abhinavxt.debforge.ui.video

import com.abhinavxt.debforge.domain.YouTubeVideo

/** Where the in-app video is shown: the watch page, the whole screen, or the small bar above the tabs. */
enum class VideoMode { WATCH, FULL, MINI }

/** What can happen to the shown video. */
sealed interface VideoModeEvent {
    /** A video opens ([startFull]: straight to full screen); replaces the one shown. */
    data class Open(val startFull: Boolean = false) : VideoModeEvent
    /** Chevron on the watch page. */
    data object Minimise : VideoModeEvent
    /** Tap on the mini bar, or the notification. */
    data object Expand : VideoModeEvent
    data object EnterFull : VideoModeEvent
    data object ExitFull : VideoModeEvent
    /** The system Back key: full screen -> watch page -> mini bar; in the mini bar it is not ours. */
    data object Back : VideoModeEvent
    /** The device turned ([landscape]); only phones follow it, tablets never change on rotation. */
    data class Rotated(val landscape: Boolean) : VideoModeEvent
    data object Close : VideoModeEvent
}

/** Pure: the mode after [event]; null is no video. A mode with nothing to do stays as it is. */
/** [backClosesFull]: the video was opened straight in full screen by the setting, so Back from it ends the video. */
fun nextMode(mode: VideoMode?, event: VideoModeEvent, isTablet: Boolean, backClosesFull: Boolean = false): VideoMode? = when (event) {
    is VideoModeEvent.Open -> if (event.startFull) VideoMode.FULL else VideoMode.WATCH
    VideoModeEvent.Close -> null
    else -> when (mode) {
        null -> null
        else -> when (event) {
            VideoModeEvent.Minimise -> if (mode == VideoMode.MINI) mode else VideoMode.MINI
            VideoModeEvent.Expand -> if (mode == VideoMode.MINI) VideoMode.WATCH else mode
            VideoModeEvent.EnterFull -> if (mode == VideoMode.WATCH) VideoMode.FULL else mode
            VideoModeEvent.ExitFull -> if (mode == VideoMode.FULL) VideoMode.WATCH else mode
            VideoModeEvent.Back -> when (mode) {
                VideoMode.FULL -> if (backClosesFull) null else VideoMode.WATCH
                VideoMode.WATCH -> VideoMode.MINI
                VideoMode.MINI -> VideoMode.MINI
            }
            is VideoModeEvent.Rotated -> when {
                isTablet -> mode
                mode == VideoMode.WATCH && event.landscape -> VideoMode.FULL
                mode == VideoMode.FULL && !event.landscape -> VideoMode.WATCH
                else -> mode
            }
            else -> mode
        }
    }
}

/** Does the system Back key belong to the video (full screen and the watch page) rather than to the app underneath? */
fun backBelongsToVideo(mode: VideoMode?): Boolean = mode == VideoMode.FULL || mode == VideoMode.WATCH

/** The video now shown; [video] is what the watch page knows about a YouTube stream (null: none known). */
data class ActiveVideo(
    val token: Long,
    val request: com.abhinavxt.debforge.ui.browse.PlayRequest,
    val video: YouTubeVideo? = null,
)

/** Where a video comes from: decides what the watch page shows. */
fun kindOf(isYouTube: Boolean, uriScheme: String?): VideoKind = when {
    isYouTube -> VideoKind.YOUTUBE
    uriScheme == "content" || uriScheme == "file" -> VideoKind.DOWNLOAD
    else -> VideoKind.DEBRID
}
