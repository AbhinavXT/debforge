package com.abhinavxt.debforge.player

import android.content.Context
import com.abhinavxt.debforge.data.local.DownloadEntity
import com.abhinavxt.debforge.download.LocalFiles
import com.abhinavxt.debforge.download.artworkFile
import com.abhinavxt.debforge.ui.media.displayTitle

/** The playable track for a finished download, or null when its file is gone or has no readable uri. */
fun audioTrackFor(context: Context, e: DownloadEntity): AudioTrack? {
    val uri = e.finalFilePath?.let { LocalFiles.uriFor(context, it) } ?: return null
    return AudioTrack(e.id, uri, e.displayTitle(), e.artist, artworkFile(context, e.id).takeIf { it.exists() })
}
