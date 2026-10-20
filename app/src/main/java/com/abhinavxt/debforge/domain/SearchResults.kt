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
    val published: Long? = null
) {
    val info: ReleaseInfo by lazy { ReleaseNameParser.parse(title) }
}

/** Quality filter on the Search screen; matches [ReleaseInfo.resolution]. */
enum class SearchQuality { ANY, UHD, FULL_HD, HD, SD }

/** What the Search tab looks for: Torznab's plain, movie or TV search. */
enum class SearchKind { ALL, MOVIE, SHOW }

object SearchResults {

    /**
     * Cached first (one tap to watch), then the best seeded. Same torrent
     * from two trackers is listed once, keeping the copy with more seeders.
     */
    fun rank(results: List<SearchResult>): List<SearchResult> =
        results
            .sortedByDescending { it.seeders ?: -1 }
            .distinctBy { it.hash.lowercase() }
            .sortedWith(compareByDescending<SearchResult> { it.cached == true }.thenByDescending { it.seeders ?: -1 })

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
