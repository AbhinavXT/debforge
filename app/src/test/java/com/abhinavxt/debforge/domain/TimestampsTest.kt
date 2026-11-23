package com.abhinavxt.debforge.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimestampsTest {
    @Test fun formats() {
        val t = findTimestamps("a 1:05 b 12:34 c 1:02:03")
        assertEquals(listOf(65L, 754L, 3723L), t.map { it.seconds })
        assertEquals("1:05", "a 1:05 b".substring(t[0].start, t[0].end))
    }

    @Test fun insideUrlIgnored() {
        assertTrue(findTimestamps("see https://x.com/v?t=1:05 now").isEmpty())
        assertEquals(1, findTimestamps("see https://x.com/a at 1:05").size)
    }

    @Test fun pastDurationIgnored() {
        assertEquals(listOf(30L), findTimestamps("0:30 and 5:00", durationSec = 120).map { it.seconds })
        assertEquals(2, findTimestamps("0:30 and 5:00").size)
    }

    @Test fun invalidMinutesIgnored() = assertTrue(findTimestamps("1:75").isEmpty())

    @Test fun validChapterList() {
        val c = descriptionChapters("Intro\n0:00 Start\n1:00 - Middle\n2:30 End", 300)
        assertEquals(listOf(0L, 60_000L, 150_000L), c.map { it.startMs })
        assertEquals(listOf("Start", "Middle", "End"), c.map { it.title })
        assertEquals(300_000L, c.last().endMs)
    }

    @Test fun missingZeroIsNotAList() = assertTrue(descriptionChapters("0:05 a\n1:00 b\n2:00 c").isEmpty())
    @Test fun fewerThanThree() = assertTrue(descriptionChapters("0:00 a\n1:00 b").isEmpty())
    @Test fun nonAscending() = assertTrue(descriptionChapters("0:00 a\n2:00 b\n1:00 c").isEmpty())
}
