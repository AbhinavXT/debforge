package com.abhinavxt.debforge.ui.video

import com.abhinavxt.debforge.domain.Container
import com.abhinavxt.debforge.domain.FormatChoice
import com.abhinavxt.debforge.domain.YouTubeSearchResult
import com.abhinavxt.debforge.domain.YouTubeSubtitle
import com.abhinavxt.debforge.domain.YouTubeVideo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WatchModelTest {
    private val size: (Long) -> String = { "${it / 1_000_000} MB" }
    private val choice = FormatChoice(137, 140, "mp4", 1, false, 1080, 30, Container.MP4, 128_000)
    private val related = YouTubeSearchResult("u", "t", "c", 10, null)
    private fun video(subs: Boolean = false, rel: Boolean = true) = YouTubeVideo(
        "id", "t", "up", 10, null, emptyList(),
        subtitles = if (subs) listOf(YouTubeSubtitle("en", "English", "u", false)) else emptyList(),
        uploaderUrl = "https://youtube.com/channel/x", related = if (rel) listOf(related) else emptyList(),
    )
    private val yt = VideoUiState(VideoKind.YOUTUBE, "t", canPlayAsAudio = true)
    private val ytDetails = WatchDetails(video = video(), formats = WatchFormats(listOf(choice), choice))

    @Test fun downloadSourceLine() {
        val s = VideoUiState(VideoKind.DOWNLOAD, "t", height = 1080, container = "mkv", sizeBytes = 1_400_000_000)
        assertEquals("Downloaded · 1080p · MKV · 1400 MB", sourceLine(s, "Downloaded", size))
    }

    @Test fun downloadSourceLineSkipsUnknown() {
        assertEquals("Downloaded", sourceLine(VideoUiState(VideoKind.DOWNLOAD, "t"), "Downloaded", size))
    }

    @Test fun debridSourceLine() {
        val s = VideoUiState(VideoKind.DEBRID, "t", providerName = "TorBox", fileName = "Movie.mkv", sizeBytes = 2_000_000_000)
        assertEquals("TorBox · Movie.mkv · 2000 MB", sourceLine(s, "Downloaded", size))
    }

    @Test fun youtubeHasNoSourceLine() {
        assertEquals("", sourceLine(yt, "Downloaded", size))
    }

    @Test fun youtubeChips() {
        assertEquals(
            listOf(WatchChip.DOWNLOAD, WatchChip.PLAY_AS_AUDIO, WatchChip.ADD_TO_PLAYLIST, WatchChip.ADD_TO_QUEUE, WatchChip.SHARE),
            chipsFor(yt, ytDetails, canAddToPlaylist = true),
        )
        assertEquals(
            listOf(WatchChip.DOWNLOAD, WatchChip.ADD_TO_QUEUE, WatchChip.SHARE, WatchChip.SUBTITLES),
            chipsFor(yt.copy(canPlayAsAudio = false), ytDetails.copy(video = video(subs = true)), canAddToPlaylist = false),
        )
    }

    @Test fun downloadChips() {
        val s = VideoUiState(VideoKind.DOWNLOAD, "t", canPlayAsAudio = true)
        assertEquals(listOf(WatchChip.PLAY_AS_AUDIO, WatchChip.SUBTITLES, WatchChip.SHARE), chipsFor(s, WatchDetails()))
        assertEquals(
            listOf(WatchChip.SUBTITLES, WatchChip.OPEN_EXTERNAL, WatchChip.SHARE),
            chipsFor(s.copy(canPlayAsAudio = false), WatchDetails(canOpenExternal = true)),
        )
    }

    @Test fun debridChips() {
        val s = VideoUiState(VideoKind.DEBRID, "t", canPlayAsAudio = true)
        assertEquals(listOf(WatchChip.SUBTITLES), chipsFor(s, WatchDetails()))
        assertEquals(
            listOf(WatchChip.DOWNLOAD, WatchChip.CAST, WatchChip.OPEN_EXTERNAL, WatchChip.SUBTITLES),
            chipsFor(s, WatchDetails(canDownload = true, canCast = true, canOpenExternal = true)),
        )
    }

    @Test fun listKinds() {
        assertEquals(WatchListKind.RELATED, listKindFor(yt, ytDetails))
        assertNull(listKindFor(yt, ytDetails.copy(video = video(rel = false))))
        val ep = VideoUiState(VideoKind.DEBRID, "t", episodes = listOf(EpisodeRow("a", "E2")))
        assertEquals(WatchListKind.EPISODES, listKindFor(ep, WatchDetails()))
        assertNull(listKindFor(ep.copy(episodes = emptyList()), WatchDetails()))
    }
}
