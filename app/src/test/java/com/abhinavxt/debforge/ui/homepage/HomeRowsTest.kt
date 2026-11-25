package com.abhinavxt.debforge.ui.homepage

import com.abhinavxt.debforge.domain.DownloadItem
import com.abhinavxt.debforge.domain.ProviderId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeRowsTest {
    private fun item(name: String) = DownloadItem(
        id = name, provider = ProviderId.TORBOX, filename = name, sourceRef = name, downloadUrl = null,
        host = "", filesize = 1, maxConnections = 1, addedAt = null
    )

    @Test fun hiddenRowsRoundTripAndIgnoreUnknownKeys() {
        val hidden = setOf(HomeRow.RECENT, HomeRow.SHORTCUTS)
        assertEquals(hidden, HomeRow.parseHidden(HomeRow.encodeHidden(hidden)))
        assertEquals(setOf(HomeRow.DOWNLOADS), HomeRow.parseHidden("nope, downloads,,downloads"))
        assertTrue(HomeRow.parseHidden(null).isEmpty())
        assertTrue(HomeRow.parseHidden("").isEmpty())
    }

    @Test fun emptyAndHiddenRowsAreLeftOutInDisplayOrder() {
        val counts = mapOf(
            HomeRow.NEW_EPISODES to 2, HomeRow.CONTINUE_WATCHING to 1, HomeRow.RECENT to 5,
            HomeRow.DOWNLOADS to 0, HomeRow.SHORTCUTS to 3
        )
        assertEquals(
            listOf(HomeRow.CONTINUE_WATCHING, HomeRow.RECENT, HomeRow.NEW_EPISODES),
            visibleHomeRows(emptySet(), counts)
        )
        assertEquals(listOf(HomeRow.CONTINUE_WATCHING, HomeRow.NEW_EPISODES), visibleHomeRows(setOf(HomeRow.RECENT), counts))
    }

    @Test fun shortcutsAloneAreNotContent() {
        assertTrue(visibleHomeRows(emptySet(), mapOf(HomeRow.SHORTCUTS to 4)).isEmpty())
        assertTrue(visibleHomeRows(HomeRow.entries.toSet(), HomeRow.entries.associateWith { 1 }).isEmpty())
    }

    @Test fun recentTitlesKeepsOnePerShowAndOnlyVideos() {
        val titles = recentTitles(
            listOf(
                item("The.Bear.S02E05.1080p.WEB-DL.mkv"),
                item("The.Bear.S02E04.1080p.WEB-DL.mkv"),
                item("notes.txt"),
                item("Oppenheimer.2023.1080p.BluRay.mkv")
            )
        )
        assertEquals(listOf("The Bear", "Oppenheimer"), titles.map { it.title })
    }

    @Test fun recentTitlesStopsAtTheLimit() {
        val items = (1..30).map { item("Movie.Number.$it.2020.1080p.mkv") }
        assertEquals(RECENT_MAX, recentTitles(items).size)
        assertEquals(3, recentTitles(items, 3).size)
    }
}
