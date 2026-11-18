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
        // Done: later stops go straight through, and the next foreground call owes nothing.
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
    }

    @Test fun resetDropsEverythingOwed() {
        val gate = ForegroundStartGate()
        gate.foregroundStartRequested()
        assertFalse(gate.requestStop())
        gate.reset()
        assertTrue(gate.requestStop())
        assertFalse(gate.onForeground())
    }
}
