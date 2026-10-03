package com.abhinavxt.debforge.domain

/**
 * Ties Trakt's watched history to files in the Library. Trakt knows titles
 * and episode numbers, the Library knows release names, so both sides are
 * reduced to the same key: [ReleaseInfo.groupKey], plus season and episode
 * for shows. Pure Kotlin: unit-tested in TraktMatchTest.
 */
object TraktMatch {

    /** Key for a file, or null if it isn't a recognisable episode or movie. */
    fun keyOf(info: ReleaseInfo): String? = when (info.kind) {
        MediaKind.SHOW -> if (info.season != null && info.episode != null) {
            episodeKey(info.title, info.season, info.episode)
        } else null
        MediaKind.MOVIE -> movieKey(info.title, info.year)
        MediaKind.OTHER -> null
    }

    fun episodeKey(showTitle: String, season: Int, episode: Int): String =
        "show:" + normalize(stripYear(showTitle)) + "|" + season + "|" + episode

    /** No year (Trakt always has one, a filename may not) matches on the title alone. */
    fun movieKey(title: String, year: Int?): String =
        "movie:" + normalize(title) + ":" + (year ?: "")

    /**
     * Keys a watched movie is reachable by: with its year, and without for
     * files whose name has none.
     */
    fun movieKeys(title: String, year: Int?): List<String> =
        listOfNotNull(movieKey(title, year), if (year != null) movieKey(title, null) else null)

    /**
     * Trakt's show titles carry a disambiguating year in parentheses
     * ("Doctor Who (2005)"); release names put it bare or leave it out.
     * Either way the Library's key has no year for shows.
     */
    fun stripYear(title: String): String =
        title.replace(Regex("""\s*\((?:19|20)\d{2}\)\s*$"""), "").trim()

    /** Like [ReleaseInfo.normalize], but "Grey's" and release names' "Greys" agree. */
    private fun normalize(title: String): String =
        ReleaseInfo.normalize(title.replace(Regex("['’]"), ""))

    /** Trakt's 0–100 progress, two decimals; null if the length isn't known. */
    fun progress(positionMs: Long, durationMs: Long): Double? {
        if (durationMs <= 0) return null
        val pct = positionMs.coerceIn(0, durationMs) * 100.0 / durationMs
        return Math.round(pct * 100) / 100.0
    }
}
