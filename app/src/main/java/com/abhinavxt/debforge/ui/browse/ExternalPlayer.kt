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
    val subtitles: List<com.abhinavxt.debforge.domain.SubtitleLink> = emptyList()
)

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
