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
    val owned: Boolean?
) {
    val info: ReleaseInfo by lazy { ReleaseNameParser.parse(title) }
}

/** Quality filter on the Search screen; matches [ReleaseInfo.resolution]. */
enum class SearchQuality { ANY, UHD, FULL_HD, HD, SD }

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

    /** [SearchQuality.ANY] when the name doesn't say: then only "Any quality" shows it. */
    fun qualityOf(info: ReleaseInfo): SearchQuality = when (info.resolution) {
        "4320p", "2160p" -> SearchQuality.UHD
        "1440p", "1080p", "1080i" -> SearchQuality.FULL_HD
        "720p" -> SearchQuality.HD
        null -> SearchQuality.ANY
        else -> SearchQuality.SD
    }

    /** A magnet for sources that only give the hash. */
    fun magnetFor(hash: String, name: String): String =
        "magnet:?xt=urn:btih:$hash&dn=" + java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20")
}
