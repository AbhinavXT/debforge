package com.abhinavxt.debforge.player

import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationButtonsTest {
    @Test fun skipIsThePairAndDefault() {
        assertEquals(NotificationButtons.SKIP, notificationButtonsOf(null))
        assertEquals(NotificationButtons.SKIP, notificationButtonsOf("junk"))
        assertEquals(listOf(ExtraButton.SKIP_BACK, ExtraButton.SKIP_FORWARD), extraButtons(NotificationButtons.SKIP, false))
    }

    @Test fun favouriteHiddenWhenNotAllowed() {
        assertEquals(listOf(ExtraButton.FAVOURITE, ExtraButton.SPEED), extraButtons(NotificationButtons.FAVOURITE_SPEED, true))
        assertEquals(listOf(ExtraButton.SLEEP), extraButtons(NotificationButtons.FAVOURITE_SLEEP, false))
        assertEquals(listOf(ExtraButton.SLEEP, ExtraButton.SPEED), extraButtons(NotificationButtons.SLEEP_SPEED, false))
    }

    @Test fun sleepCycles() {
        assertEquals(15, nextSleepStep(false, 0))
        assertEquals(30, nextSleepStep(true, 15))
        assertEquals(60, nextSleepStep(true, 30))
        assertEquals(0, nextSleepStep(true, 60))
        assertEquals(0, nextSleepStep(true, SLEEP_END_OF_TRACK))
    }

    @Test fun speedCycles() {
        assertEquals(1.25f, nextSpeed(1f))
        assertEquals(1.5f, nextSpeed(1.25f))
        assertEquals(2f, nextSpeed(1.5f))
        assertEquals(1f, nextSpeed(2f))
        assertEquals(1.5f, nextSpeed(1.3f))
        assertEquals(1f, nextSpeed(3f))
    }

    @Test fun swipeDecision() {
        assertEquals(SwipeAction.KEEP_PLAYING, swipeAction(isAudio = true, keepPlaying = true, playing = true))
        assertEquals(SwipeAction.STOP, swipeAction(true, false, true))
        assertEquals(SwipeAction.STOP, swipeAction(true, true, false))
        assertEquals(SwipeAction.STOP, swipeAction(false, true, true))
    }

    @Test fun videoSkipNeedsSeekableNonLive() {
        assertEquals(true, canSkipVideo(isLive = false, seekable = true))
        assertEquals(false, canSkipVideo(isLive = true, seekable = true))
        assertEquals(false, canSkipVideo(isLive = false, seekable = false))
    }
}
