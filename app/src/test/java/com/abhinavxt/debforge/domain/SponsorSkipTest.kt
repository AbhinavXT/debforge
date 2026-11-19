package com.abhinavxt.debforge.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SponsorSkipTest {
    private fun s(a: Long, b: Long, c: String = "sponsor") = SponsorSegment(c, a, b)

    @Test fun mergesOverlappingAndAdjacent() {
        val out = SponsorSkip.normalise(listOf(s(20_000, 30_000), s(0, 5_000), s(4_000, 10_000), s(10_000, 12_000)), 100_000)
        assertEquals(listOf(s(0, 12_000), s(20_000, 30_000)), out)
    }

    @Test fun clampsToDuration() {
        assertEquals(listOf(s(90_000, 100_000)), SponsorSkip.normalise(listOf(s(90_000, 130_000)), 100_000))
        assertEquals(emptyList<SponsorSegment>(), SponsorSkip.normalise(listOf(s(110_000, 130_000)), 100_000))
    }

    @Test fun dropsShortSegments() {
        assertEquals(emptyList<SponsorSegment>(), SponsorSkip.normalise(listOf(s(5_000, 5_900)), 0))
        assertEquals(1, SponsorSkip.normalise(listOf(s(5_000, 6_000)), 0).size)
    }

    @Test fun segmentAtAndTargets() {
        val l = listOf(s(10_000, 20_000), s(40_000, 50_000))
        assertNull(SponsorSkip.segmentAt(l, 9_999))
        assertEquals(l[0], SponsorSkip.segmentAt(l, 10_000))
        assertNull(SponsorSkip.segmentAt(l, 20_000))
        assertEquals(20_000L, SponsorSkip.skipTarget(l, 15_000))
        assertNull(SponsorSkip.skipTarget(l, 30_000))
        assertEquals(l[1], SponsorSkip.nextAfter(l, 20_000))
        assertNull(SponsorSkip.nextAfter(l, 40_000))
    }

    @Test fun categoryMapping() {
        assertEquals(IntroSkip.Kind.SPONSOR, SponsorSkip.kindOf("sponsor"))
        assertEquals(IntroSkip.Kind.SELF_PROMO, SponsorSkip.kindOf("selfpromo"))
        assertEquals(IntroSkip.Kind.INTERACTION, SponsorSkip.kindOf("interaction"))
        assertEquals(IntroSkip.Kind.INTRO, SponsorSkip.kindOf("intro"))
        assertEquals(IntroSkip.Kind.CREDITS, SponsorSkip.kindOf("outro"))
        assertEquals(IntroSkip.Kind.PREVIEW, SponsorSkip.kindOf("preview"))
        assertNull(SponsorSkip.kindOf("music_offtopic"))
    }
}
