package com.abhinavxt.debforge.ui.media

import com.abhinavxt.debforge.data.local.DownloadEntity
import com.abhinavxt.debforge.data.playback.PlaylistEntry
import com.abhinavxt.debforge.data.playback.YouTubeMeta
import com.abhinavxt.debforge.data.playback.YouTubeMode
import com.abhinavxt.debforge.domain.DownloadState
import com.abhinavxt.debforge.domain.YouTubeLinks
import com.abhinavxt.debforge.player.AudioTrack
import com.abhinavxt.debforge.player.MixedEntry

/** One row of an opened playlist, in playlist order. */
sealed interface PlaylistRow {
    /** Stable, unique per row. */
    val key: String

    data class Download(val entity: DownloadEntity) : PlaylistRow {
        override val key get() = "d:${entity.id}"
    }

    /**
     * A YouTube item. [download] is a completed download of the same video (audio-only preferred), if any, and
     * [videoDownload] one that has video. [title] falls back to the video id when no metadata is stored.
     */
    data class YouTube(
        val entry: PlaylistEntry.YouTube,
        val title: String,
        val channel: String?,
        val thumbnailUrl: String,
        val download: DownloadEntity?,
        val videoDownload: DownloadEntity?,
    ) : PlaylistRow {
        override val key get() = "y:${entry.videoId}"
        val videoId get() = entry.videoId
        val isDownloaded get() = download != null
    }
}

/** Completed downloads of YouTube videos by video id: the one to show as "downloaded" (audio-only wins) and one with video. */
internal class YouTubeLocals(val any: Map<String, DownloadEntity>, val video: Map<String, DownloadEntity>) {
    companion object {
        fun of(completed: List<DownloadEntity>): YouTubeLocals {
            val audio = LinkedHashMap<String, DownloadEntity>()
            val video = LinkedHashMap<String, DownloadEntity>()
            for (row in completed) {
                val ref = YouTubeLinks.parseSourceRef(row.sourceRef) ?: continue
                (if (ref.videoItag == null) audio else video).putIfAbsent(ref.videoId, row)
            }
            return YouTubeLocals(video + audio, video)
        }
    }
}

/**
 * The rows of a playlist: downloads from [downloadsById] (ids not in it are hidden, as before) and YouTube items
 * in entry order. A non-blank [query] keeps YouTube items whose title, channel or id contains it. Duplicate rows are dropped.
 */
internal fun playlistRows(
    entries: List<PlaylistEntry>,
    downloadsById: Map<String, DownloadEntity>,
    ytMeta: Map<String, YouTubeMeta>,
    locals: YouTubeLocals,
    query: String = "",
): List<PlaylistRow> {
    val q = query.trim()
    return entries.mapNotNull { e ->
        when (e) {
            is PlaylistEntry.Download -> downloadsById[e.id]?.let { PlaylistRow.Download(it) }
            is PlaylistEntry.YouTube -> {
                val meta = ytMeta[e.videoId]
                val row = PlaylistRow.YouTube(
                    entry = e,
                    title = meta?.title?.takeIf { it.isNotBlank() } ?: e.videoId,
                    channel = meta?.channel?.takeIf { it.isNotBlank() },
                    thumbnailUrl = PlaylistEntry.thumbnailUrl(e.videoId),
                    download = locals.any[e.videoId],
                    videoDownload = locals.video[e.videoId],
                )
                row.takeIf {
                    q.isEmpty() || it.title.contains(q, true) || it.channel?.contains(q, true) == true || it.videoId.contains(q, true)
                }
            }
        }
    }.distinctBy { it.key }
}

/**
 * The queue for "play all" or a tap on an audio row: every row as audio, in order. [trackFor] gives a download's
 * track (null when its file is gone: that row is skipped). Returns the entries and the index of [startRow] in
 * them; when the start row itself was skipped, the next kept entry.
 */
internal fun mixedQueueFor(
    rows: List<PlaylistRow>,
    startRow: Int,
    trackFor: (DownloadEntity) -> AudioTrack?,
): Pair<List<MixedEntry>, Int> {
    val out = ArrayList<MixedEntry>(rows.size)
    var start = 0
    rows.forEachIndexed { i, row ->
        if (i == startRow) start = out.size
        val entry = when (row) {
            is PlaylistRow.Download -> trackFor(row.entity)?.let { MixedEntry.Local(it) }
            is PlaylistRow.YouTube -> MixedEntry.YouTube(row.videoId, YouTubeMeta(row.title, row.channel, null))
        }
        entry?.let { out.add(it) }
    }
    return out to start.coerceAtMost((out.size - 1).coerceAtLeast(0))
}

/** A YouTube download row reduced to what "Download all" needs. */
internal data class YouTubeDownloadState(val videoId: String, val state: DownloadState)

/**
 * The YouTube entries "Download all" would queue: those with no completed or active download of the video (a failed
 * one counts as none, so it is retried), each once, in playlist order. They keep their own mode.
 */
internal fun youTubeToDownload(entries: List<PlaylistEntry>, downloads: List<YouTubeDownloadState>): List<PlaylistEntry.YouTube> {
    val have = downloads.filter { it.state != DownloadState.FAILED }.mapTo(HashSet()) { it.videoId }
    return entries.filterIsInstance<PlaylistEntry.YouTube>().filter { it.videoId !in have }.distinctBy { it.videoId }
}

/** The modes a queued batch maps to: audio items download audio only, video items the best video. */
internal fun PlaylistEntry.YouTube.enqueueMode() =
    if (mode == YouTubeMode.AUDIO) com.abhinavxt.debforge.download.youtube.EnqueueMode.AUDIO
    else com.abhinavxt.debforge.download.youtube.EnqueueMode.VIDEO
