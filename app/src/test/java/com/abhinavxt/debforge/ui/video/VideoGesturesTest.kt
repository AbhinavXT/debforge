package com.abhinavxt.debforge.ui.video

import android.content.pm.ActivityInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoGesturesTest {
    @Test fun minimiseNeedsThirtyPercentOrAFling() {
        assertFalse(shouldMinimise(200f, 0f, 1000f))
        assertTrue(shouldMinimise(300f, 0f, 1000f))
        assertTrue(shouldMinimise(20f, 2000f, 1000f))
        assertFalse(shouldMinimise(600f, -2000f, 1000f))
        assertFalse(shouldMinimise(0f, 2000f, 1000f))
        assertFalse(shouldMinimise(500f, 0f, 0f))
    }

    @Test fun miniSwipeSidewaysClosesUpExpandsDownCloses() {
        assertEquals(MiniSwipe.CLOSE_RIGHT, miniSwipe(500f, 10f, 0f, 0f, 1000f, 100f, 2000f))
        assertEquals(MiniSwipe.CLOSE_LEFT, miniSwipe(-20f, 0f, -3000f, 0f, 1000f, 100f, 2000f))
        assertEquals(MiniSwipe.NONE, miniSwipe(100f, 0f, 0f, 0f, 1000f, 100f, 2000f))
        assertEquals(MiniSwipe.EXPAND, miniSwipe(0f, -150f, 0f, 0f, 1000f, 100f, 2000f))
        assertEquals(MiniSwipe.EXPAND, miniSwipe(0f, -10f, 0f, -3000f, 1000f, 100f, 2000f))
        assertEquals(MiniSwipe.CLOSE_DOWN, miniSwipe(0f, 150f, 0f, 0f, 1000f, 100f, 2000f))
        assertEquals(MiniSwipe.NONE, miniSwipe(0f, 20f, 0f, 0f, 1000f, 100f, 2000f))
    }

    private val unspecified = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

    @Test fun phoneAutoRotateGoesFullAndBack() {
        assertEquals(OrientationPlan(VideoMode.FULL, unspecified), orientationPlan(VideoMode.WATCH, 360, true, false, true))
        assertEquals(VideoMode.WATCH, orientationPlan(VideoMode.FULL, 360, true, false, false).mode)
    }

    @Test fun noChangeWhenAutoRotateOffTabletPortraitVideoOrMini() {
        assertEquals(VideoMode.WATCH, orientationPlan(VideoMode.WATCH, 360, false, false, true).mode)
        assertEquals(VideoMode.WATCH, orientationPlan(VideoMode.WATCH, 800, true, false, true).mode)
        assertEquals(VideoMode.WATCH, orientationPlan(VideoMode.WATCH, 360, true, true, true).mode)
        assertEquals(VideoMode.FULL, orientationPlan(VideoMode.FULL, 360, false, false, false).mode)
        assertEquals(VideoMode.MINI, orientationPlan(VideoMode.MINI, 360, true, false, true).mode)
    }

    @Test fun locksHoldUntilTheSensorAgrees() {
        val landscape = orientationPlan(VideoMode.FULL, 360, true, false, false, OrientationLock.LANDSCAPE)
        assertEquals(OrientationPlan(VideoMode.FULL, ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE), landscape)
        val portrait = orientationPlan(VideoMode.WATCH, 360, true, false, true, OrientationLock.PORTRAIT)
        assertEquals(OrientationPlan(VideoMode.WATCH, ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT), portrait)
        assertEquals(OrientationLock.NONE, lockAfterSensor(OrientationLock.PORTRAIT, Hold.PORTRAIT, false))
        assertEquals(OrientationLock.PORTRAIT, lockAfterSensor(OrientationLock.PORTRAIT, Hold.LANDSCAPE, true))
        assertEquals(OrientationLock.NONE, lockAfterSensor(OrientationLock.LANDSCAPE, Hold.LANDSCAPE, true))
        assertEquals(OrientationLock.LANDSCAPE, lockAfterSensor(OrientationLock.LANDSCAPE, Hold.LANDSCAPE, false))
    }

    @Test fun enterAndExitLocks() {
        assertEquals(OrientationLock.LANDSCAPE, lockOnEnterFull(false, false))
        assertEquals(OrientationLock.NONE, lockOnEnterFull(true, false))
        assertEquals(OrientationLock.NONE, lockOnEnterFull(false, true))
        assertEquals(OrientationLock.PORTRAIT, lockOnExitFull(360, true))
        assertEquals(OrientationLock.NONE, lockOnExitFull(800, true))
        assertEquals(OrientationLock.NONE, lockOnExitFull(360, false))
        assertEquals(Hold.LANDSCAPE, holdOf(90)); assertEquals(Hold.PORTRAIT, holdOf(180)); assertEquals(Hold.OTHER, holdOf(-1))
    }

    @Test fun rotateFromMoreTurnsTheOtherWayAndStaysFull() {
        assertEquals(OrientationLock.TURNED_PORTRAIT, lockOnRotateScreen(landscapeNow = true))
        assertEquals(OrientationLock.TURNED_LANDSCAPE, lockOnRotateScreen(landscapeNow = false))
        // Held upright in full screen: no return to the watch page, even with auto-rotate on.
        val upright = orientationPlan(VideoMode.FULL, 360, true, false, false, OrientationLock.TURNED_PORTRAIT)
        assertEquals(OrientationPlan(VideoMode.FULL, ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT), upright)
        // Sideways asked for even with auto-rotate off or a portrait picture.
        val sideways = orientationPlan(VideoMode.FULL, 360, false, true, false, OrientationLock.TURNED_LANDSCAPE)
        assertEquals(OrientationPlan(VideoMode.FULL, ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE), sideways)
        // The sensor does not end it; leaving full screen does (lockOnExitFull replaces it).
        assertEquals(OrientationLock.TURNED_PORTRAIT, lockAfterSensor(OrientationLock.TURNED_PORTRAIT, Hold.PORTRAIT, true))
        assertEquals(OrientationLock.TURNED_LANDSCAPE, lockAfterSensor(OrientationLock.TURNED_LANDSCAPE, Hold.LANDSCAPE, true))
    }

    @Test fun relatedResultOnlyOpensForTheVideoItWasAskedFrom() {
        assertTrue(relatedStillWanted(from = 7, now = 7))
        assertFalse(relatedStillWanted(from = 7, now = null)) // closed meanwhile
        assertFalse(relatedStillWanted(from = 7, now = 8)) // another video opened meanwhile
    }

    @Test fun openingStraightInFullTakesTheButtonsLock() {
        // Portrait phone, landscape (or not yet known) picture: held sideways like the full screen button.
        assertEquals(OrientationLock.LANDSCAPE, lockOnOpen(VideoMode.FULL, videoPortrait = false, landscapeNow = false))
        assertEquals(OrientationLock.NONE, lockOnOpen(VideoMode.FULL, videoPortrait = true, landscapeNow = false))
        assertEquals(OrientationLock.NONE, lockOnOpen(VideoMode.FULL, videoPortrait = false, landscapeNow = true))
        assertEquals(OrientationLock.NONE, lockOnOpen(VideoMode.WATCH, videoPortrait = false, landscapeNow = false))
        // With that lock, the portrait phone does not drop back to the watch page, and turns sideways once the size is known.
        val lock = lockOnOpen(VideoMode.FULL, false, false)
        assertEquals(OrientationPlan(VideoMode.FULL, ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED),
            orientationPlan(VideoMode.FULL, 360, autoRotate = true, videoPortrait = false, landscape = false, lock = lock, sizeKnown = false))
        assertEquals(OrientationPlan(VideoMode.FULL, ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE),
            orientationPlan(VideoMode.FULL, 360, autoRotate = false, videoPortrait = false, landscape = false, lock = lock))
        // A tablet too.
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
            orientationPlan(VideoMode.FULL, 800, autoRotate = true, videoPortrait = false, landscape = false, lock = lock).requested)
    }

    @Test fun autoRotateOffInSensorFullKeepsLandscape() {
        assertEquals(OrientationLock.LANDSCAPE, lockOnAutoRotateOff(OrientationLock.NONE, VideoMode.FULL, videoPortrait = false))
        assertEquals(OrientationLock.NONE, lockOnAutoRotateOff(OrientationLock.NONE, VideoMode.FULL, videoPortrait = true))
        assertEquals(OrientationLock.NONE, lockOnAutoRotateOff(OrientationLock.NONE, VideoMode.WATCH, videoPortrait = false))
        assertEquals(OrientationLock.TURNED_PORTRAIT, lockOnAutoRotateOff(OrientationLock.TURNED_PORTRAIT, VideoMode.FULL, false))
        assertEquals(OrientationLock.PORTRAIT, lockOnAutoRotateOff(OrientationLock.PORTRAIT, VideoMode.WATCH, false))
    }

    @Test fun noOrientationRulesInOrJustOutOfTheFloatingWindow() {
        assertTrue(orientationRulesApply(pip = false, settlingAfterPip = false))
        assertFalse(orientationRulesApply(pip = true, settlingAfterPip = false))
        assertFalse(orientationRulesApply(pip = false, settlingAfterPip = true))
    }
}
