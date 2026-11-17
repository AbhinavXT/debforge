package com.abhinavxt.debforge.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class SeekStepTest {
    @Test fun firstStepUsesPosition() = assertEquals(40_000L, nextSeekTarget(null, 30_000, 10_000, 100_000))

    @Test fun heldKeyContinuesFromPendingTarget() {
        val a = nextSeekTarget(null, 30_000, 10_000, 100_000)
        val b = nextSeekTarget(a, 30_000, 10_000, 100_000)
        assertEquals(50_000L, b)
    }

    @Test fun clampsToBounds() {
        assertEquals(0L, nextSeekTarget(null, 3_000, -10_000, 100_000))
        assertEquals(100_000L, nextSeekTarget(95_000, 0, 10_000, 100_000))
    }
}
