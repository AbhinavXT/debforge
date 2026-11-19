package com.abhinavxt.debforge.player

import com.abhinavxt.debforge.domain.SponsorSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioSponsorSkipTest {
    private val segs = listOf(SponsorSegment("sponsor", 10_000, 20_000), SponsorSegment("outro", 95_000, 100_000))

    @Test fun outsideSegmentDoesNothing() =
        assertEquals(AudioSkip.None, AudioSponsorSkip.decide(segs, 5_000, 100_000, emptySet()))

    @Test fun insideSegmentSeeksToEnd() =
        assertEquals(AudioSkip.Seek(20_000, 10_000), AudioSponsorSkip.decide(segs, 12_000, 100_000, emptySet()))

    @Test fun doneSegmentIsLeftAlone() =
        assertEquals(AudioSkip.None, AudioSponsorSkip.decide(segs, 12_000, 100_000, setOf(10_000L)))

    @Test fun segmentToTheEndFinishesTheTrack() =
        assertEquals(AudioSkip.EndTrack(95_000), AudioSponsorSkip.decide(segs, 96_000, 100_000, emptySet()))

    @Test fun unknownDurationSeeks() =
        assertEquals(AudioSkip.Seek(100_000, 95_000), AudioSponsorSkip.decide(segs, 96_000, 0, emptySet()))

    @Test fun staleLoadIsIgnored() {
        assertTrue(AudioSponsorSkip.loadStillCurrent("a", "a"))
        assertFalse(AudioSponsorSkip.loadStillCurrent("a", "b"))
        assertFalse(AudioSponsorSkip.loadStillCurrent(null, null))
    }
}
