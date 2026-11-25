package com.abhinavxt.debforge.ui.browse

import com.abhinavxt.debforge.domain.DownloadItem

/**
 * What "Remove from service" takes with it. The service deletes a whole torrent, not single files,
 * so the dialog must say so whenever more goes than the user is looking at.
 * [files] is the torrent's file count when it is known (everything is loaded), else null (generic wording).
 */
data class RemovalScope(val wholeTorrent: Boolean, val files: Int? = null)

/**
 * [requested] is what the user picked, [loaded] everything the Library has fetched (filters, hidden extras
 * and search do not matter), [allLoaded] true once the last page is in. [keyOf] is the service's removal key.
 */
fun removalScope(
    requested: List<DownloadItem>,
    loaded: List<DownloadItem>,
    allLoaded: Boolean,
    keyOf: (DownloadItem) -> String?
): RemovalScope {
    val keys = requested.mapNotNull(keyOf).toSet()
    if (keys.isEmpty()) return RemovalScope(false)
    val loadedUnder = loaded.filter { keyOf(it) in keys }
    val requestedIds = requested.filter { keyOf(it) in keys }.map { it.id }.toSet()
    val hiddenLoaded = loadedUnder.any { it.id !in requestedIds }
    // Not every page is in: a file that is part of a torrent may have siblings that are not loaded.
    val mayHaveUnloaded = !allLoaded && requested.any { it.parentRef != null }
    return when {
        hiddenLoaded && allLoaded -> RemovalScope(true, loadedUnder.size)
        hiddenLoaded || mayHaveUnloaded -> RemovalScope(true)
        else -> RemovalScope(false)
    }
}
