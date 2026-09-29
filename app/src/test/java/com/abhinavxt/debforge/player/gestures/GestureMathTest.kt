package com.abhinavxt.debforge.player.gestures

import org.junit.Assert.assertEquals
import org.junit.Test

class GestureMathTest {

    @Test fun slideThroughSpeeds() {
        assertEquals(2f, GestureMath.speedAfterSlide(0), 0f)
        assertEquals(2.5f, GestureMath.speedAfterSlide(1), 0f)
        assertEquals(4f, GestureMath.speedAfterSlide(10), 0f)
        assertEquals(1.5f, GestureMath.speedAfterSlide(-1), 0f)
        assertEquals(0.25f, GestureMath.speedAfterSlide(-50), 0f)
    }

    @Test fun seekTargetsStayInsideTheVideo() {
        assertEquals(40_000L, GestureMath.seekTarget(10_000, true, 30, 60_000))
        assertEquals(60_000L, GestureMath.seekTarget(50_000, true, 30, 60_000))
        assertEquals(0L, GestureMath.seekTarget(5_000, false, 10, 60_000))
        assertEquals(70_000L, GestureMath.seekTarget(40_000, true, 30, null)) // unknown length
    }

    @Test fun scrubFullWidthIs90Seconds() {
        assertEquals(100_000L, GestureMath.scrubTarget(10_000, 1f, null))
        assertEquals(0L, GestureMath.scrubTarget(10_000, -0.5f, 3_600_000))
        assertEquals(55_000L, GestureMath.scrubTarget(10_000, 0.5f, 3_600_000))
    }

    @Test fun zoomClampsAndSnaps() {
        assertEquals(1.5f, GestureMath.zoom(1f, 1.5f), 0.001f)
        assertEquals(4f, GestureMath.zoom(3f, 2f), 0f)
        assertEquals(1f, GestureMath.zoom(1f, 0.5f), 0f)
        assertEquals(1f, GestureMath.snapZoom(1.03f), 0f)
        assertEquals(1.2f, GestureMath.snapZoom(1.2f), 0f)
    }

    @Test fun levels() {
        assertEquals(0.6f, GestureMath.level(0.5f, 0.1f), 0.0001f)
        assertEquals(1f, GestureMath.level(0.95f, 0.2f), 0f)
        assertEquals(0.01f, GestureMath.level(0.05f, -0.3f, min = 0.01f), 0f)
    }

    @Test fun labels() {
        assertEquals("1:02:03", GestureMath.time(3_723_000))
        assertEquals("0:05", GestureMath.time(5_400))
        assertEquals("+1:10", GestureMath.delta(70_000))
        assertEquals("−0:05", GestureMath.delta(-5_000))
        assertEquals("2×", GestureMath.speedLabel(2f))
        assertEquals("1.5×", GestureMath.speedLabel(1.5f))
        assertEquals("0.25×", GestureMath.speedLabel(0.25f))
    }

    @Test fun volumeBoost() {
        assertEquals(0, GestureMath.boostGainMb(0.7f))
        assertEquals(0, GestureMath.boostGainMb(1f))
        assertEquals(352, GestureMath.boostGainMb(1.5f))
        assertEquals(602, GestureMath.boostGainMb(2f))
        assertEquals(602, GestureMath.boostGainMb(3f))
    }
}
