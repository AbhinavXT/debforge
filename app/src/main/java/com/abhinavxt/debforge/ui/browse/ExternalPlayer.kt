package com.abhinavxt.debforge.ui.browse

import com.abhinavxt.debforge.R
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * A stream (or local file) to play. [item] (when known) identifies the file
 * across link refreshes, so DebForge's player can save and restore the
 * position; [showKey] ties an episode to its show.
 */
data class PlayRequest(
    val uri: Uri,
    val title: String,
    val item: com.abhinavxt.debforge.domain.DownloadItem? = null,
    val showKey: String? = null,
    /** Subtitle files from the same torrent, with fresh links. */
    val subtitles: List<com.abhinavxt.debforge.domain.SubtitleLink> = emptyList(),
    /** Set for a YouTube stream: [uri] is then the video-only URL and this holds the rest. */
    val youtube: YtStream? = null
)

/**
 * What the player needs beyond [PlayRequest.uri] to stream a YouTube video: the `yt:` source ref (itags),
 * the audio URL to merge, and the chosen height and file extension (for re-resolving and for "Download instead").
 * Handed over in intent extras like the rest of the request, so it survives process death.
 */
data class YtStream(
    val ref: String,
    val videoId: String,
    val audioUrl: String,
    val height: Int,
    val extension: String = "mp4"
)

/**
 * A request to stream [choice] of [video] (video-only URL + audio URL merged by the player), with its
 * subtitles as WebVTT links. Null for an audio-only choice or when the choice's streams are missing.
 */
fun youTubePlayRequest(
    video: com.abhinavxt.debforge.domain.YouTubeVideo,
    choice: com.abhinavxt.debforge.domain.FormatChoice,
    subtitles: List<com.abhinavxt.debforge.domain.YouTubeSubtitle> = video.subtitles
): PlayRequest? {
    val videoItag = choice.videoItag ?: return null
    val v = video.streams.firstOrNull { it.itag == videoItag && it.kind == com.abhinavxt.debforge.domain.StreamKind.VIDEO } ?: return null
    val a = video.streams.firstOrNull { it.itag == choice.audioItag && it.kind == com.abhinavxt.debforge.domain.StreamKind.AUDIO } ?: return null
    return PlayRequest(
        uri = Uri.parse(v.url),
        title = video.title,
        subtitles = subtitles.map {
            com.abhinavxt.debforge.domain.SubtitleLink(it.url, "text/vtt", it.languageCode, it.label, forced = false)
        },
        youtube = YtStream(
            ref = com.abhinavxt.debforge.domain.YouTubeLinks.sourceRef(video.id, videoItag, choice.audioItag),
            videoId = video.id,
            audioUrl = a.url,
            height = choice.height,
            extension = choice.extension
        )
    )
}

/**
 * Opens [request] in an installed video player (VLC, mpv, MX Player, Just
 * Player…) via a chooser. The "title" extra is understood by VLC and MX
 * Player; others ignore it. Returns false if no player is installed.
 */
fun Context.playExternally(request: PlayRequest): Boolean {
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(request.uri, "video/*")
        .putExtra("title", request.title)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    if (request.subtitles.isNotEmpty()) {
        // MX Player: every subtitle with its name. VLC: one (the first).
        intent.putExtra("subs", request.subtitles.map { Uri.parse(it.url) }.toTypedArray<android.os.Parcelable>())
        intent.putExtra("subs.name", request.subtitles.map { it.label }.toTypedArray())
        intent.putExtra("subtitles_location", request.subtitles.first().url)
    }
    return try {
        startActivity(Intent.createChooser(intent, getString(R.string.chooser_play_with)))
        true
    } catch (e: ActivityNotFoundException) {
        false
    }
}
