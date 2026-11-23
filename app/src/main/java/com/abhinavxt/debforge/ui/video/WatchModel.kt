package com.abhinavxt.debforge.ui.video

import com.abhinavxt.debforge.domain.FormatChoice
import com.abhinavxt.debforge.domain.YouTubeSearchResult
import com.abhinavxt.debforge.domain.YouTubeSubtitle
import com.abhinavxt.debforge.domain.YouTubeVideo
import com.abhinavxt.debforge.ui.browse.formatSize

/** Where the playing video comes from; decides what the watch page shows. */
enum class VideoKind { YOUTUBE, DOWNLOAD, DEBRID }

/** One episode in the list under the video. [itemId] is the Library file id the host jumps to. */
data class EpisodeRow(val itemId: String, val title: String)

/**
 * What the watch page needs to know about the playing video. Filled by the host from the video session;
 * everything is plain data so the page and its rules can be tested without a player.
 */
data class VideoUiState(
    val kind: VideoKind,
    val title: String,
    /** Debrid service name ([VideoKind.DEBRID] source line). */
    val providerName: String? = null,
    /** File name ([VideoKind.DEBRID] source line). */
    val fileName: String? = null,
    val sizeBytes: Long = 0,
    /** Picture height in pixels (0 = unknown); shown for downloads. */
    val height: Int = 0,
    /** File extension without the dot ("mkv"); shown for downloads. */
    val container: String? = null,
    /** The M17 "Play as audio" hand-off is possible (see playAsAudioVisible). */
    val canPlayAsAudio: Boolean = false,
    /** Next episodes, nearest first (from UpNext); empty for movies and YouTube. */
    val episodes: List<EpisodeRow> = emptyList(),
)

/** YouTube-only: the quality rows shown in the Download sheet. */
data class WatchFormats(
    val choices: List<FormatChoice>,
    val selected: FormatChoice,
    /** Subtitle languages offered with a video download. */
    val subtitles: List<YouTubeSubtitle> = emptyList(),
    /** The subtitle language to save with a video download, if any. */
    val subtitle: YouTubeSubtitle? = null,
)

/** Extra details the watch page needs, kept by [WatchViewModel] (YouTube) or set by the host. */
data class WatchDetails(
    /** The YouTube video (related list, subtitles, channel); null for other kinds. */
    val video: YouTubeVideo? = null,
    val channelName: String = "",
    val channelUrl: String? = null,
    /** Null: no Follow button (no channel page, or the state is unknown). */
    val following: Boolean? = null,
    val followBusy: Boolean = false,
    val formats: WatchFormats? = null,
    /** Debrid: the file can be added to the downloads. */
    val canDownload: Boolean = false,
    val canCast: Boolean = false,
    /** Another app can open the stream or file. */
    val canOpenExternal: Boolean = false,
    /** The video is still being resolved: the page shows grey placeholders. */
    val loading: Boolean = false,
    /** A related video waiting for its quality ("ask every time"): the page shows the quality sheet. */
    val ask: WatchFormats? = null,
) {
    val related: List<YouTubeSearchResult> get() = video?.related.orEmpty()
}

/** What the page can ask of its host. Defaults do nothing, so previews and tests need no wiring. */
class WatchActions(
    val onChannel: (name: String, url: String) -> Unit = { _, _ -> },
    val onToggleFollow: () -> Unit = {},
    /** A time in the description tapped: seek the playing video there. */
    val onSeek: (ms: Long) -> Unit = {},
    /** Related video tapped: play it in place. */
    val onOpenRelated: (YouTubeSearchResult) -> Unit = {},
    /** The "+" on a related row: queue it for audio. */
    val onAddRelatedToQueue: (YouTubeSearchResult) -> Unit = {},
    /** YouTube: add the playing video to the audio queue. */
    val onAddToQueue: () -> Unit = {},
    /** YouTube: download the selected format. Debrid: add the file to the downloads. */
    val onDownload: () -> Unit = {},
    val onSelectFormat: (FormatChoice) -> Unit = {},
    val onSelectMode: (audio: Boolean) -> Unit = {},
    val onSelectSubtitle: (YouTubeSubtitle?) -> Unit = {},
    /** The quality sheet of a related video ("ask every time"): pick a row, switch Video/Audio, play, or dismiss. */
    val onAskSelect: (FormatChoice) -> Unit = {},
    val onAskMode: (audio: Boolean) -> Unit = {},
    val onAskPlay: () -> Unit = {},
    val onAskDismiss: () -> Unit = {},
    val onPlayAsAudio: () -> Unit = {},
    /** The online subtitle search / track picker of the player. */
    val onSubtitles: () -> Unit = {},
    val onShare: () -> Unit = {},
    val onCast: () -> Unit = {},
    val onOpenExternal: () -> Unit = {},
    /** An episode in the list: jump to it. */
    val onPlayEpisode: (EpisodeRow) -> Unit = {},
)

enum class WatchChip { DOWNLOAD, PLAY_AS_AUDIO, ADD_TO_PLAYLIST, ADD_TO_QUEUE, CAST, OPEN_EXTERNAL, SHARE, SUBTITLES }

/** The action chips under the title, in display order, for what [state] is. */
fun chipsFor(state: VideoUiState, details: WatchDetails, canAddToPlaylist: Boolean = false): List<WatchChip> = buildList {
    when (state.kind) {
        VideoKind.YOUTUBE -> {
            if (details.formats != null) add(WatchChip.DOWNLOAD)
            if (state.canPlayAsAudio) add(WatchChip.PLAY_AS_AUDIO)
            if (details.video != null && canAddToPlaylist) add(WatchChip.ADD_TO_PLAYLIST)
            if (details.video != null) add(WatchChip.ADD_TO_QUEUE)
            if (details.video != null) add(WatchChip.SHARE)
            if (details.video?.subtitles?.isNotEmpty() == true) add(WatchChip.SUBTITLES)
        }
        VideoKind.DOWNLOAD -> {
            if (state.canPlayAsAudio) add(WatchChip.PLAY_AS_AUDIO)
            add(WatchChip.SUBTITLES)
            if (details.canOpenExternal) add(WatchChip.OPEN_EXTERNAL)
            add(WatchChip.SHARE)
        }
        VideoKind.DEBRID -> {
            if (details.canDownload) add(WatchChip.DOWNLOAD)
            if (details.canCast) add(WatchChip.CAST)
            if (details.canOpenExternal) add(WatchChip.OPEN_EXTERNAL)
            add(WatchChip.SUBTITLES)
        }
    }
}

/**
 * The line under the title: "Downloaded · 1080p · MKV · 1.4 GB" for a file, "Service · name · size" for a
 * debrid stream, empty for YouTube (the channel link is shown instead). [downloadedLabel] is the translated word.
 */
fun sourceLine(state: VideoUiState, downloadedLabel: String, size: (Long) -> String = ::formatSize): String {
    val parts = when (state.kind) {
        VideoKind.YOUTUBE -> emptyList()
        VideoKind.DOWNLOAD -> listOf(
            downloadedLabel,
            state.height.takeIf { it > 0 }?.let { "${it}p" },
            state.container?.takeIf { it.isNotBlank() }?.uppercase(),
            state.sizeBytes.takeIf { it > 0 }?.let(size),
        )
        VideoKind.DEBRID -> listOf(
            state.providerName,
            state.fileName,
            state.sizeBytes.takeIf { it > 0 }?.let(size),
        )
    }
    return parts.filterNotNull().filter { it.isNotBlank() }.joinToString(" · ")
}

/** The heading over the list: "Up next" for YouTube related videos, next episodes otherwise; null when there is no list. */
enum class WatchListKind { RELATED, EPISODES }

fun listKindFor(state: VideoUiState, details: WatchDetails): WatchListKind? = when (state.kind) {
    VideoKind.YOUTUBE -> if (details.related.isNotEmpty()) WatchListKind.RELATED else null
    else -> if (state.episodes.isNotEmpty()) WatchListKind.EPISODES else null
}

/** 1234 -> "1.2K", 3_400_000 -> "3.4M", 2_100_000_000 -> "2.1B"; below 1000 as is. */
fun compactCount(n: Long): String {
    fun scaled(v: Double, unit: String): String {
        val r = Math.round(v * 10) / 10.0
        return (if (r % 1.0 == 0.0) r.toLong().toString() else r.toString()) + unit
    }
    return when {
        n < 1_000 -> n.toString()
        n < 1_000_000 -> scaled(n / 1_000.0, "K")
        n < 1_000_000_000 -> scaled(n / 1_000_000.0, "M")
        else -> scaled(n / 1_000_000_000.0, "B")
    }
}

/**
 * The line under a YouTube title: "1.2M views · Jan 5, 2024". [viewCount] negative and an [uploadDate] that is not an
 * ISO date (the extractor's relative English text) leave their part out. [views] adds the translated word to the
 * shortened count (it gets the real count too, to pick the plural).
 */
fun watchMeta(viewCount: Long, uploadDate: String?, views: (count: Long, compact: String) -> String, locale: java.util.Locale = java.util.Locale.getDefault()): String {
    val date = uploadDate?.takeIf { it.length >= 10 }?.let {
        runCatching {
            java.time.LocalDate.parse(it.substring(0, 10))
                .format(java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM).withLocale(locale))
        }.getOrNull()
    }
    return listOfNotNull(viewCount.takeIf { it >= 0 }?.let { views(it, compactCount(it)) }, date).joinToString(" · ")
}

/**
 * The quantity that picks the plural of "views" for [count]: the count itself below a thousand; a shortened count
 * ("1.2K", "3M") reads as a large round number, so it takes the plural of 1000 whatever the digits after it.
 */
fun viewsQuantity(count: Long): Int = if (count in 0 until 1_000) count.toInt() else 1_000

/** The description box has more to show than its collapsed height (3 lines). */
fun descriptionOverflows(text: String, collapsedLines: Int = 3, charsPerLine: Int = 40): Boolean =
    text.count { it == '\n' } + 1 > collapsedLines || text.length > collapsedLines * charsPerLine

/** What a tap on a YouTube search result (or a channel or trending item) does. */
enum class ResultTap { PREVIEW, PLAY, ASK }

/**
 * Phones and tablets play straight away on the watch page at the default quality; "ask every time" asks first in the
 * quality sheet. A TV keeps the preview screen (it plays in PlayerActivity), except for a row's own Play icon, which
 * plays at the default quality there as before. [viaPlayIcon] is that icon.
 */
fun resultTap(quality: com.abhinavxt.debforge.domain.DefaultQuality, isTv: Boolean, viaPlayIcon: Boolean = false): ResultTap = when {
    quality == com.abhinavxt.debforge.domain.DefaultQuality.ASK -> if (isTv) ResultTap.PREVIEW else ResultTap.ASK
    isTv && !viaPlayIcon -> ResultTap.PREVIEW
    else -> ResultTap.PLAY
}
