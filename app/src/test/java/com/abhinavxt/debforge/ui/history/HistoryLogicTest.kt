package com.abhinavxt.debforge.ui.history

import android.net.Uri
import com.abhinavxt.debforge.data.history.HistoryEntity
import com.abhinavxt.debforge.data.history.HistoryKind
import com.abhinavxt.debforge.data.history.HistoryOrigin
import com.abhinavxt.debforge.domain.ProviderId
import com.abhinavxt.debforge.player.AudioTrack
import com.abhinavxt.debforge.player.YouTubeAudio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HistoryLogicTest {
    private fun entity(kind: String = HistoryKind.VIDEO, origin: String = HistoryOrigin.CLOUD, provider: String? = "REAL_DEBRID", sourceRef: String = "src", itemId: String = "id") =
        HistoryEntity(itemId, kind, origin, provider, sourceRef, null, null, "T", null, null, 0, 0, 0)

    @Test fun routes() {
        assertEquals(ReplayRoute.YOUTUBE, replayRoute(entity(origin = HistoryOrigin.YOUTUBE)))
        assertEquals(ReplayRoute.YOUTUBE, replayRoute(entity(kind = HistoryKind.AUDIO, origin = HistoryOrigin.YOUTUBE)))
        assertEquals(ReplayRoute.LOCAL_AUDIO, replayRoute(entity(kind = HistoryKind.AUDIO, origin = HistoryOrigin.LOCAL)))
        assertEquals(ReplayRoute.VIDEO, replayRoute(entity()))
        assertEquals(ReplayRoute.UNPLAYABLE, replayRoute(entity(kind = "???", origin = HistoryOrigin.LOCAL)))
    }

    @Test fun providerLookup() {
        assertEquals(ProviderId.REAL_DEBRID, replayProvider(entity()))
        assertNull(replayProvider(entity(provider = null)))
        assertNull(replayProvider(entity(provider = "GONE")))
    }

    @Test fun youtubeIds() {
        assertEquals("abc", youtubeVideoId(entity(sourceRef = "abc", itemId = "ytstream:zzz")))
        assertEquals("zzz", youtubeVideoId(entity(sourceRef = "", itemId = "ytstream:zzz")))
        assertNull(youtubeVideoId(entity(sourceRef = "", itemId = "nocolon")))
        assertEquals("https://www.youtube.com/watch?v=abc", youtubeWatchUrl("abc"))
    }

    @Test fun artworkSchemes() {
        assertTrue(isSafeArtworkUrl("https://i.ytimg.com/a.jpg"))
        assertTrue(isSafeArtworkUrl("HTTP://x/y"))
        assertFalse(isSafeArtworkUrl("file:///data/x.jpg"))
        assertFalse(isSafeArtworkUrl("content://other/x"))
        assertFalse(isSafeArtworkUrl("data:image/png;base64,AA"))
        assertFalse(isSafeArtworkUrl(null))
    }

    @Test fun progress() {
        assertNull(historyProgress(10, 0))
        assertEquals(0.5f, historyProgress(50, 100)!!, 0.001f)
        assertEquals(1f, historyProgress(500, 100)!!, 0.001f)
        assertEquals(0f, historyProgress(-5, 100)!!, 0.001f)
    }

    private fun track(yt: YouTubeAudio? = null, artist: String? = "A") =
        AudioTrack("tid", Uri.EMPTY, "Song", artist, null, artworkUrl = "https://t/a.jpg", youtube = yt)

    @Test fun audioEntryLocal() {
        val e = audioHistoryEntry(track(), 12_000, 200_000, false, 99)!!
        assertEquals(HistoryKind.AUDIO, e.kind)
        assertEquals(HistoryOrigin.LOCAL, e.origin)
        assertEquals("tid", e.sourceRef)
        assertNull(e.artworkUrl)
        assertEquals("A", e.subtitle)
        assertEquals(99L, e.playedAt)
    }

    @Test fun audioEntryYouTube() {
        val e = audioHistoryEntry(track(YouTubeAudio("vid", 140), artist = " "), 6_000, 0, false, 1)!!
        assertEquals(HistoryOrigin.YOUTUBE, e.origin)
        assertEquals("vid", e.sourceRef)
        assertEquals("https://t/a.jpg", e.artworkUrl)
        assertNull(e.subtitle)
    }

    @Test fun audioEntryThreshold() {
        assertNull(audioHistoryEntry(track(), 4_999, 200_000, false, 1))
        assertEquals(5_000L, audioHistoryEntry(track(), 5_000, 200_000, false, 1)!!.positionMs)
        val ended = audioHistoryEntry(track(), 1_000, 30_000, true, 1)!!
        assertEquals(30_000L, ended.positionMs)
    }
}
