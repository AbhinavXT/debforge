package com.abhinavxt.debforge.player

import android.net.Uri
import com.abhinavxt.debforge.data.playback.YouTubeMeta
import com.abhinavxt.debforge.domain.YouTubeLinks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class VideoHandoffTest {
    private fun file(id: String, video: Boolean = false) = AudioTrack(id, Uri.parse("file:///$id"), id, null, null, isVideoFile = video)
    private fun yt(id: String, itag: Int = 0) = lazyYouTubeTrack(id, YouTubeMeta("t", "c", 1)).copy(youtube = YouTubeAudio(id, itag))
    private fun row(id: String, video: String, videoItag: Int?) =
        YouTubeDownloadRow(id, YouTubeLinks.sourceRef(video, videoItag, 140))

    @Test fun preferredVideoDownloadIgnoresAudioOnlyRows() {
        assertEquals("v1", preferredVideoDownload(listOf(row("a1", "vid", null), row("v1", "vid", 137), row("v2", "vid", 22)), "vid"))
    }

    @Test fun preferredVideoDownloadNullWithoutAVideoRowOrOtherVideo() {
        assertNull(preferredVideoDownload(listOf(row("a1", "vid", null), row("v1", "other", 137), YouTubeDownloadRow("x", "https://e.com/a")), "vid"))
    }

    @Test fun sameCurrentItemJustSeeks() {
        val q = listOf(file("a"), yt("vid"), file("b"))
        assertEquals(HandoffPlan.JumpTo(1), handoffPlan(q, 1, yt("vid", itag = 140)))
        assertEquals(HandoffPlan.JumpTo(0), handoffPlan(q, 0, file("a", video = true)))
    }

    @Test fun otherItemIsInsertedRightAfterTheCurrentOne() {
        val q = listOf(file("a"), file("b"), file("c"))
        assertEquals(HandoffPlan.InsertAt(2), handoffPlan(q, 1, file("x", video = true)))
        assertEquals(HandoffPlan.InsertAt(3), handoffPlan(q, 2, yt("vid")))
    }

    @Test fun itemQueuedElsewhereIsJumpedToNotDuplicated() {
        val q = listOf(file("a"), yt("vid"), file("b"))
        assertEquals(HandoffPlan.JumpTo(1), handoffPlan(q, 2, yt("vid")))
    }

    @Test fun emptyQueueStartsAOneItemQueue() {
        assertEquals(HandoffPlan.StartNew, handoffPlan(emptyList(), 0, yt("vid")))
    }

    @Test fun staleCurrentIndexIsClamped() {
        assertEquals(HandoffPlan.InsertAt(2), handoffPlan(listOf(file("a"), file("b")), 9, file("x")))
    }

    @Test fun canWatchOnlyForVideoFilesAndYouTube() {
        assertTrue(file("v", video = true).canWatch)
        assertTrue(yt("vid").canWatch)
        assertFalse(file("song").canWatch)
        assertFalse(AudioTrack("p", Uri.parse("https://feed/ep.mp3"), "ep", null, null).canWatch) // a podcast episode
    }

    @Test fun handoffIgnoresASavedResumeOnlyForItsOwnItem() {
        assertTrue(handoffSkipsResume("x", "x"))
        assertFalse(handoffSkipsResume("y", "x"))
        assertFalse(handoffSkipsResume("x", null))
        assertFalse(handoffSkipsResume(null, null))
    }
}
