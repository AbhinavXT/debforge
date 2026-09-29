package com.abhinavxt.debforge.data.provider.torbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TorBoxStatusTest {

    @Test fun presentIsReady() = assertTrue(TorBoxStatus.isReady(true, false, "cached"))

    // The bug: present=false but finished=true used to count as not ready.
    @Test fun finishedButNotPresentIsReady() = assertTrue(TorBoxStatus.isReady(false, true, "completed"))

    @Test fun readyStateAloneIsEnough() {
        assertTrue(TorBoxStatus.isReady(null, null, "cached"))
        assertTrue(TorBoxStatus.isReady(false, false, "Uploading (No Peers)"))
    }

    @Test fun stillDownloadingIsNotReady() {
        assertFalse(TorBoxStatus.isReady(false, false, "downloading"))
        assertFalse(TorBoxStatus.isReady(false, false, "stalled (no seeds)"))
        assertFalse(TorBoxStatus.isReady(null, null, null))
    }

    @Test fun deadStatesNeverReady() {
        assertFalse(TorBoxStatus.isReady(true, true, "expired"))
        assertFalse(TorBoxStatus.isReady(false, true, "Missing"))
        assertFalse(TorBoxStatus.isReady(true, true, "failed (processing)"))
    }

    private data class T(val id: Long, val created: String?)

    @Test fun newestFirstWhateverTheInputOrder() {
        val oldestFirst = listOf(
            T(1, "2026-01-01T00:00:00Z"),
            T(2, "2026-05-01T00:00:00+00:00"),
            T(3, "2026-09-28T10:00:00Z")
        )
        assertEquals(listOf(3L, 2L, 1L), TorBoxStatus.newestFirst(oldestFirst, { it.created }, { it.id }).map { it.id })
    }

    @Test fun missingDatesFallBackToId() {
        val items = listOf(T(5, null), T(9, null), T(7, "garbage"))
        assertEquals(listOf(9L, 7L, 5L), TorBoxStatus.newestFirst(items, { it.created }, { it.id }).map { it.id })
    }

    private data class I(val id: Long, val ready: Boolean, val files: Int, val created: String? = null)

    @Test fun readyWithoutFilesNeedsLookupNewestFirst() {
        val items = listOf(
            I(1, ready = true, files = 0, created = "2026-09-01T00:00:00Z"),
            I(2, ready = true, files = 3),
            I(3, ready = false, files = 0),
            I(4, ready = true, files = 0, created = "2026-09-28T18:00:00Z")
        )
        val picked = TorBoxStatus.needingFiles(items, 10, { it.ready }, { it.files }, { it.created }, { it.id })
        assertEquals(listOf(4L, 1L), picked.map { it.id })
    }

    @Test fun lookupsCapped() {
        val items = (1L..30L).map { I(it, ready = true, files = 0) }
        val picked = TorBoxStatus.needingFiles(items, 10, { it.ready }, { it.files }, { it.created }, { it.id })
        assertEquals((30L downTo 21L).toList(), picked.map { it.id })
    }
}
