package com.abhinavxt.debforge.domain

import com.abhinavxt.debforge.data.subtitles.OnlineSubtitle

/** Settings: what to do about OpenSubtitles when a file has no subtitles in the user's language. Names are persisted. */
enum class OnlineSubsWhenNone { OFF, ASK, EXACT }

/** A text track of the playing file, reduced to what [SubtitleAutoPick.needsSubtitles] needs. */
data class TextTrackInfo(val language: String?, val forced: Boolean = false)

/** What to do with the OpenSubtitles results. */
sealed interface AutoPickResult {
    data class Add(val subtitle: OnlineSubtitle) : AutoPickResult
    data class Offer(val subtitle: OnlineSubtitle) : AutoPickResult
    data object Nothing : AutoPickResult
}

/** Decides what to add or offer from OpenSubtitles results. Pure; unit-tested in SubtitleAutoPickTest. */
object SubtitleAutoPick {

    enum class Context { PLAYER_START, DOWNLOAD_COMPLETE }

    /** True when no full text track (forced-only ones do not count) is in any of [languages] (codes like "en", "pt-br"; compared by base language). */
    fun needsSubtitles(textTracks: List<TextTrackInfo>, languages: List<String>): Boolean {
        val wanted = languages.map(::base).filter { it.isNotEmpty() }.toSet()
        if (wanted.isEmpty()) return false
        return textTracks.none { !it.forced && base(it.language) in wanted }
    }

    fun pick(
        results: List<OnlineSubtitle>,
        languages: List<String>,
        mode: OnlineSubsWhenNone,
        context: Context
    ): AutoPickResult {
        val inLanguage = results.filter { rank(it, languages) >= 0 }
        return when (context) {
            Context.PLAYER_START -> {
                if (mode == OnlineSubsWhenNone.OFF) return AutoPickResult.Nothing
                val best = best(inLanguage, languages) ?: return AutoPickResult.Nothing
                if (mode == OnlineSubsWhenNone.EXACT && best.hashMatch) AutoPickResult.Add(best)
                else AutoPickResult.Offer(best)
            }
            Context.DOWNLOAD_COMPLETE -> {
                val hash = inLanguage.filter { it.hashMatch }
                val pool = hash.ifEmpty { inLanguage }
                val best = best(pool, languages) ?: return AutoPickResult.Nothing
                AutoPickResult.Add(best)
            }
        }
    }

    /**
     * The best language first (preferred order); within it, results that are neither hearing-impaired nor forced when
     * there are any, then hash match, then downloads. Stable.
     */
    private fun best(results: List<OnlineSubtitle>, languages: List<String>): OnlineSubtitle? {
        val top = results.minOfOrNull { rank(it, languages) } ?: return null
        val inTop = results.filter { rank(it, languages) == top }
        val clean = inTop.filter { !it.hearingImpaired }.ifEmpty { inTop }
        val unforced = clean.filter { !isForced(it) }.ifEmpty { clean }
        return unforced.sortedWith(
            compareByDescending<OnlineSubtitle> { it.hashMatch }.thenByDescending { it.downloads }
        ).firstOrNull()
    }

    private fun isForced(s: OnlineSubtitle) = s.release.contains("forced", ignoreCase = true)

    /** Index of the preferred language the result is in (exact code first, then base language); -1 if none. */
    private fun rank(s: OnlineSubtitle, languages: List<String>): Int {
        val lang = s.language?.lowercase().orEmpty()
        if (lang.isEmpty()) return -1
        val exact = languages.indexOfFirst { it.lowercase() == lang }
        if (exact >= 0) return exact
        return languages.indexOfFirst { base(it) == base(lang) }
    }

    private fun base(code: String?): String = code.orEmpty().lowercase().substringBefore('-').substringBefore('_')
}
