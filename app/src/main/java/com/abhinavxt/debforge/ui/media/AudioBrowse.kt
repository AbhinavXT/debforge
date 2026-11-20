package com.abhinavxt.debforge.ui.media

import com.abhinavxt.debforge.data.local.DownloadEntity
import com.abhinavxt.debforge.download.SafPaths

/** How Media -> Audio groups its tracks. */
enum class BrowseMode { FOLDERS, ARTISTS, ALBUMS }

/** A group with no real name; the UI shows a string resource instead. */
enum class GroupMarker {
    /** Folder mode: files directly in the download folder ("Download folder"). */
    ROOT,
    /** Artist / album mode: blank or missing tag ("Unknown artist" / "Unknown album"). */
    UNKNOWN
}

/**
 * One row of the group list.
 *  - [key]       stable id, pass it to [tracksOf]. Folders: path below the download folder ("" = top level);
 *                artists: lower-cased trimmed name; albums: lower-cased album + album artist. "" = unknown.
 *  - [name]      display name; blank when [marker] is set.
 *  - [secondary] albums: the album artist (else artist) shown under the name; null otherwise or when unknown.
 */
data class AudioGroup(
    val key: String,
    val name: String,
    val marker: GroupMarker?,
    val secondary: String?,
    val trackCount: Int,
    val tracks: List<DownloadEntity>
)

private const val SEP = '\u0000'

/**
 * The folder holding [path], relative to the download folder: "" for the top level.
 * SAF paths use their path below the picked tree; file paths under [downloadRoot] their parent relative to it;
 * file paths outside it just their parent folder's name.
 */
fun folderKeyOf(path: String?, downloadRoot: String): String {
    if (path.isNullOrBlank()) return ""
    SafPaths.parse(path)?.let { return it.dirs.joinToString("/") }
    val parent = path.substringBeforeLast('/', "").trimEnd('/')
    val root = downloadRoot.trim().trimEnd('/')
    if (root.isNotEmpty() && !SafPaths.isTreeUri(root)) {
        if (parent == root) return ""
        if (parent.startsWith("$root/")) return parent.removePrefix("$root/")
    }
    return parent.substringAfterLast('/')
}

private fun String?.norm(): String = this?.trim().orEmpty()

private fun albumArtistOf(r: DownloadEntity): String = r.albumArtist.norm().ifEmpty { r.artist.norm() }

private fun keyOf(r: DownloadEntity, mode: BrowseMode, root: String): String = when (mode) {
    BrowseMode.FOLDERS -> folderKeyOf(r.finalFilePath, root)
    BrowseMode.ARTISTS -> r.artist.norm().lowercase()
    BrowseMode.ALBUMS -> r.album.norm().let { if (it.isEmpty()) "" else it.lowercase() + SEP + albumArtistOf(r).lowercase() }
}

/** Same matching as the Media search box: title, file name or artist contains the query. */
private fun DownloadEntity.matches(q: String): Boolean =
    q.isEmpty() || displayTitle().contains(q, true) || filename.contains(q, true) || artist?.contains(q, true) == true

private fun trackOrder(mode: BrowseMode, rows: List<DownloadEntity>): List<DownloadEntity> =
    if (mode != BrowseMode.ALBUMS) rows
    else rows.sortedWith(
        compareBy<DownloadEntity> { it.trackNo ?: Int.MAX_VALUE }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.displayTitle() }
    )

private fun build(key: String, mode: BrowseMode, rows: List<DownloadEntity>): AudioGroup {
    val first = rows.first()
    val unknown = key.isEmpty() && mode != BrowseMode.FOLDERS
    val name = when (mode) {
        BrowseMode.FOLDERS -> key.substringAfterLast('/')
        BrowseMode.ARTISTS -> first.artist.norm()
        BrowseMode.ALBUMS -> first.album.norm()
    }
    val marker = when {
        mode == BrowseMode.FOLDERS && key.isEmpty() -> GroupMarker.ROOT
        unknown -> GroupMarker.UNKNOWN
        else -> null
    }
    val secondary = if (mode == BrowseMode.ALBUMS && !unknown) albumArtistOf(first).ifEmpty { null } else null
    return AudioGroup(key, if (marker != null) "" else name, marker, secondary, rows.size, trackOrder(mode, rows))
}

private fun groupName(g: AudioGroup, mode: BrowseMode): String =
    if (mode == BrowseMode.FOLDERS) g.key else g.name

/**
 * The group list for [mode]. A non-blank [query] keeps groups whose name matches (all their tracks) and
 * groups with matching tracks (only those tracks). Folders sort by path with the top level first; artists
 * and albums by name, unknown last.
 */
fun groupsOf(rows: List<DownloadEntity>, mode: BrowseMode, downloadRoot: String, query: String = ""): List<AudioGroup> {
    val q = query.trim()
    val groups = rows.groupBy { keyOf(it, mode, downloadRoot) }.map { (k, v) -> build(k, mode, v) }
    val kept = if (q.isEmpty()) groups else groups.mapNotNull { g ->
        if (g.marker == null && groupName(g, mode).contains(q, true)) g
        else g.tracks.filter { it.matches(q) }.takeIf { it.isNotEmpty() }?.let { g.copy(trackCount = it.size, tracks = it) }
    }
    val order = when (mode) {
        BrowseMode.FOLDERS -> compareBy<AudioGroup> { it.marker == null }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.key }
        else -> compareBy<AudioGroup> { it.marker != null }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.secondary.orEmpty() }
    }
    return kept.sortedWith(order)
}

/** Tracks of the group [groupKey] (a key from [groupsOf]), narrowed by [query]; albums in track order. */
fun tracksOf(rows: List<DownloadEntity>, mode: BrowseMode, groupKey: String, downloadRoot: String, query: String = ""): List<DownloadEntity> {
    val q = query.trim()
    val all = rows.filter { keyOf(it, mode, downloadRoot) == groupKey }
    if (all.isEmpty()) return emptyList()
    // A group listed because its own name matches shows every track, as groupsOf counts them.
    val g = build(groupKey, mode, all)
    val nameMatches = q.isNotEmpty() && g.marker == null && groupName(g, mode).contains(q, true)
    return trackOrder(mode, if (nameMatches) all else all.filter { it.matches(q) })
}
