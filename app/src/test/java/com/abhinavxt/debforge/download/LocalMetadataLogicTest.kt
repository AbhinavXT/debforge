package com.abhinavxt.debforge.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalMetadataLogicTest {
    @Test fun blankOrNullTitleNeedsCheck() {
        assertTrue(LocalMetadataLogic.needsCheck(null))
        assertTrue(LocalMetadataLogic.needsCheck(""))
        assertTrue(LocalMetadataLogic.needsCheck("  "))
        assertFalse(LocalMetadataLogic.needsCheck("Song"))
    }

    @Test fun storedTitleIsNeverBlankSoAFileIsCheckedOnce() {
        assertFalse(LocalMetadataLogic.needsCheck(LocalMetadataLogic.titleFor(null, "Movie")))
        assertFalse(LocalMetadataLogic.needsCheck(LocalMetadataLogic.titleFor("  ", "")))
    }

    @Test fun tagWinsOverFallback() {
        assertEquals("Tag", LocalMetadataLogic.titleFor(" Tag ", "Movie"))
        assertEquals("Movie", LocalMetadataLogic.titleFor("", "Movie"))
    }

    @Test fun artistBlankIsNull() {
        assertNull(LocalMetadataLogic.artistFor(" "))
        assertEquals("A", LocalMetadataLogic.artistFor(" A"))
    }

    @Test fun batchIsCapped() {
        val big = (1..500).toList()
        assertEquals(LocalMetadataLogic.MAX_PER_PASS, LocalMetadataLogic.batch(big).size)
        assertEquals(3, LocalMetadataLogic.batch(listOf(1, 2, 3)).size)
    }

    @Test fun sampleSizeKeepsPictureAtLeastArtSize() {
        assertEquals(1, LocalMetadataLogic.sampleSize(500, 400))
        assertEquals(2, LocalMetadataLogic.sampleSize(1024, 1024))
        assertEquals(4, LocalMetadataLogic.sampleSize(2400, 2400))
    }
}
