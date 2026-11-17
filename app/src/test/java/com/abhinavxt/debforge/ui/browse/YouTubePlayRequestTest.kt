package com.abhinavxt.debforge.ui.browse

import com.abhinavxt.debforge.domain.Container
import com.abhinavxt.debforge.domain.FormatChoice
import com.abhinavxt.debforge.domain.StreamKind
import com.abhinavxt.debforge.domain.YouTubeStream
import com.abhinavxt.debforge.domain.YouTubeSubtitle
import com.abhinavxt.debforge.domain.YouTubeVideo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class YouTubePlayRequestTest {
    private val video = YouTubeVideo(
        id = "abc", title = "A video", uploader = "u", durationSec = 60, thumbnailUrl = null,
        streams = listOf(
            YouTubeStream(137, "https://v/137", StreamKind.VIDEO, Container.MP4, "avc1", height = 1080),
            YouTubeStream(140, "https://a/140", StreamKind.AUDIO, Container.M4A, "mp4a"),
        ),
        subtitles = listOf(YouTubeSubtitle("en", "English", "https://s/en?fmt=vtt", false)),
    )

    private fun choice(v: Int?, a: Int) = FormatChoice(v, a, "mp4", 0, true, 1080, 30, Container.MP4, 128_000)

    @Test fun buildsRequestFromChoice() {
        val r = youTubePlayRequest(video, choice(137, 140))!!
        assertEquals("https://v/137", r.uri.toString())
        assertEquals("A video", r.title)
        val yt = r.youtube!!
        assertEquals("yt:abc:137:140", yt.ref)
        assertEquals("abc", yt.videoId)
        assertEquals("https://a/140", yt.audioUrl)
        assertEquals(1080, yt.height)
        assertEquals("text/vtt", r.subtitles.single().mimeType)
        assertEquals("en", r.subtitles.single().language)
        assertFalse(r.subtitles.single().forced)
    }

    @Test fun audioOnlyChoiceIsNotAVideoRequest() = assertNull(youTubePlayRequest(video, choice(null, 140)))

    @Test fun missingStreamGivesNull() {
        assertNull(youTubePlayRequest(video, choice(137, 251)))
        assertNull(youTubePlayRequest(video, choice(299, 140)))
    }

    @Test fun streamPositionsStayOutOfContinueWatching() {
        assertTrue(isYouTubeStreamPosition("ytstream:abc"))
        assertTrue(isYouTubeStreamPosition("ytaudio:abc"))
        assertFalse(isYouTubeStreamPosition("yt:abc:137:140"))
        assertFalse(isYouTubeStreamPosition("12345"))
    }
}
