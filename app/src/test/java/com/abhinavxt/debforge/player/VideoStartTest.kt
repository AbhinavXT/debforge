package com.abhinavxt.debforge.player

import android.content.Intent
import android.net.Uri
import com.abhinavxt.debforge.data.playback.YouTubeMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class VideoStartTest {
    @Test fun visibleOnlyWhenEveryRuleAllows() {
        assertTrue(playAsAudioVisible(live = false, durationMs = 1000, hasAudioTrack = true, casting = false, debridStream = false))
        assertFalse(playAsAudioVisible(true, 1000, true, false, false))
        assertFalse(playAsAudioVisible(false, 0, true, false, false))
        assertFalse(playAsAudioVisible(false, 1000, false, false, false))
        assertFalse(playAsAudioVisible(false, 1000, true, true, false))
        assertFalse(playAsAudioVisible(false, 1000, true, false, true))
    }

    @Test fun extrasRoundTripAndSkipTheSavedPosition() {
        val i = VideoStartExtras.put(Intent(), VideoStart(95_000, 1.5f))
        val s = VideoStartExtras.from(i)
        assertEquals(VideoStart(95_000, 1.5f), s)
        assertTrue(i.getBooleanExtra(VideoStartExtras.EXTRA_FROM_AUDIO, false))
        assertFalse(restoresSavedPosition(s))
    }

    @Test fun noExtrasMeansTheSavedPositionApplies() {
        assertNull(VideoStartExtras.from(Intent().setData(Uri.parse("file:///a"))))
        assertTrue(restoresSavedPosition(null))
    }

    @Test fun startAtZeroStillCountsAsHandedOver() {
        assertEquals(VideoStart(0, 1f), VideoStartExtras.from(Intent().putExtra(VideoStartExtras.EXTRA_START_MS, 0L)))
    }

    @Test fun badValuesAreClamped() {
        val s = VideoStartExtras.from(Intent().putExtra(VideoStartExtras.EXTRA_START_MS, -5L).putExtra(VideoStartExtras.EXTRA_SPEED, Float.NaN))
        assertEquals(VideoStart(0, 1f), s)
        assertEquals(1f, VideoStartExtras.from(Intent().putExtra(VideoStartExtras.EXTRA_START_MS, 1L).putExtra(VideoStartExtras.EXTRA_SPEED, 99f))!!.speed, 0f)
    }

    private fun file(id: String) = AudioTrack(id, Uri.parse("file:///$id"), id, null, null)
    private fun stream(v: String) = lazyYouTubeTrack(v, YouTubeMeta("t", "c", 1))

    @Test fun streamHandoffJumpsToTheLocalDownloadOfTheSameVideo() {
        val q = listOf(file("a"), file("dl1"), file("b"))
        assertEquals(HandoffPlan.JumpTo(1), handoffPlan(q, 0, stream("vid"), localId = "dl1", videoId = "vid"))
    }

    @Test fun localDownloadHandoffJumpsToTheQueuedStreamOfTheSameVideo() {
        val q = listOf(file("a"), stream("vid"))
        assertEquals(HandoffPlan.JumpTo(1), handoffPlan(q, 0, file("dl1"), videoId = "vid"))
    }

    @Test fun unrelatedVideoIsStillInsertedAfterCurrent() {
        val q = listOf(file("a"), file("dl1"))
        assertEquals(HandoffPlan.InsertAt(1), handoffPlan(q, 0, stream("other"), localId = "dl9", videoId = "other"))
    }
}
