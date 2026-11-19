package com.abhinavxt.debforge.player

import android.net.Uri
import com.abhinavxt.debforge.domain.DirectLinkParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AudioTrackStreamTest {
    private val url = "https://cdn.example/ep.mp3"
    private val stream = AudioTrack("pod:abc", Uri.parse(url), "Ep", "Show", null)
    private val file = AudioTrack(DirectLinkParser.itemId(url), Uri.parse("content://files/ep.mp3"), "Ep", "Show", null)
    private val yt = AudioTrack("ytaudio:x", Uri.parse("https://v/x"), "Clip", null, null, youtube = YouTubeAudio("x", 140))

    @Test fun streamAndDownloadShareOneResumeKey() {
        assertTrue(stream.isRemoteStream)
        assertEquals(file.id, stream.resumeId)
        assertEquals(file.resumeId, stream.resumeId)
    }

    @Test fun otherTracksResumeUnderTheirOwnId() {
        assertFalse(file.isRemoteStream)
        assertFalse(yt.isRemoteStream)
        assertEquals("ytaudio:x", yt.resumeId)
    }

    @Test fun offlineErrorKeepsAnyStreamButNotAFile() {
        assertTrue(keepsOfflineOnError(isStream = true, online = false))
        assertFalse(keepsOfflineOnError(isStream = true, online = true))
        assertFalse(keepsOfflineOnError(isStream = false, online = false))
    }
}
