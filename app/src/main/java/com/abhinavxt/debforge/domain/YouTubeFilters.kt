package com.abhinavxt.debforge.domain

import java.time.Instant
import java.time.ZoneId

enum class YtType { ALL, VIDEOS, CHANNELS, PLAYLISTS }
enum class YtDuration { ANY, SHORT, MEDIUM, LONG }
enum class YtDate { ANY, TODAY, WEEK, MONTH, YEAR }

/** What a search result is; channels and playlists open their own page instead of playing. */
enum class ResultKind { VIDEO, CHANNEL, PLAYLIST }

/**
 * The filters over a YouTube text search. NewPipeExtractor 0.27 only takes the type as a search parameter; duration and
 * upload date are applied to each page of results here ([keeps]).
 */
data class YouTubeFilters(val type: YtType = YtType.VIDEOS, val duration: YtDuration = YtDuration.ANY, val date: YtDate = YtDate.ANY) {
    val active get() = this != YouTubeFilters()
    /** Duration and date only mean something for videos. */
    val videoLike get() = type == YtType.VIDEOS || type == YtType.ALL
    val clientSide get() = videoLike && (duration != YtDuration.ANY || date != YtDate.ANY)

    /** The extractor's content filter ("all", "videos", "channels", "playlists"). */
    val contentFilter get() = when (type) {
        YtType.ALL -> "all"
        YtType.VIDEOS -> "videos"
        YtType.CHANNELS -> "channels"
        YtType.PLAYLISTS -> "playlists"
    }

    /** Whether [r] passes; [now] is the current time. Videos of unknown length or date do not pass a filter on it. */
    fun keeps(r: YouTubeSearchResult, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): Boolean {
        if (!clientSide) return true
        if (r.kind != ResultKind.VIDEO) return false
        val secs = r.durationSec
        val okDuration = when (duration) {
            YtDuration.ANY -> true
            YtDuration.SHORT -> secs in 1 until 240
            YtDuration.MEDIUM -> secs in 240..1200
            YtDuration.LONG -> secs > 1200
        }
        if (!okDuration) return false
        if (date == YtDate.ANY) return true
        val up = r.uploadedAt ?: return false
        val today = now.atZone(zone).toLocalDate()
        val day = up.atZone(zone).toLocalDate()
        return when (date) {
            YtDate.ANY -> true
            YtDate.TODAY -> day >= today
            YtDate.WEEK -> day > today.minusWeeks(1)
            YtDate.MONTH -> day > today.minusMonths(1)
            YtDate.YEAR -> day > today.minusYears(1)
        }
    }
}
