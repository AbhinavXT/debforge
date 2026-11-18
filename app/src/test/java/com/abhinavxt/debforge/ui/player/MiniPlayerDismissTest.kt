package com.abhinavxt.debforge.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class MiniPlayerDismissTest {
    private val fling = 2000f
    @Test fun farRightDismissesRight() = assertEquals(1, miniPlayerDismissDirection(500f, 0f, 1000f, fling))
    @Test fun farLeftDismissesLeft() = assertEquals(-1, miniPlayerDismissDirection(-500f, 0f, 1000f, fling))
    @Test fun shortSlowSpringsBack() = assertEquals(0, miniPlayerDismissDirection(100f, 300f, 1000f, fling))
    @Test fun fastFlickEitherWay() {
        assertEquals(-1, miniPlayerDismissDirection(-10f, -3000f, 1000f, fling))
        assertEquals(1, miniPlayerDismissDirection(10f, 3000f, 1000f, fling))
    }
    @Test fun unmeasuredWidthDoesNothing() = assertEquals(0, miniPlayerDismissDirection(500f, 5000f, 0f, fling))
    @Test fun nanDoesNothing() = assertEquals(0, miniPlayerDismissDirection(Float.NaN, 0f, 1000f, fling))
    @Test fun trackChangeDuringDismissAnimationKeepsIt() = assertEquals(false, shouldResetOnTrackChange(true, true))
    @Test fun trackChangeAfterDismissFinishedResets() = assertEquals(true, shouldResetOnTrackChange(true, false))
    @Test fun trackChangeWhenIdleResets() = assertEquals(true, shouldResetOnTrackChange(false, false))
}
