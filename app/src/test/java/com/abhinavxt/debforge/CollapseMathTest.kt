package com.abhinavxt.debforge

import com.abhinavxt.debforge.ui.browse.CollapseMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CollapseMathTest {
    @Test fun collapsingConsumesNegativeDy() {
        val s = CollapseMath.step(collapsed = 0f, maxCollapse = 100f, dy = -10f)
        assertEquals(10f, s.collapsed, 0f)
        assertEquals(-10f, s.consumed, 0f)
    }

    @Test fun collapsingStopsAtLimit() {
        val s = CollapseMath.step(95f, 100f, -30f)
        assertEquals(100f, s.collapsed, 0f)
        assertEquals(-5f, s.consumed, 0f)
    }

    @Test fun expandingConsumesOnlyWhatIsCollapsed() {
        val s = CollapseMath.step(4f, 100f, 30f)
        assertEquals(0f, s.collapsed, 0f)
        assertEquals(4f, s.consumed, 0f)
        val t = CollapseMath.step(50f, 100f, 20f)
        assertEquals(30f, t.collapsed, 0f)
        assertEquals(20f, t.consumed, 0f)
    }

    @Test fun nothingConsumedAtBounds() {
        assertEquals(0f, CollapseMath.step(0f, 100f, 25f).consumed, 0f)
        assertEquals(0f, CollapseMath.step(100f, 100f, -25f).consumed, 0f)
        assertEquals(0f, CollapseMath.step(0f, 0f, -25f).consumed, 0f)
    }

    @Test fun cannotCollapseWhenListCannotScroll() {
        val s = CollapseMath.step(0f, 100f, -20f, canScroll = false)
        assertEquals(0f, s.collapsed, 0f)
        assertEquals(0f, s.consumed, 0f)
        // an expanding drag still works
        assertEquals(10f, CollapseMath.step(10f, 100f, 20f, canScroll = false).consumed, 0f)
    }

    @Test fun consumedNeverExceedsDyAndKeepsItsSign() {
        for (collapsed in listOf(0f, 3f, 50f, 99f, 100f)) {
            for (dy in listOf(-200f, -50f, -1f, 0f, 1f, 7f, 50f, 200f)) {
                val s = CollapseMath.step(collapsed, 100f, dy)
                assertTrue(kotlin.math.abs(s.consumed) <= kotlin.math.abs(dy))
                assertTrue(s.consumed * dy >= 0f)
                assertTrue(s.collapsed in 0f..100f)
            }
        }
    }
}
