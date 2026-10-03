package com.abhinavxt.debforge.domain

import com.abhinavxt.debforge.domain.IntroSkip.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IntroSkipTest {

    private fun ch(startS: Long, title: String?, endS: Long? = null) =
        MkvChapters.Chapter(startS * 1000, endS?.let { it * 1000 }, title)

    @Test fun recognisesCommonChapterNames() {
        assertEquals(Kind.INTRO, IntroSkip.kindOf("Opening"))
        assertEquals(Kind.INTRO, IntroSkip.kindOf("OP"))
        assertEquals(Kind.INTRO, IntroSkip.kindOf("Intro"))
        assertEquals(Kind.INTRO, IntroSkip.kindOf("Opening Credits"))
        assertEquals(Kind.RECAP, IntroSkip.kindOf("Previously On"))
        assertEquals(Kind.RECAP, IntroSkip.kindOf("Recap"))
        assertEquals(Kind.CREDITS, IntroSkip.kindOf("Ending"))
        assertEquals(Kind.CREDITS, IntroSkip.kindOf("ED"))
        assertEquals(Kind.CREDITS, IntroSkip.kindOf("End Credits"))
        assertEquals(Kind.PREVIEW, IntroSkip.kindOf("Preview"))
    }

    @Test fun ordinaryChaptersAreNotSkippable() {
        assertNull(IntroSkip.kindOf("Chapter 01"))
        assertNull(IntroSkip.kindOf("Part A"))
        assertNull(IntroSkip.kindOf("Operation Overlord")) // starts with "op"
        assertNull(IntroSkip.kindOf("The Opening Night")) // contains "opening"
        assertNull(IntroSkip.kindOf(null))
    }

    @Test fun segmentEndsAtTheNextChapter() {
        val segs = IntroSkip.fromChapters(listOf(ch(0, "Prologue"), ch(95, "Opening"), ch(185, "Part A"), ch(1300, "Ending"), ch(1390, "Preview")), 1420_000)
        assertEquals(
            listOf(
                IntroSkip.Segment(Kind.INTRO, 95_000, 185_000),
                IntroSkip.Segment(Kind.CREDITS, 1300_000, 1390_000),
                IntroSkip.Segment(Kind.PREVIEW, 1390_000, 1420_000)
            ),
            segs
        )
    }

    @Test fun explicitEndWins() {
        val segs = IntroSkip.fromChapters(listOf(ch(0, "Intro", endS = 40), ch(300, "Story")), 2_000_000)
        assertEquals(40_000L, segs.single().endMs)
    }

    @Test fun backToBackSameKindMerge() {
        val segs = IntroSkip.fromChapters(listOf(ch(0, "OP 1"), ch(45, "OP 2"), ch(90, "Story")), 1_000_000)
        assertEquals(listOf(IntroSkip.Segment(Kind.INTRO, 0, 90_000)), segs)
    }

    @Test fun implausibleLengthsAreIgnored() {
        assertTrue(IntroSkip.fromChapters(listOf(ch(0, "Intro"), ch(2, "Story")), 1_000_000).isEmpty())
        assertTrue(IntroSkip.fromChapters(listOf(ch(0, "Intro"), ch(1200, "Story")), 3_000_000).isEmpty())
    }

    @Test fun activeSegment() {
        val segs = listOf(IntroSkip.Segment(Kind.INTRO, 60_000, 150_000))
        assertNull(IntroSkip.at(segs, 59_999))
        assertEquals(Kind.INTRO, IntroSkip.at(segs, 60_000)?.kind)
        assertNull(IntroSkip.at(segs, 149_500)) // last second: hidden
    }

    @Test fun learntWindowAroundLastSkip() {
        assertTrue(IntroSkip.inLearntWindow(120_000, 100_000))
        assertTrue(IntroSkip.inLearntWindow(120_000, 150_000))
        assertFalse(IntroSkip.inLearntWindow(120_000, 99_000))
        assertFalse(IntroSkip.inLearntWindow(120_000, 160_000))
        assertTrue(IntroSkip.inLearntWindow(5_000, 0))
    }

    @Test fun onlyEarlySkipsAreIntros() {
        assertTrue(IntroSkip.isLikelyIntro(90_000, 2_700_000))
        assertFalse(IntroSkip.isLikelyIntro(20 * 60_000, 3_600_000))
        assertFalse(IntroSkip.isLikelyIntro(9 * 60_000, 20 * 60_000)) // past a third of a short episode
    }
}
