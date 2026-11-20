package com.abhinavxt.debforge.ui.video

import com.abhinavxt.debforge.ui.video.VideoMode.FULL
import com.abhinavxt.debforge.ui.video.VideoMode.MINI
import com.abhinavxt.debforge.ui.video.VideoMode.WATCH
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoModeTest {
    private fun next(mode: VideoMode?, e: VideoModeEvent, tablet: Boolean = false) = nextMode(mode, e, tablet)

    @Test fun openStartsOnTheWatchPageOrFullScreen() {
        assertEquals(WATCH, next(null, VideoModeEvent.Open()))
        assertEquals(FULL, next(null, VideoModeEvent.Open(startFull = true)))
    }

    @Test fun openingAnotherVideoReplacesWhatIsShown() {
        assertEquals(WATCH, next(MINI, VideoModeEvent.Open()))
        assertEquals(WATCH, next(FULL, VideoModeEvent.Open()))
        assertEquals(FULL, next(MINI, VideoModeEvent.Open(startFull = true)))
    }

    @Test fun backWalksDownFullWatchMini() {
        assertEquals(WATCH, next(FULL, VideoModeEvent.Back))
        assertEquals(MINI, next(WATCH, VideoModeEvent.Back))
        assertEquals(MINI, next(MINI, VideoModeEvent.Back))
    }

    @Test fun backIsOursOnlyInFullScreenAndOnTheWatchPage() {
        assertTrue(backBelongsToVideo(FULL))
        assertTrue(backBelongsToVideo(WATCH))
        assertFalse(backBelongsToVideo(MINI))
        assertFalse(backBelongsToVideo(null))
    }

    @Test fun minimiseAndExpand() {
        assertEquals(MINI, next(WATCH, VideoModeEvent.Minimise))
        assertEquals(WATCH, next(MINI, VideoModeEvent.Expand))
        assertEquals(WATCH, next(WATCH, VideoModeEvent.Expand))
        assertEquals(FULL, next(FULL, VideoModeEvent.Expand))
    }

    @Test fun fullScreenOnlyFromTheWatchPage() {
        assertEquals(FULL, next(WATCH, VideoModeEvent.EnterFull))
        assertEquals(MINI, next(MINI, VideoModeEvent.EnterFull))
        assertEquals(WATCH, next(FULL, VideoModeEvent.ExitFull))
        assertEquals(MINI, next(MINI, VideoModeEvent.ExitFull))
    }

    @Test fun closeAlwaysEnds() {
        for (m in listOf(WATCH, FULL, MINI)) assertNull(next(m, VideoModeEvent.Close))
        assertNull(next(null, VideoModeEvent.Close))
    }

    @Test fun nothingHappensWithoutAVideo() {
        for (e in listOf(VideoModeEvent.Minimise, VideoModeEvent.Expand, VideoModeEvent.EnterFull, VideoModeEvent.ExitFull, VideoModeEvent.Back, VideoModeEvent.Rotated(true))) {
            assertNull(next(null, e))
        }
    }

    @Test fun phonesFollowRotationTabletsNever() {
        assertEquals(FULL, next(WATCH, VideoModeEvent.Rotated(landscape = true)))
        assertEquals(WATCH, next(FULL, VideoModeEvent.Rotated(landscape = false)))
        assertEquals(MINI, next(MINI, VideoModeEvent.Rotated(landscape = true)))
        assertEquals(WATCH, next(WATCH, VideoModeEvent.Rotated(landscape = true), tablet = true))
        assertEquals(FULL, next(FULL, VideoModeEvent.Rotated(landscape = false), tablet = true))
    }

    @Test fun kindFollowsWhereTheVideoComes() {
        assertEquals(VideoKind.YOUTUBE, kindOf(true, "https"))
        assertEquals(VideoKind.DOWNLOAD, kindOf(false, "content"))
        assertEquals(VideoKind.DOWNLOAD, kindOf(false, "file"))
        assertEquals(VideoKind.DEBRID, kindOf(false, "https"))
        assertEquals(VideoKind.DEBRID, kindOf(false, null))
    }
}
