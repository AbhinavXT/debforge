package com.abhinavxt.debforge.ui.media

import com.abhinavxt.debforge.data.youtube.decodeEntities
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
        override val key get() = "y:${PlaylistEntry.format(entry)}"
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
 * in entry order. A non-blank [query] keeps YouTube items whose title, channel or id contains it. Rows are keyed by token, so a video held in both modes shows both; an identical row is dropped.
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
                    title = meta?.title?.takeIf { it.isNotBlank() }?.let { decodeEntities(it) } ?: e.videoId,
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

/** A YouTube download row reduced to what "Download all" needs: [audioOnly] is true for an audio-only download. */
internal data class YouTubeDownloadState(val id: String, val videoId: String, val state: DownloadState, val audioOnly: Boolean)

/** The YouTube downloads among [rows], reduced for "Download all". Subtitle and other rows are ignored. */
internal fun youTubeDownloadStates(rows: List<DownloadEntity>): List<YouTubeDownloadState> =
    rows.mapNotNull { e ->
        YouTubeLinks.parseSourceRef(e.sourceRef)?.let { YouTubeDownloadState(e.id, it.videoId, e.state, it.videoItag == null) }
    }

private fun PlaylistEntry.YouTube.matches(d: YouTubeDownloadState) =
    d.videoId == videoId && d.audioOnly == (mode == YouTubeMode.AUDIO)

/**
 * The YouTube entries "Download all" would act on, in playlist order, each token once: those with no completed or
 * active download of their own mode (audio entry: an audio download, video entry: one with video). A failed one counts
 * as none: it is retried, see [failedDownloadId].
 */
internal fun youTubeToDownload(entries: List<PlaylistEntry>, downloads: List<YouTubeDownloadState>): List<PlaylistEntry.YouTube> =
    entries.filterIsInstance<PlaylistEntry.YouTube>().distinctBy { PlaylistEntry.format(it) }.filter { e ->
        downloads.none { e.matches(it) && it.state != DownloadState.FAILED }
    }

/** The id of [entry]'s failed download, if any: re-queued rather than added again. */
internal fun failedDownloadId(entry: PlaylistEntry.YouTube, downloads: List<YouTubeDownloadState>): String? =
    downloads.firstOrNull { entry.matches(it) && it.state == DownloadState.FAILED }?.id

/** What a "Download all" run says afterwards. */
internal enum class BulkOutcome { QUEUED, ALREADY, FAILED }

/** [queued] items started (new or retried), [already] were already queued or downloaded, the rest failed. */
internal fun bulkOutcome(queued: Int, already: Int): BulkOutcome = when {
    queued > 0 -> BulkOutcome.QUEUED
    already > 0 -> BulkOutcome.ALREADY
    else -> BulkOutcome.FAILED
}

/** The modes a queued batch maps to: audio items download audio only, video items the best video. */
internal fun PlaylistEntry.YouTube.enqueueMode() =
    if (mode == YouTubeMode.AUDIO) com.abhinavxt.debforge.download.youtube.EnqueueMode.AUDIO
    else com.abhinavxt.debforge.download.youtube.EnqueueMode.VIDEO
