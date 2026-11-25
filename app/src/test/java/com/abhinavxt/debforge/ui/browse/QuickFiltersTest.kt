package com.abhinavxt.debforge.ui.browse

import com.abhinavxt.debforge.domain.DownloadItem
import com.abhinavxt.debforge.domain.ProviderId
import com.abhinavxt.debforge.domain.ReleaseNameParser
import com.abhinavxt.debforge.ui.browse.QuickFilter.MOVIES
import com.abhinavxt.debforge.ui.browse.QuickFilter.SHOWS
import com.abhinavxt.debforge.ui.browse.QuickFilter.UHD
import com.abhinavxt.debforge.ui.browse.QuickFilter.UNWATCHED
import org.junit.Assert.assertEquals
import org.junit.Test

class QuickFiltersTest {
    private fun item(name: String) = DownloadItem(
        id = name, provider = ProviderId.TORBOX, filename = name, sourceRef = name, downloadUrl = null,
        host = "", filesize = 1, maxConnections = 1, addedAt = null
    )

    private val movie4k = item("Dune.2021.2160p.BluRay.mkv")
    private val movieHd = item("Heat.1995.1080p.BluRay.mkv")
    private val ep1 = item("The.Bear.S01E01.1080p.WEB-DL.mkv")
    private val ep24k = item("The.Bear.S01E02.2160p.WEB-DL.mkv")
    private val doc = item("notes.txt")
    private val all = listOf(movie4k, movieHd, ep1, ep24k, doc)
    private val parsed = all.associate { it.id to ReleaseNameParser.parse(it.filename) }

    private fun run(filters: Set<QuickFilter>, watched: Set<String> = emptySet()) =
        applyQuickFilters(all, parsed, filters) { i, _ -> i.id in watched }

    @Test fun noFiltersKeepsEverything() = assertEquals(all, run(emptySet()))

    @Test fun moviesOnly() = assertEquals(listOf(movie4k, movieHd), run(setOf(MOVIES)))

    @Test fun showsOnly() = assertEquals(listOf(ep1, ep24k), run(setOf(SHOWS)))

    @Test fun fourKCombinesWithAnd() {
        assertEquals(listOf(movie4k, ep24k), run(setOf(UHD)))
        assertEquals(listOf(ep24k), run(setOf(SHOWS, UHD)))
    }

    @Test fun unwatchedDropsWatchedFiles() =
        assertEquals(listOf(ep24k), run(setOf(SHOWS, UNWATCHED), watched = setOf(ep1.id)))

    @Test fun moviesAndShowsExcludeEachOther() {
        assertEquals(setOf(SHOWS), setOf(MOVIES).toggled(SHOWS))
        assertEquals(setOf(MOVIES, UHD), setOf(SHOWS, UHD).toggled(MOVIES))
    }

    @Test fun togglingTwiceClears() = assertEquals(emptySet<QuickFilter>(), emptySet<QuickFilter>().toggled(UHD).toggled(UHD))

    @Test fun nothingMatchingGivesEmpty() = assertEquals(emptyList<DownloadItem>(), run(setOf(MOVIES, SHOWS)))
}
