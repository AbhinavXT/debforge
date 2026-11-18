package com.abhinavxt.debforge.widget

import android.net.Uri
import com.abhinavxt.debforge.player.AudioState
import com.abhinavxt.debforge.player.AudioTrack
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
class PlayerWidgetRendererTest {

    private fun track(id: String, artist: String? = "Artist") =
        AudioTrack(id, Uri.EMPTY, "Title $id", artist, null)

    @Test fun emptyQueueIsTheIdleState() {
        val s = stateOf(AudioState(), null, live = true)
        assertTrue(s.empty)
        assertFalse(showPlay(s))
        assertFalse(showPrevious(s))
        assertFalse(showNext(s))
    }

    @Test fun currentTrackFillsTheState() {
        val s = stateOf(AudioState(queue = listOf(track("a"), track("b")), index = 1, isPlaying = true), null, live = true)
        assertFalse(s.empty)
        assertEquals("Title b", s.title)
        assertEquals("Artist", s.artist)
        assertTrue(s.isPlaying)
        assertNull(s.art)
    }

    @Test fun nextIsHiddenOnTheLastTrack() {
        val s = stateOf(AudioState(queue = listOf(track("a")), hasNext = false), null, live = true)
        assertFalse(showNext(s))
        assertTrue(showPrevious(s))
        assertTrue(showPlay(s))
    }

    @Test fun withoutAPlayerOnlyPlayShows() {
        val s = stateOf(AudioState(queue = listOf(track("a"), track("b"))), null, live = false)
        assertTrue(showPlay(s))
        assertFalse(showPrevious(s))
        assertFalse(showNext(s))
    }

    @Test fun liveShowsAllThreeWhenThereIsANext() {
        val s = stateOf(AudioState(queue = listOf(track("a"), track("b"))), null, live = true)
        assertTrue(showPlay(s) && showPrevious(s) && showNext(s))
    }
}
