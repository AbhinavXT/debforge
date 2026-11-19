package com.abhinavxt.debforge.player

import android.net.Uri
import com.abhinavxt.debforge.data.playback.AudioQueueCodec
import com.abhinavxt.debforge.data.playback.QueueSnapshot
import com.abhinavxt.debforge.data.playback.YouTubeMeta
import com.abhinavxt.debforge.domain.YouTubeLinks
import com.abhinavxt.debforge.domain.YouTubeSearchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MixedQueueTest {
    private val meta = YouTubeMeta("Clip", "Chan", 120)
    private fun local(id: String) = AudioTrack(id, Uri.parse("file:///$id"), id, null, null)
    private fun yt(id: String, resolved: Boolean = false) =
        lazyYouTubeTrack(id, meta).let { if (resolved) it.copy(uri = Uri.parse("https://s/$id")) else it }

    @Test fun lazyTrackCarriesItsIdAndMetadata() {
        val t = lazyYouTubeTrack("abcdefghijk", meta)
        assertEquals("ytaudio:abcdefghijk", t.id)
        assertEquals(YouTubeAudio("abcdefghijk", 0), t.youtube)
        assertEquals(Uri.EMPTY, t.uri)
        assertEquals("Clip", t.title)
        assertEquals("Chan", t.artist)
        assertEquals("https://i.ytimg.com/vi/abcdefghijk/hqdefault.jpg", t.artworkUrl)
    }

    @Test fun nextToResolveSkipsTheCurrentLocalAndResolvedItems() {
        val q = listOf(yt("a"), local("l"), yt("b", resolved = true), yt("c"), yt("d"))
        assertEquals(3, nextToResolve(q, listOf(0, 1, 2, 3, 4)))
        assertEquals(4, nextToResolve(q, listOf(3, 4)))
        assertNull(nextToResolve(q, listOf(4)))
        assertNull(nextToResolve(listOf(local("x"), local("y")), listOf(0, 1)))
    }

    @Test fun nextToResolveFollowsTheGivenOrder() {
        val q = listOf(yt("a"), yt("b"), yt("c"))
        assertEquals(2, nextToResolve(q, listOf(0, 2, 1)))
    }

    private fun row(id: String, video: String, videoItag: Int?, audio: Int = 140) =
        YouTubeDownloadRow(id, YouTubeLinks.sourceRef(video, videoItag, audio))

    @Test fun audioDownloadBeatsVideoDownload() {
        val m = preferredLocalDownloads(listOf(row("v1", "vid", 137), row("a1", "vid", null), row("a2", "vid", null)))
        assertEquals("a1", m["vid"])
    }

    @Test fun videoDownloadIsUsedWhenThereIsNoAudioOne() {
        assertEquals("v1", preferredLocalDownloads(listOf(row("v1", "vid", 137)))["vid"])
    }

    @Test fun otherRowsAreIgnored() {
        val m = preferredLocalDownloads(listOf(YouTubeDownloadRow("x", "https://example.com/a.mp3"), row("a", "other", null)))
        assertNull(m["vid"])
        assertEquals(1, m.size)
    }

    @Test fun unresolvedTrackSurvivesTheSavedQueueRoundTrip() {
        val t = yt("abcdefghijk")
        val saved = SavedTrack0(t)
        val q = QueueSnapshot(listOf(saved))
        val back = AudioQueueCodec.decode(AudioQueueCodec.encode(q))!!.tracks.single()
        assertEquals(0, back.itag)
        assertEquals("abcdefghijk", back.videoId)
        assertEquals(saved, back)
    }

    private fun SavedTrack0(t: AudioTrack) = com.abhinavxt.debforge.data.playback.SavedTrack(
        id = t.id, title = t.title, artist = t.artist, local = false,
        videoId = t.youtube!!.videoId, itag = t.youtube!!.audioItag, streamUrl = null, artworkUrl = t.artworkUrl
    )

    @Test fun relatedCacheKeepsTwentyAndDropsTheLeastRecentlyUsed() {
        val c = RelatedCache(20)
        val r = listOf(YouTubeSearchResult("u", "t", null, 1, null))
        (1..20).forEach { c.put("v$it", r) }
        c.get("v1") // v2 is now the oldest
        c.put("v21", r)
        assertNull(c.get("v2"))
        assertEquals(r, c.get("v1"))
        assertEquals(r, c.get("v21"))
    }
}
