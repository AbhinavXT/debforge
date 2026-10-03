package com.abhinavxt.debforge.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NextEpisodeTest {

    private val GB = 1024L * 1024 * 1024
    private var n = 0
    private fun f(name: String, parent: String = "t:1", size: Long = 1 * GB, provider: ProviderId = ProviderId.TORBOX) =
        DownloadItem(
            id = "i${n++}", provider = provider, filename = name, sourceRef = name, downloadUrl = null,
            host = "", filesize = size, maxConnections = 8, addedAt = null, parentRef = parent
        )

    private fun c(item: DownloadItem) = NextEpisode.Candidate(item, ReleaseNameParser.parse(item.filename))

    private fun next(current: DownloadItem, all: List<DownloadItem>, limit: Int = 10) =
        NextEpisode.upcoming(current, ReleaseNameParser.parse(current.filename), all.map(::c), limit)
            .map { it.item.filename }

    @Test fun nextInOrder() {
        val e1 = f("Show.S01E01.1080p.mkv"); val e2 = f("Show.S01E02.1080p.mkv"); val e3 = f("Show.S01E03.1080p.mkv")
        assertEquals(listOf("Show.S01E02.1080p.mkv", "Show.S01E03.1080p.mkv"), next(e1, listOf(e3, e1, e2)))
    }

    @Test fun crossesIntoNextSeason() {
        val last = f("Show.S01E10.1080p.mkv")
        val s2 = f("Show.S02E01.1080p.mkv", parent = "t:2")
        assertEquals(listOf("Show.S02E01.1080p.mkv"), next(last, listOf(last, s2)))
    }

    @Test fun earlierEpisodesIgnored() {
        val e5 = f("Show.S01E05.mkv")
        assertTrue(next(e5, listOf(f("Show.S01E04.mkv"), e5)).isEmpty())
    }

    @Test fun prefersSameTorrentThenResolution() {
        val cur = f("Show.S01E01.1080p.mkv", parent = "pack")
        val otherRelease = f("Show.S01E02.2160p.mkv", parent = "single", size = 6 * GB)
        val samePack = f("Show.S01E02.1080p.mkv", parent = "pack", size = 1 * GB)
        assertEquals(listOf("Show.S01E02.1080p.mkv"), next(cur, listOf(cur, otherRelease, samePack)))

        val cur2 = f("Show.S02E01.1080p.mkv", parent = "a")
        val uhd = f("Show.S02E02.2160p.mkv", parent = "b", size = 6 * GB)
        val hd = f("Show.S02E02.1080p.mkv", parent = "c", size = 2 * GB)
        assertEquals(listOf("Show.S02E02.1080p.mkv"), next(cur2, listOf(cur2, uhd, hd)))
    }

    @Test fun otherShowsServicesAndNonVideoIgnored() {
        val cur = f("Show.S01E01.mkv")
        val all = listOf(
            cur,
            f("Other.Show.S01E02.mkv"),
            f("Show.S01E02.mkv", provider = ProviderId.PREMIUMIZE),
            f("Show.S01E02.en.srt")
        )
        assertTrue(next(cur, all).isEmpty())
    }

    @Test fun moviesHaveNoNext() {
        val movie = f("Movie.2023.1080p.mkv")
        assertTrue(next(movie, listOf(movie, f("Movie.2024.1080p.mkv"))).isEmpty())
    }

    @Test fun limitRespected() {
        val all = (1..20).map { f("Show.S01E%02d.mkv".format(it)) }
        assertEquals(3, next(all.first(), all, limit = 3).size)
    }

    private fun previous(current: DownloadItem, all: List<DownloadItem>) =
        NextEpisode.previous(current, ReleaseNameParser.parse(current.filename), all.map(::c))
            .map { it.item.filename }

    @Test fun previousNearestFirst() {
        val e1 = f("Show.S01E01.mkv"); val e2 = f("Show.S01E02.mkv"); val e3 = f("Show.S01E03.mkv")
        assertEquals(listOf("Show.S01E02.mkv", "Show.S01E01.mkv"), previous(e3, listOf(e1, e3, e2)))
    }

    @Test fun previousCrossesBackIntoTheLastSeason() {
        val s1 = f("Show.S01E10.mkv", parent = "t:1"); val s2 = f("Show.S02E01.mkv", parent = "t:2")
        assertEquals(listOf("Show.S01E10.mkv"), previous(s2, listOf(s1, s2)))
    }

    @Test fun firstEpisodeHasNoPrevious() {
        val e1 = f("Show.S01E01.mkv")
        assertTrue(previous(e1, listOf(e1, f("Show.S01E02.mkv"))).isEmpty())
    }
}
