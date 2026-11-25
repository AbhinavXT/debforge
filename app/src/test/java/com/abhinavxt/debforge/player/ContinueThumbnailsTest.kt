package com.abhinavxt.debforge.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ContinueThumbnailsTest {
    @Test fun sameSpotSameName() {
        assertEquals(
            ContinueThumbnails.cacheName("a", 10_000, 3_000_000),
            ContinueThumbnails.cacheName("a", 10_500, 3_000_000)
        )
    }

    @Test fun differentItemOrSpotDiffers() {
        assertNotEquals(ContinueThumbnails.cacheName("a", 0, 3_000_000), ContinueThumbnails.cacheName("b", 0, 3_000_000))
        assertNotEquals(ContinueThumbnails.cacheName("a", 0, 3_000_000), ContinueThumbnails.cacheName("a", 1_000_000, 3_000_000))
    }

    @Test fun evictsOldestBeyondMax() {
        val files = listOf("old" to 1L, "new" to 3L, "mid" to 2L)
        assertEquals(listOf("old"), ContinueThumbnails.evictions(files, 2))
        assertEquals(emptyList<String>(), ContinueThumbnails.evictions(files, 5))
    }
}
