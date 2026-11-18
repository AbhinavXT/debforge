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
}
