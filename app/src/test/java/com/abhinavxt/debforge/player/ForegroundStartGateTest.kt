package com.abhinavxt.debforge.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundStartGateTest {
    @Test fun stopIsImmediateWithoutAPendingForegroundStart() {
        val gate = ForegroundStartGate()
        assertTrue(gate.requestStop())
        gate.plainStartRequested()
        assertTrue(gate.requestStop())
    }

    @Test fun stopWhilePendingIsDeferredToTheService() {
        val gate = ForegroundStartGate()
        gate.foregroundStartRequested()
        assertFalse(gate.requestStop())
        assertTrue(gate.onForeground())
        // Done: later stops go straight through, and nothing is owed any more.
        assertTrue(gate.requestStop())
        assertFalse(gate.onForeground())
    }

    @Test fun pendingWithoutStopKeepsTheServiceRunning() {
        val gate = ForegroundStartGate()
        gate.foregroundStartRequested()
        assertFalse(gate.onForeground())
        assertTrue(gate.requestStop())
    }

    @Test fun aLaterStartCancelsADeferredStop() {
        val gate = ForegroundStartGate()
        gate.foregroundStartRequested()
        assertFalse(gate.requestStop())
        gate.plainStartRequested()
        assertFalse(gate.onForeground())

        gate.foregroundStartRequested()
        assertFalse(gate.requestStop())
        gate.foregroundStartRequested()
        assertFalse(gate.onForeground())
        assertFalse(gate.onForeground())
    }

    @Test fun aNewStartAfterTheOldServiceEndedStaysDeferred() {
        val gate = ForegroundStartGate()
        gate.foregroundStartRequested()
        assertFalse(gate.onForeground())
        assertTrue(gate.requestStop()) // old instance stopped and is being destroyed
        gate.foregroundStartRequested() // a new widget tap before its onDestroy
        // The old instance ending clears nothing, so the next stop still waits for the new start.
        assertFalse(gate.requestStop())
        assertTrue(gate.onForeground())
    }

    @Test fun twoPendingStartsStopOnlyAfterBothAreAnswered() {
        val gate = ForegroundStartGate()
        gate.foregroundStartRequested()
        gate.foregroundStartRequested()
        assertFalse(gate.requestStop())
        assertFalse(gate.onForeground()) // the second start is still owed
        assertFalse(gate.requestStop())
        assertTrue(gate.onForeground())
        assertTrue(gate.requestStop())
    }

    @Test fun aFailedStartOwesNothing() {
        val gate = ForegroundStartGate()
        gate.foregroundStartRequested()
        gate.foregroundStartFailed()
        assertTrue(gate.requestStop())
    }
}
