package com.abhinavxt.debforge.ui.youtube

import org.junit.Assert.assertEquals
import org.junit.Test

class DiscoverTopBarTest {
    @Test fun scrollingDownHidesUpToTheLimit() {
        assertEquals(30f, hiddenAfterScroll(0f, 100f, -30f), 0f)
        assertEquals(100f, hiddenAfterScroll(90f, 100f, -50f), 0f)
    }

    @Test fun scrollingUpShowsAgainAndStopsAtZero() {
        assertEquals(20f, hiddenAfterScroll(50f, 100f, 30f), 0f)
        assertEquals(0f, hiddenAfterScroll(10f, 100f, 50f), 0f)
    }

    @Test fun noLimitMeansNothingHides() {
        assertEquals(0f, hiddenAfterScroll(0f, 0f, -40f), 0f)
    }
}
