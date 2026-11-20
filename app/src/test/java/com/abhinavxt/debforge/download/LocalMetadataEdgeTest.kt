package com.abhinavxt.debforge.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalMetadataEdgeTest {
    private val L = LocalMetadataLogic

    @Test fun trackNoCases() {
        assertNull(L.trackNoFor(null))
        assertNull(L.trackNoFor(""))
        assertNull(L.trackNoFor("   "))
        assertNull(L.trackNoFor("0"))
        assertNull(L.trackNoFor("-1"))
        assertEquals(7, L.trackNoFor(" 7 "))
        assertEquals(7, L.trackNoFor("7/"))
        assertNull(L.trackNoFor("abc"))
        assertEquals(12, L.trackNoFor("12/12"))
        assertNull(L.trackNoFor("/12"))
        assertNull(L.trackNoFor("0/12"))
        assertEquals(3, L.trackNoFor("03"))
        assertEquals(3, L.trackNoFor("3/12/99"))
        assertNull(L.trackNoFor("99999999999"))
        assertNull(L.trackNoFor("3.5"))
        assertNull(L.trackNoFor("+-3"))
    }

    @Test fun trackNoSpacesAroundSlash() = assertEquals(3, L.trackNoFor(" 3 / 12 "))

    @Test fun trackNoFullWidthDigits() {
        // Kotlin toIntOrNull accepts Unicode digits; either result is fine but must not crash
        L.trackNoFor("３")
        L.trackNoFor("३")
    }

    @Test fun albumForCases() {
        assertEquals("", L.albumFor(null))
        assertEquals("", L.albumFor(""))
        assertEquals("", L.albumFor("   \t"))
        assertEquals("Abbey Road", L.albumFor("  Abbey Road "))
        assertEquals("अल्बम", L.albumFor(" अल्बम "))
        assertEquals("アルバム", L.albumFor("アルバム"))
    }

    @Test fun needsCheckMatrix() {
        assertTrue(L.needsCheck(null, null))
        assertTrue(L.needsCheck("", null))
        assertTrue(L.needsCheck("  ", ""))
        assertTrue(L.needsCheck(null, ""))
        assertFalse(L.needsCheck("T", ""))
        assertFalse(L.needsCheck("T", "A"))
        assertTrue(L.needsCheck("T", null))
    }

    @Test fun needsFullReadBlankTitles() {
        assertTrue(L.needsFullRead(""))
        assertTrue(L.needsFullRead("  "))
        assertTrue(L.needsFullRead(" \t\n"))
        assertFalse(L.needsFullRead("x"))
    }

    @Test fun skipsFileOpenMatrix() {
        // title set + non-audio -> skip
        assertTrue(L.skipsFileOpen("movie.mkv", "Movie"))
        assertTrue(L.skipsFileOpen("readme.txt", "x"))
        assertTrue(L.skipsFileOpen("noext", "x"))
        // title set + audio -> open for album tags
        assertFalse(L.skipsFileOpen("a.mp3", "x"))
        assertFalse(L.skipsFileOpen("A.MP3", "x"))
        assertFalse(L.skipsFileOpen("a.flac", "x"))
        // blank title -> full read regardless of type
        assertFalse(L.skipsFileOpen("movie.mkv", null))
        assertFalse(L.skipsFileOpen("movie.mkv", ""))
        assertFalse(L.skipsFileOpen("a.mp3", " "))
    }

    @Test fun skipsFileOpenUnicodeName() {
        assertFalse(L.skipsFileOpen("歌.mp3", "t"))
        assertTrue(L.skipsFileOpen("歌.pdf", "t"))
    }
}
