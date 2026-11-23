package com.abhinavxt.debforge.data.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LrcTest {
    @Test fun parsesTimestampForms() {
        val l = parseLrc("[00:01.5]a\n[01:02.25]b\n[02:03.123]c\n[03:04]d")
        assertEquals(listOf(1500L, 62_250L, 123_123L, 184_000L), l.map { it.timeMs })
        assertEquals(listOf("a", "b", "c", "d"), l.map { it.text })
    }

    @Test fun multipleTimestampsPerLineAreSorted() {
        val l = parseLrc("[00:30.00][00:10.00] chorus\n[00:20.00]verse")
        assertEquals(listOf(10_000L to "chorus", 20_000L to "verse", 30_000L to "chorus"), l.map { it.timeMs to it.text })
    }

    @Test fun ignoresMetadataAndBlankLines() {
        val l = parseLrc("[ar:Someone]\n[ti:Song]\n\n   \n[00:05.00]hi")
        assertEquals(1, l.size)
        assertEquals("hi", l[0].text)
    }

    @Test fun appliesOffsetAndKeepsEmptyTimedLines() {
        val l = parseLrc("[offset:+500]\n[00:05.00]\n[00:06.00]x")
        assertEquals(listOf(4500L to "", 5500L to "x"), l.map { it.timeMs to it.text })
    }

    @Test fun lineAtBinarySearch() {
        val l = parseLrc("[00:10.00]a\n[00:20.00]b\n[00:30.00]c")
        assertEquals(-1, lineAt(l, 9_999))
        assertEquals(0, lineAt(l, 10_000))
        assertEquals(1, lineAt(l, 29_999))
        assertEquals(2, lineAt(l, 999_999))
        assertEquals(-1, lineAt(emptyList(), 5))
    }

    @Test fun cleansTitles() {
        assertEquals("Song", cleanTitle("Song (Official Video)"))
        assertEquals("Song", cleanTitle("Song [Lyrics]"))
        assertEquals("Song", cleanTitle("Song (feat. Other) [Official Audio]"))
        assertEquals("Song", cleanTitle("Song ft. Other"))
        assertEquals("Song (Live in Paris)", cleanTitle("Song (Live in Paris)"))
        assertEquals("Artist - Song", cleanTitle("Artist - Song (Official Music Video) HD".replace(" HD", "")))
        assertEquals("(Official Video)", cleanTitle("(Official Video)"))
        assertEquals("Name", cleanArtist("Name - Topic"))
        assertNull(cleanArtist("  "))
    }

    @Test fun picksClosestDurationWithinThree() {
        val a = LrcLibItem(plainLyrics = "a", duration = 200.0)
        val b = LrcLibItem(plainLyrics = "b", duration = 180.5)
        val c = LrcLibItem(plainLyrics = "c", duration = 179.0)
        assertEquals(b, pickLyrics(listOf(a, c, b), 180))
        assertEquals(a, pickLyrics(listOf(a, LrcLibItem(duration = 180.0)), 150))
        assertEquals(a, pickLyrics(listOf(a), 0))
        assertNull(pickLyrics(listOf(LrcLibItem(plainLyrics = " ")), 100))
    }
}
