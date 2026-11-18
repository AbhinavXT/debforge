package com.abhinavxt.debforge.ui.history

import androidx.annotation.StringRes
import com.abhinavxt.debforge.R
import com.abhinavxt.debforge.data.history.HistoryEntity
import com.abhinavxt.debforge.data.history.HistoryKind
import com.abhinavxt.debforge.data.history.HistoryOrigin
import com.abhinavxt.debforge.data.history.shouldRecord
import com.abhinavxt.debforge.domain.ProviderId
import com.abhinavxt.debforge.player.AudioTrack

/**
 * The history row for [track] at [positionMs], or null when it is not worth recording (under 5 s in, unless it
 * [ended]). A finished track is stored at its full length so it shows as watched through.
 */
fun audioHistoryEntry(track: AudioTrack, positionMs: Long, durationMs: Long, ended: Boolean, now: Long): HistoryEntity? {
    val dur = durationMs.coerceAtLeast(0)
    val pos = if (ended && dur > 0) dur else positionMs.coerceAtLeast(0)
    if (!shouldRecord(pos, ended)) return null
    val yt = track.youtube
    return HistoryEntity(
        itemId = track.id,
        kind = HistoryKind.AUDIO,
        origin = if (yt != null) HistoryOrigin.YOUTUBE else HistoryOrigin.LOCAL,
        provider = null,
        sourceRef = yt?.videoId ?: track.id,
        parentRef = null,
        showKey = null,
        title = track.title,
        subtitle = track.artist?.takeIf { it.isNotBlank() },
        artworkUrl = if (yt != null) track.artworkUrl else null,
        positionMs = pos,
        durationMs = dur,
        playedAt = now
    )
}

/** How tapping a history row replays it. */
enum class ReplayRoute {
    /** Open the YouTube preview with fresh links. */
    YOUTUBE,
    /** A downloaded audio file. */
    LOCAL_AUDIO,
    /** A video: the downloaded copy if there is one, else a fresh link from [HistoryEntity.provider]. */
    VIDEO,
    /** Nothing to replay it from (a row without a known service). */
    UNPLAYABLE
}

fun replayRoute(e: HistoryEntity): ReplayRoute = when {
    e.origin == HistoryOrigin.YOUTUBE -> ReplayRoute.YOUTUBE
    e.kind == HistoryKind.AUDIO -> ReplayRoute.LOCAL_AUDIO
    e.kind == HistoryKind.VIDEO -> ReplayRoute.VIDEO
    else -> ReplayRoute.UNPLAYABLE
}

/** The service a cloud video streams from, or null when unknown (local-only rows need none). */
fun replayProvider(e: HistoryEntity): ProviderId? = ProviderId.fromName(e.provider)

/** The YouTube video id of a row: its [HistoryEntity.sourceRef], else what follows the `ytstream:` / `ytaudio:` prefix of its key. */
fun youtubeVideoId(e: HistoryEntity): String? =
    e.sourceRef.takeIf { it.isNotBlank() }
        ?: e.itemId.substringAfter(':', "").takeIf { it.isNotBlank() }

fun youtubeWatchUrl(videoId: String) = "https://www.youtube.com/watch?v=$videoId"

/** Artwork is only fetched from the web (http/https); anything else (file:, content:, data:) is ignored. */
fun isSafeArtworkUrl(url: String?): Boolean =
    url != null && (url.startsWith("https://", ignoreCase = true) || url.startsWith("http://", ignoreCase = true))

/** 0..1 for the progress bar, null when the length is unknown. */
fun historyProgress(positionMs: Long, durationMs: Long): Float? =
    if (durationMs <= 0) null else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)

@StringRes
fun originLabelRes(origin: String): Int = when (origin) {
    HistoryOrigin.LOCAL -> R.string.history_source_downloaded
    HistoryOrigin.YOUTUBE -> R.string.history_source_youtube
    else -> R.string.history_source_cloud
}
