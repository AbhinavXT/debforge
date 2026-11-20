package com.abhinavxt.debforge.ui.player

import com.abhinavxt.debforge.player.AudioRepeat
import com.abhinavxt.debforge.player.AudioState
import com.abhinavxt.debforge.player.AudioTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Which track the artwork carousel draws beside the current one (and whether a swipe that way does anything). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ArtworkNeighbourTest {
    private fun t(id: String) = AudioTrack(id, android.net.Uri.EMPTY, id, null, null)
    private val q = listOf(t("a"), t("b"), t("c"))

    @Test fun nextNeighbourFollowsPlayOrder() {
        assertEquals("c", artworkNeighbour(AudioState(queue = q, index = 1), 1)?.id)
        // Shuffled: the order runs from the current item.
        assertEquals("a", artworkNeighbour(AudioState(queue = q, index = 2, order = listOf(2, 0, 1), shuffle = true), 1)?.id)
    }
    @Test fun noNextAtTheEnd() {
        val s = AudioState(queue = q, index = 2, hasNext = false)
        assertEquals(null, artworkNeighbour(s, 1))
        assertFalse(canArtworkSwipe(s, 1))
    }
    @Test fun previousNeighbourEarlyInTheTrack() = assertEquals("a", artworkNeighbour(AudioState(queue = q, index = 1, positionMs = 1000), -1)?.id)
    @Test fun previousRestartsLateInTheTrack() = assertEquals("b", artworkNeighbour(AudioState(queue = q, index = 1, positionMs = 10_000), -1)?.id)
    @Test fun noPreviousAtTheStart() {
        val s = AudioState(queue = q, index = 0, positionMs = 0, hasPrevious = false)
        assertEquals(null, artworkNeighbour(s, -1))
        assertFalse(canArtworkSwipe(s, -1))
    }
    @Test fun repeatAllWrapsBothWays() {
        val s = AudioState(queue = q, index = 0, repeat = AudioRepeat.ALL)
        assertEquals("c", artworkNeighbour(s, -1)?.id)
        assertEquals("a", artworkNeighbour(s.copy(index = 2), 1)?.id)
    }
    @Test fun shuffledPreviousUnknownWithoutRepeat() {
        // Under shuffle the order starts at the current item.
        assertEquals(null, previousQueueIndex(listOf(1, 2, 0), 1, AudioRepeat.OFF, 3))
        assertEquals(0, previousQueueIndex(listOf(1, 2, 0), 1, AudioRepeat.ALL, 3))
    }
    @Test fun nextArrivesFromTheRightInLtr() {
        assertEquals(1, arrivingSide(1, false))
        assertEquals(-1, arrivingSide(-1, false))
        assertEquals(-1, arrivingSide(1, true))
    }
}
