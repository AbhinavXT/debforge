package com.abhinavxt.debforge.domain

/**
 * Storage rules, as pure functions (unit-tested in HousekeepingTest):
 * "is there room for this?" and "which finished downloads are old enough to
 * delete?".
 */
object Housekeeping {

    /**
     * Kept free on top of what a download needs: the system, other apps and
     * the file system's own bookkeeping need room too, and a phone at 0 bytes
     * free misbehaves.
     */
    const val MARGIN_BYTES = 200L * 1024 * 1024

    private const val DAY_MS = 24L * 60 * 60 * 1000

    /** Choices offered in Settings; 0 = never delete. */
    val AUTO_DELETE_CHOICES = listOf(0, 7, 14, 30, 60)

    /**
     * Bytes missing to fit [needed] (plus [MARGIN_BYTES]) into [available].
     * Null when it fits, when nothing is needed, or when free space is
     * unknown (cloud-backed folders): unknown never blocks a download.
     */
    fun shortfall(needed: Long, available: Long?): Long? {
        if (available == null || needed <= 0) return null
        val missing = needed + MARGIN_BYTES - available
        return missing.takeIf { it > 0 }
    }

    /** A download that finished; [finishedAt] is epoch millis. */
    data class Finished(val id: String, val path: String?, val finishedAt: Long)

    /** Finished downloads at least [days] old at [now]. Empty when [days] <= 0 (off). */
    fun expired(files: List<Finished>, now: Long, days: Int): List<Finished> {
        if (days <= 0) return emptyList()
        val cutoff = now - days * DAY_MS
        return files.filter { it.finishedAt <= cutoff }
    }
}
