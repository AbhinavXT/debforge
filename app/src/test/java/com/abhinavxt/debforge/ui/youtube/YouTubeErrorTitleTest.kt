package com.abhinavxt.debforge.ui.youtube

import com.abhinavxt.debforge.R
import org.junit.Assert.assertEquals
import org.junit.Test

class YouTubeErrorTitleTest {
    @Test fun searchFailureHasItsOwnHeading() {
        assertEquals(R.string.yt_err_search_title, errorTitle(YouTubeErrorKind.NETWORK, search = true))
        assertEquals(R.string.yt_err_title, errorTitle(YouTubeErrorKind.NETWORK))
        assertEquals(R.string.yt_err_playlist_title, errorTitle(YouTubeErrorKind.PLAYLIST, search = true))
    }

    @Test fun channelFailureHasItsOwnHeading() {
        assertEquals(R.string.yt_err_channel_title, errorTitle(YouTubeErrorKind.NETWORK, search = true, channel = true))
        assertEquals(R.string.yt_err_playlist_title, errorTitle(YouTubeErrorKind.PLAYLIST, channel = true))
    }

    @Test fun downloadOutcomeReflectsWhatHappened() {
        val q = YouTubeEvent.QUEUED; val a = YouTubeEvent.ALREADY_QUEUED; val f = YouTubeEvent.QUEUE_FAILED
        assertEquals(q, downloadOutcome(q, null))
        assertEquals(q, downloadOutcome(q, q))
        assertEquals(q, downloadOutcome(q, a))
        assertEquals(YouTubeEvent.QUEUED_NO_SUBTITLE, downloadOutcome(q, f))
        assertEquals(a, downloadOutcome(a, null))
        assertEquals(a, downloadOutcome(a, a))
        assertEquals(YouTubeEvent.SUBTITLE_ADDED, downloadOutcome(a, q))
        assertEquals(f, downloadOutcome(a, f))
        assertEquals(f, downloadOutcome(f, q))
    }
}
