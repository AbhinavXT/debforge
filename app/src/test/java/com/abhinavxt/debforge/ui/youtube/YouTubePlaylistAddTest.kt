package com.abhinavxt.debforge.ui.youtube

import com.abhinavxt.debforge.R
import com.abhinavxt.debforge.data.playback.YouTubeAddResult
import com.abhinavxt.debforge.data.playback.YouTubeMode
import com.abhinavxt.debforge.domain.YouTubeSearchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubePlaylistAddTest {
    private fun result(url: String, channel: String? = "Chan", dur: Long = 90) = YouTubeSearchResult(url, "Title", channel, dur, null)

    @Test fun searchRowDefaultsToAudioAndCarriesMetadata() {
        val r = result("https://www.youtube.com/watch?v=dQw4w9WgXcQ").toAddRequest()!!
        assertEquals("dQw4w9WgXcQ", r.videoId)
        assertTrue(r.audio)
        assertEquals("Chan", r.meta.channel)
        assertEquals(90, r.meta.durationSec)
    }

    @Test fun blankChannelAndZeroDurationBecomeNull() {
        val r = result("https://youtu.be/dQw4w9WgXcQ", channel = " ", dur = 0).toAddRequest()!!
        assertNull(r.meta.channel)
        assertNull(r.meta.durationSec)
    }

    @Test fun nonVideoUrlGivesNoRequest() = assertNull(result("https://example.com/x").toAddRequest())

    @Test fun modeMapping() {
        assertEquals(YouTubeMode.AUDIO, playlistAddMode(true))
        assertEquals(YouTubeMode.VIDEO, playlistAddMode(false))
    }

    @Test fun everyResultHasAMessage() {
        assertEquals(R.string.yt_pl_added, playlistAddMessage(YouTubeAddResult.ADDED))
        assertEquals(R.string.yt_pl_already, playlistAddMessage(YouTubeAddResult.ALREADY_IN_LIST))
        assertEquals(R.string.yt_pl_limit, playlistAddMessage(YouTubeAddResult.LIMIT_REACHED))
        assertEquals(R.string.yt_pl_failed, playlistAddMessage(YouTubeAddResult.NO_SUCH_LIST))
        assertEquals(R.string.yt_pl_failed, playlistAddMessage(YouTubeAddResult.INVALID_ID))
    }
}
