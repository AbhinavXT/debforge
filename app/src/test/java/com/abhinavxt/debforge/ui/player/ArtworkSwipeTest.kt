package com.abhinavxt.debforge.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtworkSwipeTest {
    @Test fun axisLock() {
        assertTrue(isHorizontalDrag(10f, 3f))
        assertFalse(isHorizontalDrag(3f, 10f))
        assertFalse(isHorizontalDrag(5f, 5f))
    }

    @Test fun leftIsNextRightIsPrevious() {
        assertEquals(1, artworkSwipeDirection(-300f, 0f, 1000f, false))
        assertEquals(-1, artworkSwipeDirection(300f, 0f, 1000f, false))
    }

    @Test fun shortSlowDragSpringsBack() = assertEquals(0, artworkSwipeDirection(-100f, 200f, 1000f, false))

    @Test fun flingCommitsEvenWhenShort() {
        assertEquals(1, artworkSwipeDirection(-20f, -1500f, 1000f, false))
        assertEquals(-1, artworkSwipeDirection(20f, 1500f, 1000f, false))
    }

    @Test fun rtlMirrors() {
        assertEquals(-1, artworkSwipeDirection(-300f, 0f, 1000f, true))
        assertEquals(1, artworkSwipeDirection(300f, 0f, 1000f, true))
    }

    @Test fun badInputsNeverCommit() {
        assertEquals(0, artworkSwipeDirection(-300f, 0f, 0f, false))
        assertEquals(0, artworkSwipeDirection(Float.NaN, 0f, 1000f, false))
    }
}
