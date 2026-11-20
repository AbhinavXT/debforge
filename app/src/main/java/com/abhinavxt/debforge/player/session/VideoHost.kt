package com.abhinavxt.debforge.player.session

import android.net.Uri
import com.abhinavxt.debforge.domain.ProviderId
import com.abhinavxt.debforge.domain.SubtitleLink
import com.abhinavxt.debforge.ui.browse.PlayRequest
import com.abhinavxt.debforge.ui.browse.YtStream

/** The file being played; null for a plain link (positions are saved only when known). */
data class VideoIdentity(
    val itemId: String,
    val provider: ProviderId,
    val sourceRef: String,
    val filename: String,
    val showKey: String?,
    val parentRef: String? = null
)

/** What [VideoSession.open] plays: the link, its name, which file it is, its subtitles, and the YouTube parts. */
data class VideoTarget(
    val uri: Uri,
    val title: String,
    val identity: VideoIdentity?,
    val subtitles: List<SubtitleLink> = emptyList(),
    val youtube: YtStream? = null
) {
    companion object {
        fun from(request: PlayRequest) = VideoTarget(
            uri = request.uri,
            title = request.title,
            identity = request.item?.let {
                VideoIdentity(it.id, it.provider, it.sourceRef, it.filename, request.showKey, it.parentRef)
            },
            subtitles = request.subtitles,
            youtube = request.youtube
        )
    }
}

/**
 * What the engine asks of the screen showing the video (PlayerActivity): the overlays over the video,
 * dialogs, picture-in-picture and orientation, and closing. Every call comes on the main thread, only
 * while the host is attached ([VideoSession.attach]).
 */
interface VideoHost {
    /** In the floating window: no buttons, cards or intro skips. */
    val inPictureInPicture: Boolean
    /** Leaving for good: no new dialogs. */
    val isClosing: Boolean

    /** Closes the player screen. */
    fun close()

    fun titleChanged(title: String)

    /** "Start over" after resuming mid-way. */
    fun showStartOver(show: Boolean)

    /** "Skip intro" and the like; [onSkip] when tapped (the button hides first). */
    fun showSkip(text: String, onSkip: () -> Unit)
    fun hideSkip()

    /** "Next: [text]" card appears (with Play now / Cancel). */
    fun showNextCard(text: String)
    /** The card's countdown line; null hides it. */
    fun setNextCountdown(text: String?)
    fun hideNextCard()

    /** Stats for nerds: shown or hidden, and the latest text. */
    fun showStats(on: Boolean)
    fun statsText(text: String)

    /** Video size or playing state changed: refresh the picture-in-picture settings. */
    fun updatePip()
    /** The video's size is known: follow its shape (portrait / landscape). */
    fun videoShape(portrait: Boolean)

    /** The connection broke and the link could not be refreshed: Retry or Close. */
    fun showNetworkError()
    fun dismissNetworkError()
    /** A YouTube stream that cannot be kept going: download instead, or close. */
    fun showStreamFailed(youtube: YtStream)
    /** A stream that cannot continue and that downloading would not fix: close. */
    fun showYouTubeProblem(message: String)
    /** "Can't play this here": another player, keep watching, or (with [offerSoftware]) software decoding. */
    fun offerExternal(message: String, offerSoftware: Boolean)
}
