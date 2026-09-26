package com.abhinavxt.debforge.ui.browse

import com.abhinavxt.debforge.R
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/** A stream (or local file) to hand to the user's video player. */
data class PlayRequest(val uri: Uri, val title: String)

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
    return try {
        startActivity(Intent.createChooser(intent, getString(R.string.chooser_play_with)))
        true
    } catch (e: ActivityNotFoundException) {
        false
    }
}
