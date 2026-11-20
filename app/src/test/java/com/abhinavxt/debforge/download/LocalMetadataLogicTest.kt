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

    @Test fun unreadAlbumNeedsCheckEvenWithTitle() {
        assertTrue(LocalMetadataLogic.needsCheck("Song", null))
        assertFalse(LocalMetadataLogic.needsCheck("Song", ""))
        assertFalse(LocalMetadataLogic.needsCheck("Song", "Album"))
        assertTrue(LocalMetadataLogic.needsCheck("", "Album"))
        assertFalse(LocalMetadataLogic.needsFullRead("Song"))
        assertTrue(LocalMetadataLogic.needsFullRead(null))
    }

    @Test fun nonAudioWithTitleSkipsOpeningButAudioOrBlankTitleDoesNot() {
        assertTrue(LocalMetadataLogic.skipsFileOpen("Movie.mkv", "Movie"))
        assertFalse(LocalMetadataLogic.skipsFileOpen("Song.mp3", "Song"))
        assertFalse(LocalMetadataLogic.skipsFileOpen("Movie.mkv", null))
    }

    @Test fun albumAndTrackNumberParsing() {
        assertEquals("", LocalMetadataLogic.albumFor(null))
        assertEquals("", LocalMetadataLogic.albumFor("  "))
        assertEquals("Abbey Road", LocalMetadataLogic.albumFor(" Abbey Road "))
        assertEquals(3, LocalMetadataLogic.trackNoFor("3/12"))
        assertEquals(7, LocalMetadataLogic.trackNoFor(" 7 "))
        assertEquals(5, LocalMetadataLogic.trackNoFor("05"))
        assertNull(LocalMetadataLogic.trackNoFor(null))
        assertNull(LocalMetadataLogic.trackNoFor(""))
        assertNull(LocalMetadataLogic.trackNoFor("abc"))
        assertNull(LocalMetadataLogic.trackNoFor("/12"))
        assertNull(LocalMetadataLogic.trackNoFor("0"))
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

    @Test fun openedWithoutTagsStoresFallbackButFailedToOpenStaysBlank() {
        assertEquals("Movie", LocalMetadataLogic.titleToStore(true, null, "Movie"))
        assertEquals("Tag", LocalMetadataLogic.titleToStore(true, "Tag", "Movie"))
        assertNull(LocalMetadataLogic.titleToStore(false, "Tag", "Movie"))
    }

    @Test fun batchSkipsFailedAndEndsWhenNothingLeft() {
        assertEquals(listOf("b"), LocalMetadataLogic.batch(listOf("a", "b"), setOf("a")))
        assertTrue(LocalMetadataLogic.batch(listOf("a"), setOf("a")).isEmpty())
        assertTrue(LocalMetadataLogic.batch(emptyList<String>()).isEmpty())
    }

    @Test fun skippedRowsDoNotCrowdOutTheRest() {
        assertEquals(LocalMetadataLogic.MAX_PER_PASS + 5, LocalMetadataLogic.fetchLimit(5))
        val ids = (1..100).map { "r$it" }
        val skipped = ids.take(5).toSet()
        val fetched = ids.take(LocalMetadataLogic.fetchLimit(skipped.size))
        assertEquals(LocalMetadataLogic.MAX_PER_PASS, LocalMetadataLogic.batch(fetched, skipped).size)
    }
}
