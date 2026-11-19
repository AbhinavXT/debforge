package com.abhinavxt.debforge.ui.media

import android.net.Uri
import com.abhinavxt.debforge.data.local.DownloadEntity
import com.abhinavxt.debforge.data.playback.PlaylistEntry
import com.abhinavxt.debforge.data.playback.YouTubeMeta
import com.abhinavxt.debforge.data.playback.YouTubeMode
import com.abhinavxt.debforge.domain.DownloadState
import com.abhinavxt.debforge.domain.ProviderId
import com.abhinavxt.debforge.domain.YouTubeLinks
import com.abhinavxt.debforge.player.AudioTrack
import com.abhinavxt.debforge.player.MixedEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlaylistRowsTest {
    private val a = "aaaaaaaaaaa"
    private val b = "bbbbbbbbbbb"
    private val c = "ccccccccccc"

    private fun dl(id: String, ref: String = "", state: DownloadState = DownloadState.COMPLETED) = DownloadEntity(
        id = id, provider = ProviderId.REAL_DEBRID, filename = "$id.m4a", sourceRef = ref, downloadUrl = "", host = "",
        filesize = 1, chunksAllowed = 1, state = state
    )
    private fun ytA(id: String) = PlaylistEntry.YouTube(id, YouTubeMode.AUDIO)
    private fun ytV(id: String) = PlaylistEntry.YouTube(id, YouTubeMode.VIDEO)
    private val meta = mapOf(a to YouTubeMeta("Alpha", "Chan", 60))

    @Test fun keepsPlaylistOrderMixed() {
        val entries = listOf(ytA(a), PlaylistEntry.Download("d1"), ytV(b), PlaylistEntry.Download("gone"))
        val rows = playlistRows(entries, mapOf("d1" to dl("d1")), meta, YouTubeLocals.of(emptyList()))
        assertEquals(listOf("y:yt:a:$a", "d:d1", "y:yt:v:$b"), rows.map { it.key })
    }

    @Test fun missingMetadataFallsBackToVideoId() {
        val rows = playlistRows(listOf(ytA(a), ytA(b)), emptyMap(), meta, YouTubeLocals.of(emptyList()))
        val ya = rows[0] as PlaylistRow.YouTube
        val yb = rows[1] as PlaylistRow.YouTube
        assertEquals("Alpha", ya.title); assertEquals("Chan", ya.channel)
        assertEquals(b, yb.title); assertNull(yb.channel)
        assertEquals("https://i.ytimg.com/vi/$b/hqdefault.jpg", yb.thumbnailUrl)
    }

    @Test fun detectsDownloadedByVideoIdAndPrefersAudio() {
        val audioDl = dl("x1", YouTubeLinks.sourceRef(a, null, 140))
        val videoDl = dl("x2", YouTubeLinks.sourceRef(a, 137, 140))
        val rows = playlistRows(listOf(ytV(a), ytA(b)), emptyMap(), meta, YouTubeLocals.of(listOf(videoDl, audioDl)))
        val ya = rows[0] as PlaylistRow.YouTube
        assertEquals("x1", ya.download?.id)
        assertEquals("x2", ya.videoDownload?.id)
        assertTrue(ya.isDownloaded)
        assertFalse((rows[1] as PlaylistRow.YouTube).isDownloaded)
    }

    @Test fun queryFiltersYouTubeRowsAndDuplicatesAreDropped() {
        val rows = playlistRows(listOf(ytA(a), ytA(b), ytA(a)), emptyMap(), meta, YouTubeLocals.of(emptyList()), "alp")
        assertEquals(listOf("y:yt:a:$a"), rows.map { it.key })
    }

    private fun track(id: String) = AudioTrack(id, Uri.parse("file:///$id"), id, null, null)

    @Test fun playAllQueueIsEveryRowInOrder() {
        val rows = playlistRows(
            listOf(PlaylistEntry.Download("d1"), ytV(a), ytA(b)), mapOf("d1" to dl("d1")), meta, YouTubeLocals.of(emptyList())
        )
        val (q, start) = mixedQueueFor(rows, 0, ::trackFor)
        assertEquals(0, start)
        assertTrue(q[0] is MixedEntry.Local)
        assertEquals(MixedEntry.YouTube(a, YouTubeMeta("Alpha", "Chan", null)), q[1])
        assertEquals(MixedEntry.YouTube(b, YouTubeMeta(b, null, null)), q[2])
    }

    private fun trackFor(e: DownloadEntity): AudioTrack? = if (e.id == "bad") null else track(e.id)

    @Test fun startIndexFollowsSkippedRows() {
        val rows = playlistRows(
            listOf(PlaylistEntry.Download("bad"), ytA(a), ytA(b)), mapOf("bad" to dl("bad")), meta, YouTubeLocals.of(emptyList())
        )
        val (q, start) = mixedQueueFor(rows, 2, ::trackFor)
        assertEquals(2, q.size)
        assertEquals(1, start)
        val (_, startOnSkipped) = mixedQueueFor(rows, 0, ::trackFor)
        assertEquals(0, startOnSkipped)
    }

    private fun ds(id: String, v: String, state: DownloadState, audioOnly: Boolean) = YouTubeDownloadState(id, v, state, audioOnly)

    @Test fun downloadAllSkipsCompletedAndActiveButKeepsFailed() {
        val entries = listOf(ytA(a), ytV(b), ytA(c), PlaylistEntry.Download("d1"), ytA(c))
        val have = listOf(
            ds("1", a, DownloadState.COMPLETED, true),
            ds("2", b, DownloadState.FAILED, false),
        )
        // the failed one stays selected (it is retried), c is once
        assertEquals(listOf(ytV(b), ytA(c)), youTubeToDownload(entries, have))
        assertEquals(listOf(ytA(c)), youTubeToDownload(entries, have + ds("3", b, DownloadState.DOWNLOADING, false)))
        assertEquals("2", failedDownloadId(ytV(b), have))
        assertNull(failedDownloadId(ytA(a), have))
    }

    @Test fun downloadAllMatchesByMode() {
        val audioOnly = listOf(ds("1", a, DownloadState.COMPLETED, true))
        assertEquals(listOf(ytV(a)), youTubeToDownload(listOf(ytV(a)), audioOnly))
        assertTrue(youTubeToDownload(listOf(ytA(a)), audioOnly).isEmpty())
        val video = listOf(ds("2", a, DownloadState.QUEUED, false))
        assertEquals(listOf(ytA(a)), youTubeToDownload(listOf(ytA(a)), video))
        assertTrue(youTubeToDownload(listOf(ytV(a)), video).isEmpty())
        // a failed audio download does not stand in for the video one
        assertNull(failedDownloadId(ytV(a), listOf(ds("3", a, DownloadState.FAILED, true))))
        // both tokens of one video are both considered
        assertEquals(listOf(ytA(a), ytV(a)), youTubeToDownload(listOf(ytA(a), ytV(a)), emptyList()))
    }

    @Test fun downloadStatesComeFromSourceRefs() {
        val audio = dl("x", YouTubeLinks.sourceRef(a, null, 140))
        val vid = dl("y", YouTubeLinks.sourceRef(b, 137, 140), DownloadState.FAILED)
        val other = dl("z", "https://example.com")
        assertEquals(
            listOf(YouTubeDownloadState("x", a, DownloadState.COMPLETED, true), YouTubeDownloadState("y", b, DownloadState.FAILED, false)),
            youTubeDownloadStates(listOf(audio, vid, other)),
        )
    }

    @Test fun bulkOutcomeMapping() {
        assertEquals(BulkOutcome.QUEUED, bulkOutcome(1, 3))
        assertEquals(BulkOutcome.ALREADY, bulkOutcome(0, 2))
        assertEquals(BulkOutcome.FAILED, bulkOutcome(0, 0))
    }

    @Test fun bothModesOfOneVideoShowBothRows() {
        val rows = playlistRows(listOf(ytA(a), ytV(a)), emptyMap(), meta, YouTubeLocals.of(emptyList()))
        assertEquals(2, rows.size)
        assertEquals(2, rows.map { it.key }.toSet().size)
    }

    @Test fun downloadAllUsesEachItemsOwnMode() {
        assertEquals(com.abhinavxt.debforge.download.youtube.EnqueueMode.AUDIO, ytA(a).enqueueMode())
        assertEquals(com.abhinavxt.debforge.download.youtube.EnqueueMode.VIDEO, ytV(a).enqueueMode())
    }
}
