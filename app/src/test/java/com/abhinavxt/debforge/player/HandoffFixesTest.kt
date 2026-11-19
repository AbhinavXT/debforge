package com.abhinavxt.debforge.player

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HandoffFixesTest {
    @Test fun parkingCancelsAnActivationOfAParkedQueue() {
        assertEquals(ParkAction.CANCEL_ACTIVATION, parkAction(queueEmpty = false, restoredPending = true))
        assertEquals(ParkAction.PARK, parkAction(queueEmpty = false, restoredPending = false))
        assertEquals(ParkAction.NONE, parkAction(queueEmpty = true, restoredPending = false))
        assertEquals(ParkAction.NONE, parkAction(queueEmpty = true, restoredPending = true))
    }

    @Test fun watchStartIsTheLaunchPositionClamped() {
        assertEquals(VideoStart(42_000, 1.5f), watchStart(42_000, 1.5f))
        assertEquals(0L, watchStart(-5, 1f).positionMs)
    }

    @Test fun handoffWaitsForARestoreInFlight() = runBlocking {
        var restored = false
        val job = launch { delay(50); restored = true }
        awaitRestore(job)
        assertTrue(restored)
        awaitRestore(null) // nothing in flight
    }

    @Test fun handoffIdIsClearedWhenPlaybackReachesOrLeavesTheItem() {
        // Reached by a real transition: resume skipped once, id cleared.
        assertEquals(HandoffMark(true, null), handoffAfterTransition("x", "x", playlistChanged = false))
        // Moved on to another item: the id is stale, cleared, resume applies.
        assertEquals(HandoffMark(false, null), handoffAfterTransition("x", "y", playlistChanged = false))
        // An in-place replace (activation while parked) says nothing: kept.
        assertEquals(HandoffMark(false, "x"), handoffAfterTransition("x", "x", playlistChanged = true))
        assertNull(handoffAfterTransition(null, "x", playlistChanged = false).next)
        assertFalse(handoffAfterTransition(null, "x", playlistChanged = false).skipResume)
    }
}
