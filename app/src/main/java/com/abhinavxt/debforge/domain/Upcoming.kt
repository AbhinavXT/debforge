package com.abhinavxt.debforge.domain

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Locale

/** The next episode of a followed show to air, from TMDB. */
data class AiringEpisode(val season: Int, val episode: Int, val airDate: LocalDate) {
    val episodeLabel: String get() = "S%02dE%02d".format(Locale.ROOT, season, episode)

    /** 0 = today, 1 = tomorrow; negative if TMDB hasn't moved on yet. */
    fun daysFrom(today: LocalDate): Long = ChronoUnit.DAYS.between(today, airDate)

    companion object {
        /** From TMDB's `next_episode_to_air`; null when any part is missing or the date is unreadable. */
        fun of(season: Int?, episode: Int?, airDate: String?): AiringEpisode? {
            val date = airDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
            return AiringEpisode(season ?: return null, episode ?: return null, date)
        }
    }
}

/** A followed show with an episode on the way, for the Library's "Coming up" row. */
data class Upcoming(val title: String, val showKey: String, val next: AiringEpisode) {
    companion object {
        /** Soonest first; one per show (the same show followed on two services shows once). */
        fun sort(list: List<Upcoming>): List<Upcoming> = list.distinctBy { it.showKey }.sortedBy { it.next.airDate }
    }
}
