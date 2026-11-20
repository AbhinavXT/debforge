package com.abhinavxt.debforge.ui.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoPipTest {

    private fun enter(
        supported: Boolean = true, allowed: Boolean = true, shown: Boolean = true,
        playing: Boolean = true, web: Boolean = false,
    ) = shouldEnterPip(supported, allowed, shown, playing, web)

    @Test fun `enters while a shown video plays`() = assertTrue(enter())

    @Test fun `never without a video`() = assertFalse(enter(shown = false))

    @Test fun `never while paused`() = assertFalse(enter(playing = false))

    @Test fun `never when the setting or the device says no`() {
        assertFalse(enter(allowed = false))
        assertFalse(enter(supported = false))
    }

    @Test fun `never over a web page full screen video`() = assertFalse(enter(web = true))

    @Test fun `background handover waits out finishing and the floating window`() {
        assertTrue(com.abhinavxt.debforge.player.session.mayHandOver(mayLeave = true, inPictureInPicture = false))
        assertFalse(com.abhinavxt.debforge.player.session.mayHandOver(mayLeave = false, inPictureInPicture = false))
        assertFalse(com.abhinavxt.debforge.player.session.mayHandOver(mayLeave = true, inPictureInPicture = true))
    }

    @Test fun `expanding the window opens the watch page from the mini bar only`() {
        assertEquals(VideoMode.WATCH, modeAfterPipExpand(VideoMode.MINI))
        assertEquals(VideoMode.WATCH, modeAfterPipExpand(VideoMode.WATCH))
        assertEquals(VideoMode.FULL, modeAfterPipExpand(VideoMode.FULL))
    }

    @Test fun leaveHintIgnoresTheAppsOwnActivities() {
        assertTrue(pipOnLeaveHint(30, canEnter = true, ownActivityStarting = false))
        assertFalse(pipOnLeaveHint(30, canEnter = true, ownActivityStarting = true))
        assertFalse(pipOnLeaveHint(30, canEnter = false, ownActivityStarting = false))
        assertFalse(pipOnLeaveHint(31, canEnter = true, ownActivityStarting = false)) // 12+ enters by itself
    }
}
