package com.abhinavxt.debforge.player.controls

import org.junit.Assert.assertEquals
import org.junit.Test

class NudgeMathTest {
    @Test fun stepsAdd() {
        assertEquals(1_100L, NudgeMath.step(0, 1_100))
        assertEquals(-100L, NudgeMath.step(0, -NudgeMath.FINE_STEP_MS))
    }

    @Test fun clampsAtTenSeconds() {
        assertEquals(10_000L, NudgeMath.step(9_500, 1_000))
        assertEquals(-10_000L, NudgeMath.step(-9_950, -100))
        assertEquals(10_000L, NudgeMath.step(10_000, 100))
    }

    @Test fun repeatPacing() {
        assertEquals(400L, NudgeMath.repeatDelayMs(0))
        assertEquals(80L, NudgeMath.repeatDelayMs(1))
        assertEquals(80L, NudgeMath.repeatDelayMs(50))
    }
}
