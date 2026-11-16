package com.abhinavxt.debforge.player

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioPlayerLogicTest {
    @Test fun repeatCyclesOffAllOneOff() {
        assertEquals(AudioRepeat.ALL, AudioRepeat.OFF.next())
        assertEquals(AudioRepeat.ONE, AudioRepeat.ALL.next())
        assertEquals(AudioRepeat.OFF, AudioRepeat.ONE.next())
    }

    @Test fun repeatMapsToPlayerModesAndBack() {
        AudioRepeat.entries.forEach { assertEquals(it, repeatFromPlayerMode(it.toPlayerMode())) }
        assertEquals(Player.REPEAT_MODE_ONE, AudioRepeat.ONE.toPlayerMode())
    }

    @Test fun sleepMinutesRoundUpAndNeverShowZero() {
        assertEquals(1, sleepMinutesLeft(0))
        assertEquals(1, sleepMinutesLeft(1))
        assertEquals(1, sleepMinutesLeft(60_000))
        assertEquals(2, sleepMinutesLeft(60_001))
        assertEquals(15, sleepMinutesLeft(15 * 60_000L))
    }

    @Test fun speedLabelsDropTrailingZero() {
        assertEquals("1x", speedLabel(1f))
        assertEquals("0.75x", speedLabel(0.75f))
        assertEquals("2x", speedLabel(2f))
    }

    @Test fun clockFormats() {
        assertEquals("0:00", formatClock(-5))
        assertEquals("3:05", formatClock(185_900))
        assertEquals("1:02:03", formatClock(3_723_000))
    }

    @Test fun playOrderFollowsNextFromCurrentAndStops() {
        val next = intArrayOf(-1, -1, 0) // order 2 -> 0 -> end
        assertEquals(listOf(1), playOrder(3, 1) { next[it] })
        assertEquals(listOf(2, 0), playOrder(3, 2) { next[it] })
        assertEquals(listOf(0, 1, 2), playOrder(3, 0) { (it + 1) % 3 }) // cycle capped at count
    }

    @Test fun upNextListsOnlyWhatPlaysNext() {
        val order = listOf(0, 1, 2)
        assertEquals(emptyList<Int>(), upNextOrder(order, 2, AudioRepeat.OFF))
        assertEquals(emptyList<Int>(), upNextOrder(order, 2, AudioRepeat.ONE))
        assertEquals(listOf(2), upNextOrder(order, 1, AudioRepeat.OFF))
        assertEquals(listOf(0, 1), upNextOrder(order, 2, AudioRepeat.ALL))
        assertEquals(listOf(2, 0), upNextOrder(order, 1, AudioRepeat.ALL))
        assertEquals(listOf(0), upNextOrder(listOf(2, 0), 2, AudioRepeat.ALL))
    }

    @Test fun queueWithoutDropsIds() {
        assertEquals(listOf("a", "c"), queueWithout(listOf("a", "b", "c"), setOf("b", "x")) { it })
    }

    @Test fun bufferingStillCountsAsPlaying() {
        assertEquals(true, wantsToPlay(true, Player.STATE_BUFFERING))
        assertEquals(false, wantsToPlay(false, Player.STATE_READY))
        assertEquals(false, wantsToPlay(true, Player.STATE_ENDED))
    }

    @Test fun stateAssumesNeighboursUntilThePlayerSaysOtherwise() {
        org.junit.Assert.assertTrue(AudioState().hasNext && AudioState().hasPrevious)
        org.junit.Assert.assertFalse(AudioState(hasNext = false).hasNext)
    }
}
