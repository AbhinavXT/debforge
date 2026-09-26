package com.abhinavxt.debforge.ui.browse

import com.abhinavxt.debforge.domain.DownloadItem
import com.abhinavxt.debforge.domain.MediaKind
import com.abhinavxt.debforge.domain.ReleaseInfo
import com.abhinavxt.debforge.domain.SortOrder

/** A file plus what we parsed out of its name. */
data class ParsedItem(val item: DownloadItem, val info: ReleaseInfo)

/**
 * One card in the poster view: every file of a show, or every copy of a
 * movie, or a single unrecognised file.
 */
data class TitleGroup(
    val key: String,
    /** Representative parse (first file) — used for the TMDB lookup. */
    val info: ReleaseInfo,
    val files: List<ParsedItem>
) {
    val kind: MediaKind get() = info.kind
    val title: String get() = info.title
    val totalSize: Long get() = files.sumOf { it.item.filesize }

    /** Newest addedAt among the files (ISO-8601 sorts lexically). */
    val latestAdded: String get() = files.maxOfOrNull { it.item.addedAt.orEmpty() }.orEmpty()

    /** Files in watch order: season, episode, then quality/size. */
    val sortedFiles: List<ParsedItem>
        get() = files.sortedWith(
            compareBy<ParsedItem>(
                { it.info.season ?: Int.MAX_VALUE },
                { it.info.episode ?: Int.MAX_VALUE }
            ).thenByDescending { it.item.filesize }
        )
}

/** Filter -> group by title -> sort groups. Pure; call from `remember`. */
fun buildTitleGroups(
    items: List<DownloadItem>,
    parsed: Map<String, ReleaseInfo>,
    query: String,
    sort: SortOrder
): List<TitleGroup> {
    val q = query.trim().lowercase()
    val parsedItems = items.mapNotNull { item ->
        val info = parsed[item.id] ?: return@mapNotNull null
        if (q.isNotEmpty() &&
            !item.filename.lowercase().contains(q) &&
            !info.title.lowercase().contains(q) &&
            !item.host.lowercase().contains(q)
        ) return@mapNotNull null
        ParsedItem(item, info)
    }
    val groups = parsedItems
        .groupBy { if (it.info.kind == MediaKind.OTHER) "file:" + it.item.id else it.info.groupKey }
        .map { (key, files) -> TitleGroup(key, files.first().info, files) }

    val comparator: Comparator<TitleGroup> = when (sort) {
        SortOrder.DATE_DESC -> compareByDescending { it.latestAdded }
        SortOrder.DATE_ASC -> compareBy { it.latestAdded }
        SortOrder.NAME_ASC -> compareBy { it.title.lowercase() }
        SortOrder.NAME_DESC -> compareByDescending { it.title.lowercase() }
        SortOrder.SIZE_DESC -> compareByDescending { it.totalSize }
        SortOrder.SIZE_ASC -> compareBy { it.totalSize }
    }
    return groups.sortedWith(comparator)
}

fun formatSize(bytes: Long): String = when {
    bytes <= 0 -> "—"
    bytes < 1024 -> "$bytes B"
    bytes < 1024L * 1024 -> "${bytes / 1024} KB"
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    else -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
}
