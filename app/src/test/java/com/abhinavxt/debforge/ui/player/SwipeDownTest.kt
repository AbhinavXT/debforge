package com.abhinavxt.debforge.ui.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SwipeDownTest {
    @Test fun farEnoughDismisses() = assertTrue(shouldDismissSwipe(300f, 0f, 1000f))
    @Test fun shortSlowSpringsBack() = assertFalse(shouldDismissSwipe(100f, 200f, 1000f))
    @Test fun fastFlickDismisses() = assertTrue(shouldDismissSwipe(40f, 2000f, 1000f))
    @Test fun noOffsetNeverDismisses() = assertFalse(shouldDismissSwipe(0f, 5000f, 1000f))
    @Test fun unknownHeightNeverDismisses() = assertFalse(shouldDismissSwipe(50f, 0f, 0f))

    // Device case: an 800 px slow drag on a 2400 px sheet (adb reports 0..700 px/s at release) must close.
    @Test fun slowThirdOfTheSheetDismisses() {
        assertTrue(shouldDismissSwipe(765f, 0f, 2400f))
        assertTrue(shouldDismissSwipe(765f, 700f, 2400f))
        assertTrue(shouldDismissSwipe(600f, 0f, 2400f))
    }
    @Test fun justUnderAQuarterSpringsBack() = assertFalse(shouldDismissSwipe(599f, 0f, 2400f))
    @Test fun smallUpwardWobbleAtReleaseStillDismisses() = assertTrue(shouldDismissSwipe(765f, -400f, 2400f))
    @Test fun clearUpwardFlingCancelsALongDrag() = assertFalse(shouldDismissSwipe(900f, -2000f, 2400f))
    @Test fun nonFiniteNeverDismisses() {
        assertFalse(shouldDismissSwipe(Float.NaN, 0f, 2400f))
        assertFalse(shouldDismissSwipe(900f, Float.NaN, 2400f))
        assertFalse(shouldDismissSwipe(900f, 0f, Float.POSITIVE_INFINITY))
    }

    @Test fun firedWhileClosingIsLockedUntilReopened() {
        val st = SwipeDownState {}
        assertFalse(st.locked)
        st.fire(); st.open = false
        assertTrue(st.locked)
        st.open = true
        assertFalse(st.locked)
    }

    @Test fun neighbourMemoComputesOncePerKey() {
        val memo = NeighbourMemo()
        var n = 0
        repeat(5) { memo.get(listOf(1, "a")) { n++ } }
        assertTrue(n == 1)
        memo.get(listOf(2, "a")) { n++ }
        assertTrue(n == 2)
    }
}
