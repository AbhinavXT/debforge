package com.abhinavxt.debforge.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioFadeTest {
    private val min = 60_000L
    private fun vol(pos: Long, dur: Long = 4 * min, speed: Float = 1f, fade: Long = 3000, i: Boolean = true, o: Boolean = true) =
        fadeVolume(pos, dur, speed, fade, i, o)

    @Test fun edgesAndMiddle() {
        assertEquals(0f, vol(0), 0.001f)
        assertEquals(0.5f, vol(1500), 0.001f)
        assertEquals(1f, vol(3000), 0.001f)
        assertEquals(1f, vol(2 * min), 0.001f)
        assertEquals(0.5f, vol(4 * min - 1500), 0.001f)
        assertEquals(0f, vol(4 * min), 0.001f)
    }

    @Test fun onlyTheRequestedSides() {
        assertEquals(1f, vol(0, i = false), 0.001f)
        assertEquals(1f, vol(4 * min, o = false), 0.001f)
    }

    @Test fun durationsAreRealTime() {
        assertEquals(0.5f, vol(3000, speed = 2f), 0.001f) // 3 s of media = 1.5 s of real time
        assertEquals(0.5f, vol(4 * min - 750, speed = 0.5f), 0.001f) // 0.75 s of media = 1.5 s real
    }

    @Test fun capAtAQuarterOfTheTrack() {
        // 8 s track, 12 s fade: capped to 2 s.
        assertEquals(0.5f, vol(1000, dur = 8000, fade = 12_000, o = false), 0.001f)
        assertEquals(1f, vol(2000, dur = 8000, fade = 12_000), 0.001f)
        assertEquals(0.5f, vol(7000, dur = 8000, fade = 12_000), 0.001f)
        // At 2x the 8 s track lasts 4 s, so the cap is 1 s of real time.
        assertEquals(0.5f, vol(1000, dur = 8000, speed = 2f, fade = 12_000, o = false), 0.001f)
    }

    @Test fun invalidInputIsFullVolume() {
        assertEquals(1f, vol(0, dur = 0), 0f)
        assertEquals(1f, vol(0, dur = -1), 0f)
        assertEquals(1f, vol(0, speed = 0f), 0f)
        assertEquals(1f, vol(0, fade = 0), 0f)
        assertEquals(1f, vol(-5), 0f)
        assertEquals(0f, vol(5 * min, i = false), 0f) // past the end clamps, never negative
    }

    private val song = FadeInfo(stream = false, albumKey = null, trackNo = null)
    private val dur = 3 * min
    private fun eligible(cur: FadeInfo = song, next: FadeInfo? = song, d: Long = dur, one: Boolean = false, sleep: Boolean = false) =
        fadeEligible(cur, next, d, one, sleep)

    @Test fun plainNeighboursFade() = assertTrue(eligible())

    @Test fun streamsNeverFade() {
        assertFalse(eligible(cur = song.copy(stream = true)))
        assertFalse(eligible(next = song.copy(stream = true)))
    }

    @Test fun unknownDurationNeverFades() {
        assertFalse(eligible(d = 0))
        assertFalse(eligible(d = -1))
    }

    @Test fun longRememberedTrackNeverFades() {
        assertFalse(eligible(d = AUDIO_REMEMBER_OVER_MS + 1))
        assertTrue(eligible(d = AUDIO_REMEMBER_OVER_MS))
    }

    @Test fun repeatOneAndQueueEndNeverFade() {
        assertFalse(eligible(one = true))
        assertFalse(eligible(next = null))
    }

    @Test fun consecutiveAlbumTracksDoNotFade() {
        val a = song.copy(albumKey = "x\u0000y", trackNo = 3)
        assertFalse(eligible(cur = a, next = a.copy(trackNo = 4)))
        assertFalse(eligible(cur = a.copy(trackNo = null), next = a.copy(trackNo = null)))
        assertTrue(eligible(cur = a, next = a.copy(trackNo = 7))) // shuffled: not consecutive
        assertTrue(eligible(cur = a, next = a.copy(albumKey = "other")))
        assertTrue(eligible(cur = song, next = song)) // no album tag: fades
    }

    @Test fun sleepEndFadesOutEvenAtTheQueueEnd() {
        assertTrue(eligible(next = null, sleep = true))
        assertTrue(eligible(next = song.copy(stream = true), one = true, sleep = true))
        assertFalse(eligible(cur = song.copy(stream = true), sleep = true))
        assertFalse(eligible(d = 0, sleep = true))
    }
}
