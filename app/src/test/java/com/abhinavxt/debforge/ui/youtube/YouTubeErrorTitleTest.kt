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
}
