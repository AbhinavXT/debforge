package com.abhinavxt.debforge.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoParkTest {
    @Test fun parkDecision() {
        assertEquals(ParkAction.NONE, parkAction(queueEmpty = true, restoredPending = false))
        assertEquals(ParkAction.CANCEL_ACTIVATION, parkAction(queueEmpty = false, restoredPending = true))
        assertEquals(ParkAction.PARK, parkAction(queueEmpty = false, restoredPending = false))
    }

    @Test fun stopWaitsForAPendingForegroundStart() {
        assertEquals(StopAction.DEFER, stopAction(mayStopNow = false, serviceRunning = true))
        assertEquals(StopAction.DEFER, stopAction(mayStopNow = false, serviceRunning = false))
    }

    @Test fun aRunningServiceStopsItselfAfterAnsweringAForegroundStart() {
        assertEquals(StopAction.STOP_FROM_SERVICE, stopAction(mayStopNow = true, serviceRunning = true))
        assertEquals(StopAction.STOP_SERVICE, stopAction(mayStopNow = true, serviceRunning = false))
    }

    @Test fun sameDownloadMatches() {
        assertEquals(true, videoMatchesTrack("d1", null, "d1", null))
    }

    @Test fun streamedAndLocalCopyOfOneYouTubeVideoMatch() {
        assertEquals(true, videoMatchesTrack("ytaudio:abc", "abc", "d9", "abc"))
        assertEquals(true, videoMatchesTrack("d9", "abc", "ytstream:abc", "abc"))
    }

    @Test fun unrelatedVideoDoesNotMatch() {
        assertEquals(false, videoMatchesTrack("d1", null, "d2", null))
        assertEquals(false, videoMatchesTrack("ytaudio:abc", "abc", "ytstream:xyz", "xyz"))
        assertEquals(false, videoMatchesTrack("d1", null, "d2", "xyz"))
    }

    @Test fun parkedPositionFollowsAMatchingVideo() {
        assertEquals(235_000L, parkedPositionAfterVideo(true, 235_000, 600_000))
        assertEquals(0L, parkedPositionAfterVideo(true, 0, 0))
    }

    @Test fun parkedPositionUntouchedOtherwise() {
        assertNull(parkedPositionAfterVideo(false, 235_000, 600_000))
        assertNull(parkedPositionAfterVideo(true, 599_500, 600_000)) // video ended
        assertNull(parkedPositionAfterVideo(true, -1, 600_000))
    }
}
