package com.abhinavxt.debforge.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

class YouTubeFiltersTest {
    private val now = Instant.parse("2026-06-15T12:00:00Z")
    private fun video(secs: Long, daysAgo: Long? = null) =
        YouTubeSearchResult("u", "t", "c", secs, null, uploadedAt = daysAgo?.let { now.minusSeconds(it * 86_400) })
    private fun keeps(f: YouTubeFilters, r: YouTubeSearchResult) = f.keeps(r, now, ZoneOffset.UTC)

    @Test fun `type maps to the extractor content filter`() {
        assertEquals("all", YouTubeFilters(type = YtType.ALL).contentFilter)
        assertEquals("videos", YouTubeFilters().contentFilter) // the default keeps search to videos, as before filters existed
        assertEquals("videos", YouTubeFilters(type = YtType.VIDEOS).contentFilter)
        assertEquals("channels", YouTubeFilters(type = YtType.CHANNELS).contentFilter)
        assertEquals("playlists", YouTubeFilters(type = YtType.PLAYLISTS).contentFilter)
    }

    @Test fun `default filters are inactive and keep everything`() {
        assertFalse(YouTubeFilters().active)
        assertTrue(keeps(YouTubeFilters(), YouTubeSearchResult("c", "n", null, 0, null, kind = ResultKind.CHANNEL)))
        assertTrue(YouTubeFilters(type = YtType.ALL).active)
    }

    @Test fun `duration bands`() {
        val short = YouTubeFilters(duration = YtDuration.SHORT)
        assertTrue(keeps(short, video(239)))
        assertFalse(keeps(short, video(240)))
        val medium = YouTubeFilters(duration = YtDuration.MEDIUM)
        assertTrue(keeps(medium, video(240)))
        assertTrue(keeps(medium, video(1200)))
        assertFalse(keeps(medium, video(1201)))
        val long = YouTubeFilters(duration = YtDuration.LONG)
        assertTrue(keeps(long, video(1201)))
        assertFalse(keeps(long, video(0))) // live or unknown length
    }

    @Test fun `upload date windows`() {
        assertTrue(keeps(YouTubeFilters(date = YtDate.TODAY), video(60, 0)))
        assertFalse(keeps(YouTubeFilters(date = YtDate.TODAY), video(60, 1)))
        assertTrue(keeps(YouTubeFilters(date = YtDate.WEEK), video(60, 6)))
        assertFalse(keeps(YouTubeFilters(date = YtDate.WEEK), video(60, 8)))
        assertTrue(keeps(YouTubeFilters(date = YtDate.MONTH), video(60, 20)))
        assertFalse(keeps(YouTubeFilters(date = YtDate.MONTH), video(60, 40)))
        assertTrue(keeps(YouTubeFilters(date = YtDate.YEAR), video(60, 300)))
        assertFalse(keeps(YouTubeFilters(date = YtDate.YEAR), video(60, 400)))
        assertFalse(keeps(YouTubeFilters(date = YtDate.YEAR), video(60, null))) // unknown date
    }

    @Test fun `a duration or date filter drops channels and playlists`() {
        val f = YouTubeFilters(duration = YtDuration.LONG)
        assertFalse(keeps(f, YouTubeSearchResult("p", "n", null, 0, null, kind = ResultKind.PLAYLIST)))
    }

    @Test fun `length and date are ignored for channels and playlists`() {
        val ch = YouTubeFilters(type = YtType.CHANNELS, duration = YtDuration.LONG, date = YtDate.WEEK)
        assertFalse(ch.clientSide)
        assertTrue(keeps(ch, YouTubeSearchResult("c", "n", null, 0, null, kind = ResultKind.CHANNEL)))
        assertTrue(YouTubeFilters(type = YtType.ALL, duration = YtDuration.LONG).clientSide)
    }

    @Test fun `filters combine`() {
        val f = YouTubeFilters(duration = YtDuration.SHORT, date = YtDate.WEEK)
        assertTrue(keeps(f, video(100, 2)))
        assertFalse(keeps(f, video(100, 20)))
        assertFalse(keeps(f, video(1000, 2)))
    }
}
