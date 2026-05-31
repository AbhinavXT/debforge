package com.abhinavxt.debforge.ui.browse

import com.abhinavxt.debforge.domain.DownloadItem

/**
 * Pure series-detection logic. Tries a ladder of patterns from most specific
 * (SxxExx) to least (anime trailing-dash); the first match wins. Items that
 * match nothing are surfaced separately as an "Other" bucket.
 *
 * Heuristic by design — release names are chaotic. We prefer false negatives
 * (item stays in Other) over false positives (wrong show grouped together).
 */
object SeriesGrouper {

    data class Episode(
        val showKey: String,
        val showDisplay: String,
        val season: Int?,
        val episode: Int,
        val item: DownloadItem
    )

    /** Standard "Name S01E03" with ., _, space, or - as separators. */
    private val SXXEXX = Regex(
        """^(?<show>.+?)[\s._-]+[Ss](?<s>\d{1,2})[\s._-]?[Ee](?<e>\d{1,3})\b"""
    )

    /** "Name 1x03" / "Name 1×03". */
    private val N_X_NN = Regex(
        """^(?<show>.+?)[\s._-]+(?<s>\d{1,2})[xX×](?<e>\d{1,3})\b"""
    )

    /** "Name Episode 12" / "Name Ep 12" / "Name Ep. 12". Bare "E" deliberately not accepted. */
    private val EPISODE = Regex(
        """^(?<show>.+?)[\s._-]+[Ee]p(?:isode|\.)?[\s._-]+(?<e>\d{1,3})\b"""
    )

    /** Anime-style "Show Name - 12" possibly followed by quality/release tags. */
    private val ANIME_DASH = Regex(
        """^(?<show>.+?)\s+-\s+(?<e>\d{1,3})(?:[\s._\[(v]|$)"""
    )

    /** Returns an [Episode] if a pattern matches, null otherwise. */
    fun classify(item: DownloadItem): Episode? {
        val name = stripExtension(item.filename)
        for (pattern in arrayOf(SXXEXX, N_X_NN, EPISODE, ANIME_DASH)) {
            val m = pattern.find(name) ?: continue
            val show = m.groups["show"]!!.value
            return Episode(
                showKey = normalizeKey(show),
                showDisplay = humanize(show),
                season = m.groups["s"]?.value?.toIntOrNull(),
                episode = m.groups["e"]!!.value.toInt(),
                item = item
            )
        }
        return null
    }

    private fun stripExtension(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0 && name.length - dot - 1 in 1..4) name.substring(0, dot) else name
    }

    /** Lowercased, separators normalized — used to bucket items into the same show. */
    private fun normalizeKey(raw: String): String =
        raw.lowercase()
            .replace(Regex("[._]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    /** Display-friendly version of the show name. */
    private fun humanize(raw: String): String {
        val spaced = raw.replace(Regex("[._]"), " ").replace(Regex("\\s+"), " ").trim()
        return spaced.split(' ').joinToString(" ") { word ->
            if (word.length <= 2) word
            else word.lowercase().replaceFirstChar(Char::uppercaseChar)
        }
    }
}

/**
 * A rendered section in the browse list — either a show with its episodes, or
 * the loose items bucket. The screen renders [Show] sections first in the
 * chosen sort order, then a single [Loose] section at the end.
 */
sealed interface BrowseSection {
    val items: List<DownloadItem>

    data class Show(
        val showKey: String,
        val showDisplay: String,
        val episodes: List<SeriesGrouper.Episode>
    ) : BrowseSection {
        override val items: List<DownloadItem> get() = episodes.map { it.item }
    }

    data class Loose(override val items: List<DownloadItem>) : BrowseSection
}
