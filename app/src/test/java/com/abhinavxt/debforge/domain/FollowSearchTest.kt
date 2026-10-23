package com.abhinavxt.debforge.domain

import com.abhinavxt.debforge.data.follow.FollowBackup
import com.abhinavxt.debforge.data.search.SearchHistoryStore
import com.abhinavxt.debforge.data.search.Torznab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FollowSearchTest {

    private val since = 1_800_000_000_000L
    private val after = since + 86_400_000L

    private fun r(
        title: String, hash: String = title.hashCode().toString(), seeders: Int = 10,
        cached: Boolean? = null, published: Long? = after
    ) = SearchResult(hash, title, "magnet:?xt=urn:btih:$hash", 1, seeders, null, null, cached, null, published)

    private val bear = Follow(ProviderId.TORBOX, "show:the bear", "The Bear", since, resolution = "1080p", autoSearch = true)

    @Test fun picksOneCopyPerNewEpisode() {
        val picks = FollowSearch.pick(
            bear,
            listOf(
                r("The.Bear.S03E01.2160p.WEB", seeders = 500),
                r("The.Bear.S03E01.1080p.WEB", seeders = 50),
                r("The.Bear.S03E02.720p.WEB", seeders = 5),
                r("The.Bear.S03E02.720p.HDTV", seeders = 1, cached = true)
            )
        )
        assertEquals(listOf("S03E01", "S03E02"), picks.map { it.episodeKey })
        assertEquals("The.Bear.S03E01.1080p.WEB", picks[0].result.title) // followed quality beats seeders
        assertEquals("The.Bear.S03E02.720p.HDTV", picks[1].result.title) // cached beats everything
    }

    @Test fun preferredWordsBreakTiesAfterQuality() {
        val picks = FollowSearch.pick(
            bear,
            listOf(
                r("The.Bear.S03E01.1080p.WEB.x264", seeders = 500),
                r("The.Bear.S03E01.1080p.WEB.x265", seeders = 5).copy(preferred = 1),
                r("The.Bear.S03E01.2160p.WEB.x265", seeders = 50).copy(preferred = 1)
            )
        )
        assertEquals("The.Bear.S03E01.1080p.WEB.x265", picks.single().result.title)
    }

    @Test fun skipsOldUnknownHandledPacksAndOtherShows() {
        val follow = bear.copy(seen = setOf("S03E03"), requested = setOf("S03E04"))
        val picks = FollowSearch.pick(
            follow,
            listOf(
                r("The.Bear.S03E01.1080p", published = since - 1),   // listed before following
                r("The.Bear.S03E02.1080p", published = null),        // unknown date
                r("The.Bear.S03E03.1080p"),                          // already queued
                r("The.Bear.S03E04.1080p"),                          // already sent
                r("The.Bear.S03.1080p.WEB"),                         // season pack
                r("Shogun.S01E05.1080p"),                            // another show
                r("The.Bear.S03E05.1080p")
            )
        )
        assertEquals(listOf("S03E05"), picks.map { it.episodeKey })
    }

    @Test fun episodeFilterKeepsPacksOfThatSeason() {
        val all = listOf(r("The.Bear.S02E05.1080p"), r("The.Bear.S02E06.1080p"), r("The.Bear.S02.1080p"), r("The.Bear.S01.1080p"), r("The Bear Season 2 Complete"))
        assertEquals(
            listOf("The.Bear.S02E05.1080p", "The.Bear.S02.1080p", "The Bear Season 2 Complete"),
            SearchResults.forEpisode(all, 2, 5).map { it.title }
        )
        assertEquals(4, SearchResults.forEpisode(all, 2, null).size)
        assertEquals(all, SearchResults.forEpisode(all, null, null))
    }

    @Test fun seasonPacks() {
        assertEquals(2, SearchResults.seasonPackOf("The.Bear.S02.1080p.WEB"))
        assertEquals(3, SearchResults.seasonPackOf("The Bear Season 3"))
        assertNull(SearchResults.seasonPackOf("The.Bear.S02E05.1080p"))
        assertNull(SearchResults.seasonPackOf("Oppenheimer.2023.1080p"))
    }

    @Test fun torznabParams() {
        assertEquals(
            mapOf("t" to "tvsearch", "q" to "The Bear", "imdbid" to "14452776", "season" to "2", "ep" to "5"),
            Torznab.searchParams("The Bear", SearchKind.SHOW, 2, 5, "tt14452776")
        )
        assertEquals(mapOf("t" to "movie", "q" to "Oppenheimer"), Torznab.searchParams("Oppenheimer", SearchKind.MOVIE, null, null, null))
        // No season: the episode alone means nothing to an indexer.
        assertEquals(mapOf("t" to "tvsearch", "q" to "X"), Torznab.searchParams("X", SearchKind.SHOW, null, 5, null))
        assertEquals(mapOf("t" to "search", "q" to "X"), Torznab.searchParams("X", SearchKind.ALL, 1, 1, "tt1"))
        assertEquals("The Bear S02E05", Torznab.plainQuery("The Bear", 2, 5))
        assertEquals("The Bear S02", Torznab.plainQuery("The Bear", 2, null))
    }

    @Test fun historyNewestFirstWithoutRepeats() {
        val list = SearchHistoryStore.remember(listOf("oppenheimer", "The Bear"), "the bear")
        assertEquals(listOf("the bear", "oppenheimer"), list)
        val full = (1..15).fold(emptyList<String>()) { acc, i -> SearchHistoryStore.remember(acc, "q$i") }
        assertEquals(SearchHistoryStore.MAX, full.size)
        assertEquals("q15", full.first())
    }

    @Test fun backupKeepsSearchFollow() {
        val f = bear.copy(requested = setOf("S03E01"))
        assertEquals(listOf(f), FollowBackup.fromJson(FollowBackup.toJson(listOf(f))))
        // A phone follow from the Library merged with a search follow from the backup turns search on.
        val (merged, _) = FollowBackup.merge(listOf(bear.copy(autoSearch = false)), listOf(f))
        assertTrue(merged.single().autoSearch)
        assertEquals(setOf("S03E01"), merged.single().requested)
    }
}
