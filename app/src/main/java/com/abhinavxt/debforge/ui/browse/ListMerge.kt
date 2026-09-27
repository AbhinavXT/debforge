package com.abhinavxt.debforge.ui.browse

/**
 * Merges a freshly fetched first page into a list that may already hold
 * several pages, keyed by [key].
 *
 * Old first-page items missing from [fresh] were either deleted on the service
 * or pushed onto page 2 by newer items. Newer items push from the bottom, so
 * as many old items as there are new ones, counted from the bottom of the old
 * first page, are kept (they sit just below the fresh page); the others were
 * deleted and are dropped. Items from later pages are kept, minus anything that
 * now appears in [fresh].
 */
fun <T> mergeFirstPage(current: List<T>, firstPageCount: Int, fresh: List<T>, key: (T) -> Any): List<T> {
    val oldFirst = current.take(firstPageCount)
    val freshKeys = fresh.mapTo(HashSet(), key)
    // Genuinely new = not anywhere in the list yet (an item moving up from a
    // later page doesn't push anything down).
    val allKeys = current.mapTo(HashSet(), key)
    val newCount = fresh.count { key(it) !in allKeys }
    val pushedDown = oldFirst.takeLast(newCount).filter { key(it) !in freshKeys }
    val keep = HashSet(freshKeys)
    val rest = (pushedDown + current.drop(firstPageCount)).filter { keep.add(key(it)) }
    return fresh + rest
}
