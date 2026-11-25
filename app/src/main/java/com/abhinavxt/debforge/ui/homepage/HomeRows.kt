package com.abhinavxt.debforge.ui.homepage

import com.abhinavxt.debforge.domain.DownloadItem
import com.abhinavxt.debforge.domain.ReleaseInfo
import com.abhinavxt.debforge.domain.ReleaseNameParser

/** The rows of the Home tab, in display order. [key] is persisted (as hidden rows): never rename. */
enum class HomeRow(val key: String) {
    CONTINUE_WATCHING("continue_watching"),
    CONTINUE_LISTENING("continue_listening"),
    RECENT("recent"),
    DOWNLOADS("downloads"),
    NEW_EPISODES("new_episodes"),
    SHORTCUTS("shortcuts");

    companion object {
        /** Rows the user turned off; unknown keys (from a newer version's backup) are ignored. */
        fun parseHidden(stored: String?): Set<HomeRow> =
            stored.orEmpty().split(',').mapNotNull { k -> entries.firstOrNull { it.key == k.trim() } }.toSet()

        fun encodeHidden(hidden: Set<HomeRow>): String =
            entries.filter { it in hidden }.joinToString(",") { it.key }
    }
}

/**
 * The content rows to draw: not hidden and with something in them. Shortcuts are not content, so a screen
 * of only shortcut chips still gets the empty state.
 */
fun visibleHomeRows(hidden: Set<HomeRow>, counts: Map<HomeRow, Int>): List<HomeRow> =
    HomeRow.entries.filter { it != HomeRow.SHORTCUTS && it !in hidden && (counts[it] ?: 0) > 0 }

/** One entry per show or movie among [items] (newest first, as the service lists them), videos only. */
fun recentTitles(items: List<DownloadItem>, max: Int = RECENT_MAX): List<ReleaseInfo> =
    items.asSequence()
        .map { ReleaseNameParser.parse(it.filename) }
        .filter { it.isVideo }
        .distinctBy { it.groupKey }
        .take(max)
        .toList()

const val RECENT_MAX = 12
