package com.abhinavxt.debforge.player

import android.content.Context
import android.net.Uri
import com.abhinavxt.debforge.data.local.DownloadEntity
import com.abhinavxt.debforge.domain.FileKind
import com.abhinavxt.debforge.domain.kindOf
import com.abhinavxt.debforge.download.LocalFiles
import com.abhinavxt.debforge.download.artworkFile
import com.abhinavxt.debforge.ui.media.displayTitle

/** The playable track for a finished download, or null when its file is gone or has no readable uri. */
fun audioTrackFor(context: Context, e: DownloadEntity): AudioTrack? {
    val uri = e.finalFilePath?.let { LocalFiles.uriFor(context, it) } ?: return null
    return trackOf(context, e, uri)
}

/**
 * [audioTrackFor] for a whole list, in order, skipping files that are gone. Each picked folder is listed once,
 * so a queue of hundreds of files resolves quickly. Blocking: call off the main thread.
 */
fun audioTracksFor(context: Context, entities: List<DownloadEntity>): List<AudioTrack> {
    val uris = LocalFiles.urisFor(context, entities.mapNotNull { it.finalFilePath })
    return entities.mapNotNull { e -> e.finalFilePath?.let(uris::get)?.let { trackOf(context, e, it) } }
}

private fun trackOf(context: Context, e: DownloadEntity, uri: Uri) =
    AudioTrack(e.id, uri, e.displayTitle(), e.artist, artworkFile(context, e.id).takeIf { it.exists() },
        isVideoFile = kindOf(e.filename) == FileKind.Video)

/** Where the tapped item ([tappedId]) is in [tracks] after unplayable files were skipped; the first one if it is gone. */
internal fun startIndexOf(tracks: List<AudioTrack>, tappedId: String?): Int =
    tracks.indexOfFirst { it.id == tappedId }.coerceAtLeast(0)
