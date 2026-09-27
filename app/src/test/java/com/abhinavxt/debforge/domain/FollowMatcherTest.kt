package com.abhinavxt.debforge.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FollowMatcherTest {

    private val GB = 1024L * 1024 * 1024
    private val since = FollowMatcher.addedMillis("2026-09-01T00:00:00Z")!!
    private val bear = Follow(ProviderId.TORBOX, "show:the bear", "The Bear", since, resolution = "1080p")

    private var n = 0
    private fun item(
        name: String,
        added: String? = "2026-09-10T12:00:00Z",
        size: Long = 1 * GB,
        provider: ProviderId = ProviderId.TORBOX
    ) = DownloadItem(
        id = "id${n++}", provider = provider, filename = name, sourceRef = name, downloadUrl = null,
        host = "Torrent", filesize = size, maxConnections = 8, addedAt = added
    )

    private fun match(follows: List<Follow>, items: List<DownloadItem>, tracked: Set<String> = emptySet()) =
        FollowMatcher.match(follows, items, { ReleaseNameParser.parse(it.filename) }, tracked)

    @Test fun newEpisodeMatches() {
        val ep = item("The.Bear.S03E01.1080p.WEB-DL.x264-GRP.mkv")
        val m = match(listOf(bear), listOf(ep))
        assertEquals(listOf(ep), m.map { it.item })
        assertEquals("S03E01", m.single().episodeKey)
    }

    @Test fun olderThanFollowIsIgnored() =
        assertTrue(match(listOf(bear), listOf(item("The.Bear.S02E10.1080p.mkv", added = "2026-08-20T00:00:00Z"))).isEmpty())

    @Test fun unknownAgeIsIgnored() =
        assertTrue(match(listOf(bear), listOf(item("The.Bear.S03E01.1080p.mkv", added = null))).isEmpty())

    @Test fun offsetTimestampsWork() {
        val ep = item("The.Bear.S03E01.1080p.mkv", added = "2026-09-10T12:00:00+05:30")
        assertEquals(1, match(listOf(bear), listOf(ep)).size)
    }

    @Test fun otherShowsAndMoviesIgnored() {
        val items = listOf(item("Shogun.S01E01.1080p.mkv"), item("Oppenheimer.2023.1080p.mkv"))
        assertTrue(match(listOf(bear), items).isEmpty())
    }

    @Test fun otherServiceIgnored() =
        assertTrue(match(listOf(bear), listOf(item("The.Bear.S03E01.1080p.mkv", provider = ProviderId.PREMIUMIZE))).isEmpty())

    @Test fun seenEpisodesSkipped() =
        assertTrue(match(listOf(bear.copy(seen = setOf("S03E01"))), listOf(item("The.Bear.S03E01.1080p.mkv"))).isEmpty())

    @Test fun alreadyQueuedSkipped() {
        val ep = item("The.Bear.S03E01.1080p.mkv")
        assertTrue(match(listOf(bear), listOf(ep), tracked = setOf(ep.id)).isEmpty())
    }

    @Test fun oneFilePerEpisodePreferringFollowedQuality() {
        val uhd = item("The.Bear.S03E02.2160p.WEB-DL.mkv", size = 6 * GB)
        val hd = item("The.Bear.S03E02.1080p.WEB-DL.mkv", size = 2 * GB)
        val m = match(listOf(bear), listOf(uhd, hd))
        assertEquals(listOf(hd), m.map { it.item })
    }

    @Test fun largestWhenFollowedQualityMissing() {
        val small = item("The.Bear.S03E03.720p.mkv", size = 1 * GB)
        val big = item("The.Bear.S03E03.2160p.mkv", size = 5 * GB)
        assertEquals(listOf(big), match(listOf(bear), listOf(small, big)).map { it.item })
    }

    @Test fun nonVideoFilesIgnored() =
        assertTrue(match(listOf(bear), listOf(item("The.Bear.S03E01.1080p.srt"))).isEmpty())

    @Test fun severalEpisodesSorted() {
        val e2 = item("The.Bear.S03E02.1080p.mkv")
        val e1 = item("The.Bear.S03E01.1080p.mkv")
        assertEquals(listOf("S03E01", "S03E02"), match(listOf(bear), listOf(e2, e1)).map { it.episodeKey })
    }
}
