package com.abhinavxt.debforge.player

import com.abhinavxt.debforge.domain.Container
import com.abhinavxt.debforge.domain.StreamKind
import com.abhinavxt.debforge.domain.YouTubeStream
import com.abhinavxt.debforge.domain.YouTubeVideo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeAudioTrackTest {
    private fun audio(itag: Int, c: Container, bps: Long, url: String = "https://x/$itag") =
        YouTubeStream(itag, url, StreamKind.AUDIO, c, "mp4a", bitrateBps = bps)

    private fun video(vararg s: YouTubeStream) = YouTubeVideo("abc", "T", "U", 100, "https://t/img.jpg", s.toList())

    @Test fun freshStreamKeepsTheSameItagWithANewUrl() {
        val v = video(audio(140, Container.M4A, 128_000, "https://new/140"), audio(251, Container.WEBM, 160_000))
        assertEquals("https://new/140", freshAudioStream(v, 140, 34)?.url)
    }

    @Test fun freshStreamFallsBackWhenTheItagIsGone() {
        val v = video(audio(251, Container.WEBM, 160_000))
        assertEquals(251, freshAudioStream(v, 140, 34)?.itag)
    }

    @Test fun freshStreamIsNullWithoutAudio() = assertNull(freshAudioStream(video(), 140, 34))

    @Test fun recoveryIsRateLimited() {
        assertTrue(mayRecoverLink(null, 5))
        assertFalse(mayRecoverLink(1_000, 1_000 + YT_RECOVER_GAP_MS - 1))
        assertTrue(mayRecoverLink(1_000, 1_000 + YT_RECOVER_GAP_MS))
    }
}
