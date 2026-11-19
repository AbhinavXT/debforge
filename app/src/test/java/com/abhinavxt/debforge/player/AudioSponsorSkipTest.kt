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

    @Test fun endSkipSeeksToTheTrackEnd() {
        assertEquals(100_000L, AudioSponsorSkip.endSeekMs(100_000))
        assertEquals(Long.MAX_VALUE, AudioSponsorSkip.endSeekMs(0))
    }

    @Test fun endSkipIsTheSameForEveryRepeatAndSleepState() {
        // The decision never depends on repeat mode, sleep timer or a next track: those are left to ExoPlayer's own end handling.
        val d = AudioSponsorSkip.decide(segs, 96_000, 100_000, emptySet())
        assertEquals(AudioSkip.EndTrack(95_000), d)
    }

    @Test fun midTrackSkipStaysInsideTheTrack() =
        assertEquals(AudioSkip.Seek(20_000, 10_000), AudioSponsorSkip.decide(segs, 10_500, 100_000, emptySet()))

    @Test fun failedLoadRetriesOnceAfterThirtySeconds() {
        assertFalse(AudioSponsorSkip.retryDue(1_000, 30_999, false))
        assertTrue(AudioSponsorSkip.retryDue(1_000, 31_000, false))
        assertFalse(AudioSponsorSkip.retryDue(1_000, 99_000, true))
    }
}
