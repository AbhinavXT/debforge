package com.abhinavxt.debforge.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HousekeepingTest {

    private val MB = 1024L * 1024
    private val GB = 1024 * MB
    private val DAY = 24L * 60 * 60 * 1000

    @Test fun fitsWithRoomToSpare() = assertNull(Housekeeping.shortfall(needed = 2 * GB, available = 10 * GB))

    @Test fun marginCounts() {
        // 2 GB needed, 2.1 GB free: the file would fit, but not with 200 MB to spare.
        assertEquals(100 * MB, Housekeeping.shortfall(needed = 2 * GB, available = 2 * GB + 100 * MB))
    }

    @Test fun clearlyShort() =
        assertEquals(3 * GB + Housekeeping.MARGIN_BYTES, Housekeeping.shortfall(needed = 4 * GB, available = 1 * GB))

    @Test fun unknownFreeSpaceNeverBlocks() = assertNull(Housekeeping.shortfall(needed = 50 * GB, available = null))

    @Test fun nothingNeeded() = assertNull(Housekeeping.shortfall(needed = 0, available = 0))

    @Test fun offDeletesNothing() {
        val old = Housekeeping.Finished("a", "/x", finishedAt = 0)
        assertEquals(emptyList<Housekeeping.Finished>(), Housekeeping.expired(listOf(old), now = 1000 * DAY, days = 0))
    }

    @Test fun onlyOldEnough() {
        val now = 100 * DAY
        val old = Housekeeping.Finished("old", "/a", finishedAt = now - 8 * DAY)
        val exact = Housekeeping.Finished("exact", "/b", finishedAt = now - 7 * DAY)
        val recent = Housekeeping.Finished("recent", "/c", finishedAt = now - 6 * DAY)
        assertEquals(listOf("old", "exact"), Housekeeping.expired(listOf(old, exact, recent), now, days = 7).map { it.id })
    }
}
