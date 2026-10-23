package com.abhinavxt.debforge.domain

/**
 * One torrent a search found. [cached] / [owned] are null when the source
 * can't tell (a source without a cache check).
 */
data class SearchResult(
    /** Info hash, lowercase hex: identity across sources. */
    val hash: String,
    val title: String,
    val magnet: String,
    val size: Long,
    val seeders: Int?,
    /** The source's own age label ("3d", "2 months"), shown as is. */
    val age: String?,
    val tracker: String?,
    /** Ready to stream or download right away on the service. */
    val cached: Boolean?,
    /** Already in the user's account. */
    val owned: Boolean?,
    /** When the tracker listed it (epoch millis); null if the source doesn't say. */
    val published: Long? = null,
    /** How many of the user's preferred words ([ReleasePrefs.prefer]) the name has. */
    val preferred: Int = 0
) {
    val info: ReleaseInfo by lazy { ReleaseNameParser.parse(title) }
}

/**
 * Settings → Search sources → Release preferences: releases whose name has an
 * [avoid] word are never shown or added, and each [prefer] word a name has
 * ranks it higher. A word matches whole name parts, so "TS" doesn't hit
 * "Shorts" and "WEB-DL" matches "WEB.DL".
 */
data class ReleasePrefs(val avoid: List<String> = emptyList(), val prefer: List<String> = emptyList()) {

    fun avoids(title: String): Boolean = tokens(title).let { t -> avoid.any { has(t, it) } }

    fun score(title: String): Int = tokens(title).let { t -> prefer.count { has(t, it) } }

    private fun has(titleTokens: List<String>, word: String): Boolean {
        val w = tokens(word)
        return w.isNotEmpty() && titleTokens.windowed(w.size).any { it == w }
    }

    companion object {
        /** Avoided unless the user changes it: recordings made in a cinema. */
        const val DEFAULT_AVOID = "CAM, HDCAM, TS, HDTS, TELESYNC"

        /** "x265, HEVC ,, REMUX" → the words, as typed in the setting. */
        fun words(text: String): List<String> = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }

        private fun tokens(s: String): List<String> = s.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
    }
}

/** Quality filter on the Search screen; matches [ReleaseInfo.resolution]. */
enum class SearchQuality { ANY, UHD, FULL_HD, HD, SD }

/** What the Search tab looks for: Torznab's plain, movie or TV search. */
enum class SearchKind { ALL, MOVIE, SHOW }

object SearchResults {

    /**
     * Cached first (one tap to watch), then the most preferred words, then
     * the best seeded. Same torrent from two trackers is listed once, keeping
     * the copy with more seeders.
     */
    fun rank(results: List<SearchResult>): List<SearchResult> =
        results
            .sortedByDescending { it.seeders ?: -1 }
            .distinctBy { it.hash.lowercase() }
            .sortedWith(
                compareByDescending<SearchResult> { it.cached == true }
                    .thenByDescending { it.preferred }
                    .thenByDescending { it.seeders ?: -1 }
            )

    fun filter(results: List<SearchResult>, quality: SearchQuality, cachedOnly: Boolean): List<SearchResult> =
        results.filter { r ->
            (!cachedOnly || r.cached == true) && (quality == SearchQuality.ANY || qualityOf(r.info) == quality)
        }

    /**
     * Results for one season (and episode): that episode, or a whole-season
     * pack that holds it. Trackers match loosely, so this is checked here.
     */
    fun forEpisode(results: List<SearchResult>, season: Int?, episode: Int?): List<SearchResult> {
        if (season == null && episode == null) return results
        return results.filter { r ->
            val info = r.info
            val pack = seasonPackOf(r.title)
            when {
                info.kind == MediaKind.SHOW && info.episode != null ->
                    (season == null || info.season == season) && (episode == null || info.episode == episode)
                pack != null -> season == null || pack == season
                else -> false
            }
        }
    }

    private val SEASON_PACK = Regex("""(?i)(?:^|[\s._\-\[(])(?:s(\d{1,2})|season[\s._]?(\d{1,2}))(?![\de])""")

    /** "The.Bear.S02.1080p" / "The Bear Season 2" → 2; null if it isn't a season pack. */
    fun seasonPackOf(title: String): Int? {
        val m = SEASON_PACK.find(title) ?: return null
        return (m.groupValues[1].ifEmpty { m.groupValues[2] }).toIntOrNull()
    }

    /** [SearchQuality.ANY] when the name doesn't say: then only "Any quality" shows it. */
    fun qualityOf(info: ReleaseInfo): SearchQuality = when (info.resolution) {
        "4320p", "2160p" -> SearchQuality.UHD
        "1440p", "1080p", "1080i" -> SearchQuality.FULL_HD
        "720p" -> SearchQuality.HD
        null -> SearchQuality.ANY
        else -> SearchQuality.SD
    }

    /** The resolution a quality stands for, to remember on a follow; null for [SearchQuality.ANY] / SD. */
    fun resolutionOf(quality: SearchQuality): String? = when (quality) {
        SearchQuality.UHD -> "2160p"
        SearchQuality.FULL_HD -> "1080p"
        SearchQuality.HD -> "720p"
        else -> null
    }

    /** A magnet for sources that only give the hash. */
    fun magnetFor(hash: String, name: String): String =
        "magnet:?xt=urn:btih:$hash&dn=" + java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20")
}
