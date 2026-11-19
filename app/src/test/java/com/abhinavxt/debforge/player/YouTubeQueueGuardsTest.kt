package com.abhinavxt.debforge.player

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class YouTubeQueueGuardsTest {
    @Test fun breakerTripsOnThirdInARowAndStartsOver() {
        val b = BrokenBreaker(3)
        assertFalse(b.recordBroken()); assertFalse(b.recordBroken())
        assertTrue(b.recordBroken())
        assertFalse(b.recordBroken()) // counting again from zero
    }

    @Test fun breakerResetOnSuccess() {
        val b = BrokenBreaker(3)
        b.recordBroken(); b.recordBroken()
        b.reset()
        assertFalse(b.recordBroken()); assertFalse(b.recordBroken())
        assertTrue(b.recordBroken())
    }

    @Test fun toastCollapsesInsideWindow() {
        val t = ToastCollapser(4_000)
        assertTrue(t.shouldShow(1_000))
        assertFalse(t.shouldShow(2_000))
        assertFalse(t.shouldShow(4_999))
        assertTrue(t.shouldShow(5_000))
        assertFalse(t.shouldShow(6_000))
    }

    @Test fun playNextGoesRightAfterCurrentInShuffleOrder() {
        // queue 0..3 plays 2,0,3,1; current = 0; insert at 1 (current + 1)
        val out = orderWithPlayNext(listOf(2, 0, 3, 1), current = 0, at = 1)
        assertEquals(listOf(3, 0, 1, 4, 2), out) // old 1 -> 2, old 2 -> 3, old 3 -> 4; new 1 right after 0
        assertEquals(listOf(0, 1, 2, 3, 4), out.sorted())
    }

    @Test fun playNextShiftsLaterIndices() {
        assertEquals(listOf(2, 0, 1), orderWithPlayNext(listOf(1, 0), current = 0, at = 1))
        assertEquals(listOf(0, 1, 2), orderWithPlayNext(listOf(0, 1), current = 1, at = 2))
    }

    @Test fun duplicateCheckCoversStreamAndDownload() {
        val queue = listOf("ytaudio:abc", "dl-7")
        assertTrue(youTubeAlreadyQueued(queue, "abc", "ytaudio:abc")) // stream queued, stream added
        assertTrue(youTubeAlreadyQueued(queue, "abc", "dl-1")) // stream queued, its download added
        assertTrue(youTubeAlreadyQueued(queue, "xyz", "dl-7")) // download queued, added again
        assertFalse(youTubeAlreadyQueued(queue, "xyz", "ytaudio:xyz"))
    }

    @Test fun inFlightSharesOneRunPerKey() = runBlocking {
        val inFlight = InFlight<String, Int>()
        val runs = AtomicInteger()
        val gate = CompletableDeferred<Unit>()
        val scope = CoroutineScope(Dispatchers.Default)
        val a = async { inFlight.run("v", scope) { runs.incrementAndGet(); gate.await(); 42 } }
        while (!inFlight.isRunning("v")) kotlinx.coroutines.yield()
        val b = async { inFlight.run("v", scope) { runs.incrementAndGet(); 99 } }
        kotlinx.coroutines.yield() // b reaches its await while the run is still open
        gate.complete(Unit)
        assertEquals(42, a.await()); assertEquals(42, b.await())
        assertEquals(1, runs.get())
        // finished: a new call runs again
        assertEquals(7, inFlight.run("v", scope) { 7 })
    }
}
