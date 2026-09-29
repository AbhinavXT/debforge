package com.abhinavxt.debforge.data.provider.torbox

import com.abhinavxt.debforge.domain.FollowMatcher

/**
 * How DebForge reads a TorBox item's state. Pure: unit-tested in
 * TorBoxStatusTest.
 */
object TorBoxStatus {

    /** States where the files can be fetched from TorBox now. */
    private val READY_STATES = listOf("cached", "completed", "uploading", "seeding")

    /** Gone or broken: never "ready", whatever the flags say. */
    private val DEAD_STATES = listOf("expired", "missing", "failed", "incomplete")

    /**
     * Files can be listed and downloaded. Any one sign is enough:
     * download_present, download_finished, or a ready download_state.
     *
     * (It used to be `present ?: finished ?: false`, which ignores
     * `finished` whenever `present` is false rather than missing: such items
     * were hidden from the Library and shown as "processing" forever.)
     */
    fun isReady(present: Boolean?, finished: Boolean?, state: String?): Boolean {
        val s = state?.trim()?.lowercase().orEmpty()
        if (DEAD_STATES.any { s.startsWith(it) }) return false
        return present == true || finished == true || READY_STATES.any { s.startsWith(it) }
    }

    /**
     * Ready items that came back from the list with no files, newest first,
     * at most [max]. TorBox's list can lag behind for a just-added item: it
     * says "ready" but its file list is still empty. With nothing to show in
     * the Library and not being "processing" either, such an item used to be
     * invisible. These few are looked up one by one (that lookup is fresh).
     */
    fun <T> needingFiles(
        items: List<T>,
        max: Int,
        ready: (T) -> Boolean,
        fileCount: (T) -> Int,
        createdAt: (T) -> String?,
        id: (T) -> Long
    ): List<T> = newestFirst(items.filter { ready(it) && fileCount(it) == 0 }, createdAt, id).take(max)

    /**
     * Newest first, whatever order TorBox returned: by created_at, then by
     * id (higher = newer) when the date is missing or unreadable.
     */
    fun <T> newestFirst(items: List<T>, createdAt: (T) -> String?, id: (T) -> Long): List<T> =
        items.sortedWith(
            compareByDescending<T> { FollowMatcher.addedMillis(createdAt(it)) ?: Long.MIN_VALUE }
                .thenByDescending { id(it) }
        )
}
