package com.abhinavxt.debforge.ui.browse

import com.abhinavxt.debforge.domain.DownloadItem
import com.abhinavxt.debforge.domain.MediaKind
import com.abhinavxt.debforge.domain.ReleaseInfo

/** Chips under the search field; combined with AND, except Movies and Shows which exclude each other. */
enum class QuickFilter { MOVIES, SHOWS, UHD, UNWATCHED }

/** Switches [filter] on or off; turning Movies on turns Shows off and the other way round. */
fun Set<QuickFilter>.toggled(filter: QuickFilter): Set<QuickFilter> = when {
    filter in this -> this - filter
    filter == QuickFilter.MOVIES -> this - QuickFilter.SHOWS + filter
    filter == QuickFilter.SHOWS -> this - QuickFilter.MOVIES + filter
    else -> this + filter
}

/**
 * Keeps the items every filter accepts. Works per file, so a show filtered to Unwatched keeps
 * only its unwatched episodes. [watched] says whether one file has been watched.
 */
fun applyQuickFilters(
    items: List<DownloadItem>,
    parsed: Map<String, ReleaseInfo>,
    filters: Set<QuickFilter>,
    watched: (DownloadItem, ReleaseInfo) -> Boolean
): List<DownloadItem> {
    if (filters.isEmpty()) return items
    return items.filter { item ->
        val info = parsed[item.id] ?: return@filter false
        filters.all { f ->
            when (f) {
                QuickFilter.MOVIES -> info.kind == MediaKind.MOVIE
                QuickFilter.SHOWS -> info.kind == MediaKind.SHOW
                QuickFilter.UHD -> info.resolution.equals("2160p", ignoreCase = true) || info.resolution.equals("4k", ignoreCase = true)
                QuickFilter.UNWATCHED -> !watched(item, info)
            }
        }
    }
}
