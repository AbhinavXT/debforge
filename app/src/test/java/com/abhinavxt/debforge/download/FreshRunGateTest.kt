package com.abhinavxt.debforge.download

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test

class FreshRunGateTest {
    @Test fun idleCallRunsOnce() = runBlocking {
        val gate = FreshRunGate()
        var runs = 0
        gate.runFresh { runs++ }
        assertEquals(1, runs)
    }

    @Test fun callWhileBusyRunsAgainAfterwards() = runBlocking {
        val gate = FreshRunGate()
        val release = CompletableDeferred<Unit>()
        var runs = 0
        val first = async { gate.runFresh { runs++; release.await() } }
        yield()
        val second = async { gate.runFresh { runs++ } }
        yield()
        assertEquals(1, runs) // second is waiting, not skipped
        release.complete(Unit)
        first.await(); second.await()
        assertEquals(2, runs)
    }

    @Test fun callersQueuedTogetherShareOneRerun() = runBlocking {
        val gate = FreshRunGate()
        val release = CompletableDeferred<Unit>()
        var runs = 0
        val first = async { gate.runFresh { runs++; release.await() } }
        yield()
        val waiting = List(3) { async { gate.runFresh { runs++ } } }
        yield()
        release.complete(Unit)
        first.await(); waiting.forEach { it.await() }
        assertEquals(2, runs)
    }

    @Test fun cancelledRunDoesNotCoverWaiters() = runBlocking {
        val gate = FreshRunGate()
        val never = CompletableDeferred<Unit>()
        var runs = 0
        val first = async { gate.runFresh { runs++; never.await() } }
        yield()
        val second = async { gate.runFresh { runs++ } }
        yield()
        first.cancel()
        second.await()
        assertEquals(2, runs)
    }
}
