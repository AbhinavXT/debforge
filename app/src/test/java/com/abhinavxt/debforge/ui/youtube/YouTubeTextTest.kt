package com.abhinavxt.debforge.ui.youtube

import com.abhinavxt.debforge.data.youtube.YouTubeException
import com.abhinavxt.debforge.domain.Container
import com.abhinavxt.debforge.domain.FormatChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeTextTest {
    private val video = FormatChoice(137, 140, "mp4", 1, false, 1080, 60, Container.MP4, 128_000)
    private val audio = FormatChoice(null, 140, "m4a", 3_400_000, true, 0, 0, Container.M4A, 128_000)

    @Test fun fileNames() {
        assertEquals("AB [1080p].mp4", fileNameFor("A/B:", video))
        assertEquals("What ever [1080p].mp4", fileNameFor(" What: ever?* ", video))
        assertEquals("Song.m4a", fileNameFor("Song", audio))
        assertEquals("YouTube video.m4a", fileNameFor("///", audio))
        assertEquals(200 + " [1080p].mp4".length, fileNameFor("x".repeat(300), video).length)
    }

    @Test fun fileNamesStayWithinByteLimit() {
        val cjk = fileNameFor("漢".repeat(300), video).toByteArray().size
        assertTrue(cjk <= 255)
        val emoji = fileNameFor("😀".repeat(300), video)
        assertTrue(emoji.toByteArray().size <= 255)
        assertEquals(emoji, String(emoji.toByteArray())) // no half surrogate pair
        assertEquals("dQw4w9WgXcQ.m4a", fileNameFor("///", audio, fallback = "dQw4w9WgXcQ"))
        assertEquals("ab", truncateUtf8("ab😀", 4))
    }

    @Test fun labels() {
        assertEquals("1080p60 · MP4", choiceLabel(video))
        assertEquals("720p · WebM", choiceLabel(video.copy(height = 720, fps = 30, container = Container.WEBM)))
        assertEquals("M4A · 128 kbps", choiceLabel(audio))
        assertTrue(sizeText(audio).startsWith("~"))
        assertFalse(sizeText(video).startsWith("~"))
        assertEquals("3:33", durationText(213))
        assertEquals("1:01:05", durationText(3665))
    }

    @Test fun errors() {
        assertEquals(YouTubeErrorKind.PLAYLIST, errorKind(YouTubeException.PlaylistOnly()))
        assertEquals(YouTubeErrorKind.SIGN_IN, errorKind(YouTubeException.SignInRequired()))
        assertEquals(YouTubeErrorKind.UNAVAILABLE, errorKind(YouTubeException.Unavailable()))
        assertEquals(YouTubeErrorKind.NETWORK, errorKind(YouTubeException.Network(null)))
        assertEquals(YouTubeErrorKind.GENERIC, errorKind(YouTubeException.Failed(null)))
        assertEquals(YouTubeErrorKind.GENERIC, errorKind(IllegalStateException()))
    }

    @Test fun links() {
        assertEquals("youtu.be", linkHost("https://youtu.be/dQw4w9WgXcQ"))
        assertEquals("youtube.com", linkHost("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertEquals("youtu.be", linkHost("watch this https://youtu.be/dQw4w9WgXcQ now"))
        assertEquals("YouTube", linkHost("hello"))
        assertTrue(looksLikeYouTube("watch https://youtu.be/dQw4w9WgXcQ now"))
        assertFalse(looksLikeYouTube("hello"))
    }
}
