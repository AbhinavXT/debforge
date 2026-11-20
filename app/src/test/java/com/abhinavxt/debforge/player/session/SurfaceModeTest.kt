package com.abhinavxt.debforge.player.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SurfaceModeTest {
    @Test
    fun fullHasEveryGestureAndOverlay() {
        val f = SurfaceFeatures.of(SurfaceMode.FULL)
        assertTrue(f.levels && f.scrub && f.holdSpeed && f.pinchZoom)
        assertTrue(f.doubleTaps && f.tapToggle && f.overlays && f.cards)
    }

    @Test
    fun inlineKeepsTapsButNoDragsHoldOrPinch() {
        val f = SurfaceFeatures.of(SurfaceMode.INLINE)
        assertFalse(f.levels)
        assertFalse(f.scrub)
        assertFalse(f.holdSpeed)
        assertFalse(f.pinchZoom)
        assertTrue(f.doubleTaps && f.tapToggle && f.overlays && f.cards)
        assertTrue(f.hostDrag)
        assertFalse(SurfaceFeatures.of(SurfaceMode.FULL).hostDrag)
    }

    @Test
    fun miniHasNothing() {
        val f = SurfaceFeatures.of(SurfaceMode.MINI)
        assertEquals(
            SurfaceFeatures(false, false, false, false, false, false, false, false),
            f
        )
    }
}
