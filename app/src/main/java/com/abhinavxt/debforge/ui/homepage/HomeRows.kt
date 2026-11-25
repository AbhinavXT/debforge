package com.abhinavxt.debforge.ui.homepage

import com.abhinavxt.debforge.domain.DownloadItem
import com.abhinavxt.debforge.domain.SortOrder
import com.abhinavxt.debforge.ui.browse.TitleGroup
import com.abhinavxt.debforge.ui.browse.buildTitleGroups
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

/** The Library's own title groups (same keys, so a tap opens the same sheet), newest first, with a video in them. */
fun recentTitles(items: List<DownloadItem>, max: Int = RECENT_MAX): List<TitleGroup> {
    val parsed = items.associate { it.id to ReleaseNameParser.parse(it.filename) }
    return buildTitleGroups(items, parsed, "", SortOrder.DATE_DESC)
        .filter { g -> g.files.any { it.info.isVideo } }
        .take(max)
}

const val RECENT_MAX = 12
