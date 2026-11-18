package com.abhinavxt.debforge.download

import com.abhinavxt.debforge.domain.HlsPlaylist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioFinishTest {
    private fun media(segs: List<HlsPlaylist.Segment>, init: HlsPlaylist.Segment? = null) =
        HlsPlaylist.Media(segs, init, 10.0, live = false, drm = false)

    @Test fun rangesOfOneUrlAreProgressive() {
        assertTrue(AudioFinish.isSingleProgressive(media(YouTubeSource.planPieces("u", 9_000_000L))))
    }

    @Test fun differentUrlsOrGapsAreNot() {
        val a = HlsPlaylist.Segment("a", 0, 10, null)
        assertFalse(AudioFinish.isSingleProgressive(media(listOf(a, HlsPlaylist.Segment("b", 10, 10, null)))))
        assertFalse(AudioFinish.isSingleProgressive(media(listOf(a, HlsPlaylist.Segment("a", 20, 10, null)))))
    }

    @Test fun wholeFileSegmentsWithoutRangesAreNot() {
        assertFalse(AudioFinish.isSingleProgressive(media(listOf(HlsPlaylist.Segment("a", null, null, null)))))
        assertFalse(AudioFinish.isSingleProgressive(media(emptyList())))
    }

    @Test fun initSectionIsNot() {
        val init = HlsPlaylist.Segment("a", 0, 5, null)
        assertFalse(AudioFinish.isSingleProgressive(media(YouTubeSource.planPieces("a", 100), init)))
    }

    @Test fun kindByExtension() {
        assertEquals(AudioFinish.Kind.M4A, AudioFinish.kindFor("/x/a.m4a", 1, true))
        assertEquals(AudioFinish.Kind.M4A, AudioFinish.kindFor("/x/a.MP4", 1, true))
        assertEquals(AudioFinish.Kind.WEBM, AudioFinish.kindFor("/x/a.webm", 1, true))
        assertEquals(AudioFinish.Kind.WEBM, AudioFinish.kindFor("/x/a.weba", 1, true))
        assertNull(AudioFinish.kindFor("/x/a.mp3", 1, true))
        assertNull(AudioFinish.kindFor("/x/noext", 1, true))
    }

    @Test fun neverForTwoTracksOrNonProgressive() {
        assertNull(AudioFinish.kindFor("/x/a.m4a", 2, true))
        assertNull(AudioFinish.kindFor("/x/a.m4a", 1, false))
    }
}
