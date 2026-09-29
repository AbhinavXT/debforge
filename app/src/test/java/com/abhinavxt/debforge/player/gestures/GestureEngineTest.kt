package com.abhinavxt.debforge.player.gestures

import com.abhinavxt.debforge.player.gestures.GestureEngine.Side
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureEngineTest {

    /** 1000 x 500 px screen at density 1 (dp = px). */
    private class Rec : GestureEngine.Callbacks {
        val log = mutableListOf<String>()
        var acceptLongPress = true
        override fun onSingleTap() { log += "tap" }
        override fun onCenterDoubleTap() { log += "center2" }
        override fun onSeekTaps(forward: Boolean, seconds: Int) { log += (if (forward) "+" else "-") + seconds }
        override fun onSeekTapsEnd() { log += "seekEnd" }
        override fun onVerticalStart(side: Side) { log += "v:$side" }
        override fun onVertical(side: Side, delta: Float) { log += "dv:%.2f".format(delta) }
        override fun onVerticalEnd(side: Side) { log += "vEnd" }
        override fun onScrubStart() { log += "scrub" }
        override fun onScrub(fraction: Float) { log += "s:%.2f".format(fraction) }
        override fun onScrubEnd() { log += "scrubEnd" }
        override fun onScrubCancel() { log += "scrubCancel" }
        override fun onLongPressStart(): Boolean { log += "long"; return acceptLongPress }
        override fun onLongPressSlide(steps: Int) { log += "slide:$steps" }
        override fun onLongPressEnd() { log += "longEnd" }
        override fun onPinchStart() { log += "pinch" }
        override fun onPinch(scale: Float) { log += "p:%.1f".format(scale) }
        override fun onPinchEnd() { log += "pinchEnd" }
    }

    private val rec = Rec()
    private val g = GestureEngine(GestureEngine.Config(1000f, 500f, 1f), rec)

    private fun tap(x: Float, t: Long, y: Float = 250f) {
        g.down(x, y, t)
        g.up(x, y, t + 40)
    }

    /** Fires the timer if it's due by [t], like the host's Handler would. */
    private fun clock(t: Long) {
        val d = g.deadline ?: return
        if (d <= t) g.timer(t)
    }

    @Test fun singleTapFiresAfterTheDoubleTapWindow() {
        tap(500f, 0)
        clock(100)
        assertEquals(emptyList<String>(), rec.log)
        clock(300)
        assertEquals(listOf("tap"), rec.log)
    }

    @Test fun doubleTapRightSeeksAndMoreTapsAccumulate() {
        tap(900f, 0)
        tap(900f, 150)
        tap(900f, 500)
        tap(900f, 900)
        clock(2000)
        assertEquals(listOf("+10", "+20", "+30", "seekEnd"), rec.log)
    }

    @Test fun doubleTapLeftSeeksBack() {
        tap(100f, 0)
        tap(100f, 150)
        clock(1000)
        assertEquals(listOf("-10", "seekEnd"), rec.log)
    }

    @Test fun switchingSidesRestartsTheCount() {
        tap(900f, 0); tap(900f, 150)
        tap(100f, 400)
        assertEquals(listOf("+10", "-10"), rec.log)
    }

    @Test fun tapsTooSlowAreSeparateTaps() {
        tap(900f, 0)
        clock(300)
        tap(900f, 600)
        clock(900)
        assertEquals(listOf("tap", "tap"), rec.log)
    }

    @Test fun centerDoubleTapTogglesPlay() {
        tap(500f, 0)
        tap(510f, 150)
        clock(1000)
        assertEquals(listOf("center2"), rec.log)
    }

    @Test fun afterARunEndsTapsAreNormalAgain() {
        tap(900f, 0); tap(900f, 150)
        clock(900) // run ended
        tap(900f, 1000)
        clock(1300)
        assertEquals(listOf("+10", "seekEnd", "tap"), rec.log)
    }

    @Test fun verticalDragRightIsVolume() {
        g.down(800f, 300f, 0)
        g.move(800f, 250f, 10)
        g.move(800f, 200f, 20)
        g.up(800f, 200f, 30)
        assertEquals(listOf("v:RIGHT", "dv:0.10", "dv:0.10", "vEnd"), rec.log)
    }

    @Test fun verticalDragLeftIsBrightness() {
        g.down(200f, 200f, 0)
        g.move(200f, 300f, 10)
        g.up(200f, 300f, 20)
        assertEquals(listOf("v:LEFT", "dv:-0.20", "vEnd"), rec.log)
    }

    @Test fun horizontalDragScrubs() {
        g.down(400f, 250f, 0)
        g.move(450f, 252f, 10)
        g.move(600f, 255f, 20)
        g.up(600f, 255f, 30)
        assertEquals(listOf("scrub", "s:0.05", "s:0.20", "scrubEnd"), rec.log)
    }

    @Test fun edgeDragsAreLeftToTheSystem() {
        g.down(500f, 10f, 0) // top edge: notification shade
        g.move(500f, 200f, 10)
        g.up(500f, 200f, 20)
        g.down(5f, 250f, 100) // left edge: back gesture
        g.move(200f, 250f, 110)
        g.up(200f, 250f, 120)
        clock(2000)
        assertEquals(emptyList<String>(), rec.log)
    }

    @Test fun longPressThenSlidePicksSpeed() {
        g.down(500f, 250f, 0)
        clock(600)
        g.move(545f, 250f, 700) // 45dp: one step
        g.move(590f, 250f, 800) // 90dp: two
        g.move(590f, 251f, 810) // unchanged
        g.up(590f, 250f, 900)
        clock(5000)
        assertEquals(listOf("long", "slide:1", "slide:2", "longEnd"), rec.log)
    }

    @Test fun declinedLongPressDoesNothingOnRelease() {
        rec.acceptLongPress = false
        g.down(500f, 250f, 0)
        clock(600)
        g.up(500f, 250f, 700)
        clock(5000)
        assertEquals(listOf("long"), rec.log)
    }

    @Test fun pinchZoomsAndCancelsAScrub() {
        g.down(400f, 250f, 0)
        g.move(500f, 250f, 10) // scrub started
        g.pointerDown(2, 200f, 20)
        g.move(500f, 250f, 30, pointers = 2, span = 300f)
        g.pointerUp(1, 40)
        g.up(500f, 250f, 50)
        clock(5000)
        assertEquals(listOf("scrub", "s:0.10", "scrubCancel", "pinch", "p:1.5", "pinchEnd"), rec.log)
    }

    @Test fun cancelledScrubGoesBack() {
        g.down(400f, 250f, 0)
        g.move(500f, 250f, 10)
        g.cancel(20)
        assertTrue(rec.log.last() == "scrubCancel")
    }

    @Test fun deadlineCoversLongPressWhilePressed() {
        g.down(500f, 250f, 1000)
        assertEquals(1500L, g.deadline)
    }
}
