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

    @Test fun miniBarFollowsDownFullyAndUpWithResistance() {
        assertEquals(100f, miniDragY(100f), 0f)
        assertEquals(-35f, miniDragY(-100f), 0.001f)
        assertEquals(0f, miniDragY(Float.NaN), 0f)
    }

    @Test fun miniBarAxisAndSlop() {
        assertTrue(miniSwipeHorizontal(30f, -10f))
        assertFalse(miniSwipeHorizontal(5f, 30f))
        assertEquals(12f, pastSlop(20f, 8f), 0f)
        assertEquals(-12f, pastSlop(-20f, 8f), 0f)
        assertEquals(0f, pastSlop(5f, 8f), 0f)
        assertEquals(0f, pastSlop(Float.NaN, 8f), 0f)
    }

    @Test fun miniBarFadesWithDistance() {
        assertEquals(1f, miniSwipeAlpha(0f, 0f, 1000f, 100f), 0f)
        assertEquals(0.5f, miniSwipeAlpha(500f, 0f, 1000f, 100f), 0.001f)
        assertEquals(0.5f, miniSwipeAlpha(-500f, 0f, 1000f, 100f), 0.001f)
        assertEquals(0f, miniSwipeAlpha(0f, 150f, 1000f, 100f), 0.001f)
        assertEquals(1f, miniSwipeAlpha(0f, -80f, 1000f, 100f), 0f)
        assertEquals(0f, miniSwipeAlpha(2000f, 0f, 1000f, 100f), 0f)
        assertEquals(1f, miniSwipeAlpha(10f, 10f, 0f, 0f), 0f)
    }

    @Test fun committedCloseSlidesOutTheWayItWasThrown() {
        assertEquals(-1000f to 0f, miniExitTarget(MiniSwipe.CLOSE_LEFT, 1000f, 100f))
        assertEquals(1000f to 0f, miniExitTarget(MiniSwipe.CLOSE_RIGHT, 1000f, 100f))
        assertEquals(0f to 150f, miniExitTarget(MiniSwipe.CLOSE_DOWN, 1000f, 100f))
        assertEquals(0f to 0f, miniExitTarget(MiniSwipe.NONE, 1000f, 100f))
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

    @Test fun swipeUpEntersFullByProgressOrFling() {
        assertTrue(shouldEnterFull(progress = 0.5f, velocity = 0f))
        assertFalse(shouldEnterFull(progress = 0.1f, velocity = -200f))
        assertTrue(shouldEnterFull(progress = 0.05f, velocity = -2000f))
        assertFalse(shouldEnterFull(progress = 0.9f, velocity = 2000f))
        assertFalse(shouldEnterFull(progress = 0f, velocity = -2000f))
        assertFalse(shouldEnterFull(progress = Float.NaN, velocity = 0f))
    }

    @Test fun swipeDownLeavesFullByDistanceOrFling() {
        assertTrue(shouldLeaveFull(dy = 200f, velocity = 0f, height = 1000f))
        assertFalse(shouldLeaveFull(dy = 100f, velocity = 200f, height = 1000f))
        assertTrue(shouldLeaveFull(dy = 20f, velocity = 1500f, height = 1000f))
        assertFalse(shouldLeaveFull(dy = 400f, velocity = -1500f, height = 1000f))
        assertFalse(shouldLeaveFull(dy = 0f, velocity = 3000f, height = 1000f))
        assertFalse(shouldLeaveFull(dy = Float.NaN, velocity = 0f, height = 1000f))
        assertFalse(shouldLeaveFull(dy = 300f, velocity = 0f, height = 0f))
    }

    @Test fun leaveRectFollowsTheFingerAndShrinksALittle() {
        val full = androidx.compose.ui.geometry.Rect(0f, 0f, 400f, 800f)
        assertEquals(full, leaveFullRect(full, 0f, 800f))
        assertEquals(full, leaveFullRect(full, -50f, 800f))
        val r = leaveFullRect(full, 400f, 800f) // the whole travel
        assertEquals(400f, r.top, 0.01f)
        assertEquals(340f, r.width, 0.01f)
        assertEquals(30f, r.left, 0.01f)
        assertEquals(680f, r.height, 0.01f)
        assertEquals(r.width, leaveFullRect(full, 900f, 800f).width, 0.01f) // the shrink stops
    }

    @Test fun swipeUpProgressMapsToRectAndAlphas() {
        val watch = androidx.compose.ui.geometry.Rect(0f, 100f, 400f, 325f)
        val full = androidx.compose.ui.geometry.Rect(0f, 0f, 400f, 900f)
        assertEquals(230f, enterFullTravel(watch, full), 0.01f)
        assertEquals(0.5f, enterFullProgress(115f, 230f), 0.001f)
        assertEquals(1f, enterFullProgress(1000f, 230f), 0f)
        assertEquals(0f, enterFullProgress(-5f, 230f), 0f)
        assertEquals(watch, enterFullRect(watch, full, 0f))
        assertEquals(full, enterFullRect(watch, full, 1f))
        assertEquals(562.5f, enterFullRect(watch, full, 0.5f).height, 0.01f)
        assertEquals(1f, enterFullPageAlpha(0f), 0f)
        assertEquals(0f, enterFullPageAlpha(1f), 0f)
        assertEquals(0.25f, enterFullScrimAlpha(0.2f), 0.001f)
        assertEquals(1f, enterFullScrimAlpha(0.8f), 0f)
        assertEquals(1f, enterFullScrimAlpha(1f), 0f)
        assertEquals(0.75f, enterFullPageAlpha(0.25f), 0f)
    }

    @Test fun swipeUpSettleVelocityKeepsOnlyTheRightDirection() {
        assertEquals(2f, enterFullSettleVelocity(-460f, 230f, commit = true), 0.001f)
        assertEquals(0f, enterFullSettleVelocity(460f, 230f, commit = true), 0f)
        assertEquals(-2f, enterFullSettleVelocity(460f, 230f, commit = false), 0.001f)
        assertEquals(0f, enterFullSettleVelocity(Float.NaN, 230f, commit = true), 0f)
    }

    @Test fun swipeUpAndDownMoveBetweenWatchAndFull() {
        assertEquals(VideoMode.FULL, nextMode(VideoMode.WATCH, VideoModeEvent.EnterFull, isTablet = false))
        assertEquals(VideoMode.WATCH, nextMode(VideoMode.FULL, VideoModeEvent.Back, isTablet = false, backClosesFull = false))
        assertEquals(null, nextMode(VideoMode.FULL, VideoModeEvent.Back, isTablet = false, backClosesFull = true))
    }
}

class VelocityTowardTest {
    @org.junit.Test fun sameDirectionKept() {
        org.junit.Assert.assertEquals(800f, velocityToward(800f, 300f), 0f)
        org.junit.Assert.assertEquals(-800f, velocityToward(-800f, -300f), 0f)
    }
    @org.junit.Test fun opposingOrInvalidIsZero() {
        org.junit.Assert.assertEquals(0f, velocityToward(800f, -300f), 0f)
        org.junit.Assert.assertEquals(0f, velocityToward(Float.NaN, 300f), 0f)
        org.junit.Assert.assertEquals(0f, velocityToward(800f, 0f), 0f)
    }

    @Test fun layerForSameRectIsIdentity() {
        val r = androidx.compose.ui.geometry.Rect(0f, 100f, 1080f, 709f)
        assertEquals(PictureLayer.Identity, layerFor(r, r))
    }

    @Test fun layerForShrinksFromTheTopLeft() {
        val layout = androidx.compose.ui.geometry.Rect(0f, 100f, 1000f, 600f)
        val visual = androidx.compose.ui.geometry.Rect(200f, 400f, 700f, 650f)
        val l = layerFor(layout, visual)
        assertEquals(0.5f, l.scaleX, 0f)
        assertEquals(0.5f, l.scaleY, 0f)
        assertEquals(200f, l.translationX, 0f)
        assertEquals(300f, l.translationY, 0f)
    }

    @Test fun layerForBadInputIsIdentity() {
        val visual = androidx.compose.ui.geometry.Rect(0f, 0f, 10f, 10f)
        assertEquals(PictureLayer.Identity, layerFor(androidx.compose.ui.geometry.Rect(0f, 0f, 0f, 0f), visual))
        assertEquals(PictureLayer.Identity, layerFor(visual, androidx.compose.ui.geometry.Rect(0f, 0f, Float.NaN, 10f)))
    }

    @Test fun sizeChangeInTheSameModeSnaps() {
        assertTrue(shouldSnapRect(modeChanged = false, animating = false, animationsOff = false))
        assertFalse(shouldSnapRect(modeChanged = true, animating = false, animationsOff = false))
        assertFalse(shouldSnapRect(modeChanged = false, animating = true, animationsOff = false))
        assertTrue(shouldSnapRect(modeChanged = true, animating = false, animationsOff = true))
    }

    @Test fun settleVelocityKeepsOnlyTheWayToTheDestination() {
        assertEquals(900f, settleVelocity(900f, towardsMini = true), 0f)
        assertEquals(0f, settleVelocity(-900f, towardsMini = true), 0f)
        assertEquals(-900f, settleVelocity(-900f, towardsMini = false), 0f)
        assertEquals(0f, settleVelocity(900f, towardsMini = false), 0f)
        assertEquals(0f, settleVelocity(Float.NaN, towardsMini = true), 0f)
    }
}
