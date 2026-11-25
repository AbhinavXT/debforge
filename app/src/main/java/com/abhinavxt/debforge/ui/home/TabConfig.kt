package com.abhinavxt.debforge.ui.home

/**
 * Which tabs the phone's bottom bar shows, in what order, and which tab the app opens on.
 * Tabs are named by their stable key (the nav route), never by position or enum name.
 * Pure Kotlin so the rules are unit-tested; HomeScreen and Settings both go through it.
 */
object TabConfig {
    const val HOME = "home"
    const val LIBRARY = "browse"
    const val SEARCH = "search"
    const val DISCOVER = "youtube"
    const val BROWSER = "browser"
    const val MEDIA = "media"

    /** Every tab that can sit in the bar. Downloads and Settings are opened from the header icons. */
    val ALL = listOf(HOME, LIBRARY, SEARCH, DISCOVER, BROWSER, MEDIA)

    const val MIN = 2
    const val MAX = 5

    /** The stored start-tab value for "follow the signed-in state". */
    const val AUTO = "auto"

    /** What the bar shows until the user customises it. Search only earns its place once a source exists. */
    fun defaultBar(hasSearchSource: Boolean): List<String> =
        if (hasSearchSource) listOf(HOME, LIBRARY, SEARCH, DISCOVER, MEDIA)
        else listOf(HOME, LIBRARY, DISCOVER, BROWSER, MEDIA)

    /**
     * The stored bar, or null when there is none worth keeping: nothing stored, or fewer than [MIN] known
     * tabs left after dropping unknown and duplicate keys. More than [MAX] known tabs keep the first [MAX].
     */
    fun parse(stored: String?): List<String>? =
        stored.orEmpty().split(',').map { it.trim() }.filter { it in ALL }.distinct().take(MAX).takeIf { it.size >= MIN }

    fun encode(bar: List<String>): String = bar.joinToString(",")

    fun bar(stored: String?, hasSearchSource: Boolean): List<String> = parse(stored) ?: defaultBar(hasSearchSource)

    /** The side rail lists every tab: the bar's order first, the hidden ones after. */
    fun rail(bar: List<String>): List<String> = bar + ALL.filterNot { it in bar }

    /** Tabs left out of the bar, for Home's shortcut chips. */
    fun hidden(bar: List<String>): List<String> = ALL.filterNot { it in bar }

    /**
     * The tab the app opens on. A stored tab key wins; otherwise (automatic, unset or unknown) it is the
     * Library when signed in and the Browser when not, which is how the app always opened.
     */
    fun startTab(stored: String?, signedIn: Boolean): String =
        stored?.takeIf { it in ALL } ?: if (signedIn) LIBRARY else BROWSER

    /** Turns [key] on or off in the bar; null when that would break the [MIN] or [MAX] limit. */
    fun toggled(bar: List<String>, key: String): List<String>? = when {
        key in bar -> if (bar.size > MIN) bar - key else null
        else -> if (bar.size < MAX) bar + key else null
    }

    /** Moves [key] one place (-1 up, +1 down); the list itself when it cannot move. */
    fun moved(bar: List<String>, key: String, by: Int): List<String> {
        val from = bar.indexOf(key)
        val to = from + by
        if (from < 0 || to !in bar.indices) return bar
        return bar.toMutableList().also { it.add(to, it.removeAt(from)) }
    }
}

/** What the shell reads before it draws: the stored tab choices plus whether a search source exists. */
data class NavPrefs(val storedBar: String?, val storedStart: String?, val hasSearchSource: Boolean) {
    val bar: List<String> get() = TabConfig.bar(storedBar, hasSearchSource)
}
