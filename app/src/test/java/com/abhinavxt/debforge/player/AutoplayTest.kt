package com.abhinavxt.debforge.player

import com.abhinavxt.debforge.domain.YouTubeSearchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoplayTest {
    private fun r(id: String, dur: Long = 200) =
        YouTubeSearchResult("https://www.youtube.com/watch?v=$id", "t$id", "ch", dur, null)

    @Test fun picksFirstRelated() {
        assertEquals("aaaaaaaaaaa", pickAutoplay(listOf(r("aaaaaaaaaaa"), r("bbbbbbbbbbb")), emptySet(), emptySet())?.url?.substringAfter("v="))
    }

    @Test fun skipsPlayedThisSession() {
        val pick = pickAutoplay(listOf(r("aaaaaaaaaaa"), r("bbbbbbbbbbb")), setOf("aaaaaaaaaaa"), emptySet())
        assertEquals("bbbbbbbbbbb", pick?.url?.substringAfter("v="))
    }

    @Test fun skipsAlreadyQueued() {
        val pick = pickAutoplay(listOf(r("aaaaaaaaaaa"), r("bbbbbbbbbbb")), emptySet(), setOf("aaaaaaaaaaa"))
        assertEquals("bbbbbbbbbbb", pick?.url?.substringAfter("v="))
    }

    @Test fun skipsLiveStreams() {
        val pick = pickAutoplay(listOf(r("aaaaaaaaaaa", 0), r("bbbbbbbbbbb")), emptySet(), emptySet())
        assertEquals("bbbbbbbbbbb", pick?.url?.substringAfter("v="))
    }

    @Test fun nothingFitsGivesNull() {
        assertNull(pickAutoplay(emptyList(), emptySet(), emptySet()))
        assertNull(pickAutoplay(listOf(r("aaaaaaaaaaa", 0), r("bbbbbbbbbbb")), setOf("bbbbbbbbbbb"), emptySet()))
        assertNull(pickAutoplay(listOf(YouTubeSearchResult("https://example.com/x", "t", null, 10, null)), emptySet(), emptySet()))
    }

    @Test fun triggersOnLastYouTubeItemWithRepeatOff() {
        assertTrue(shouldAutoplay(true, true, AudioRepeat.OFF, true, false))
    }

    @Test fun noTriggerWhenNotLast() = assertFalse(shouldAutoplay(false, true, AudioRepeat.OFF, true, false))

    @Test fun noTriggerWithRepeatAllOrOne() {
        assertFalse(shouldAutoplay(true, true, AudioRepeat.ALL, true, false))
        assertFalse(shouldAutoplay(true, true, AudioRepeat.ONE, true, false))
    }

    @Test fun noTriggerWhenSettingOff() = assertFalse(shouldAutoplay(true, true, AudioRepeat.OFF, false, false))

    @Test fun noTriggerWhenSleepAtEndArmed() = assertFalse(shouldAutoplay(true, true, AudioRepeat.OFF, true, true))

    @Test fun noTriggerForNonYouTubeLastItem() = assertFalse(shouldAutoplay(true, false, AudioRepeat.OFF, true, false))

    @Test fun appendPutsNewItemLastInPlayOrder() {
        assertEquals(listOf(2, 0, 1, 3), orderWithAppend(listOf(2, 0, 1), 3))
        assertEquals(listOf(0), orderWithAppend(emptyList(), 0))
    }

    @Test fun pickedIdIsMarkedTried() {
        val played = HashSet<String>()
        val first = pickAutoplayMarked(listOf(r("aaaaaaaaaaa"), r("bbbbbbbbbbb")), played, emptySet())
        assertEquals("aaaaaaaaaaa", first?.url?.substringAfter("v="))
        assertTrue("aaaaaaaaaaa" in played)
        // Dropped as unavailable (never queued): the next pick moves on instead of looping.
        val second = pickAutoplayMarked(listOf(r("aaaaaaaaaaa"), r("bbbbbbbbbbb")), played, emptySet())
        assertEquals("bbbbbbbbbbb", second?.url?.substringAfter("v="))
        assertNull(pickAutoplayMarked(listOf(r("aaaaaaaaaaa"), r("bbbbbbbbbbb")), played.also { it.add("bbbbbbbbbbb") }, emptySet()))
    }

    @Test fun noTriggerWhenSleepCancelledOnlyAfterwards() {
        assertFalse(shouldAutoplay(true, true, AudioRepeat.OFF, true, true))
        assertTrue(shouldAutoplay(true, true, AudioRepeat.OFF, true, false)) // re-evaluated once the timer is off
    }
}
