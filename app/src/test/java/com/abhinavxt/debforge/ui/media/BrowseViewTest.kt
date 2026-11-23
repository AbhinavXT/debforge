package com.abhinavxt.debforge.ui.media

import com.abhinavxt.debforge.data.local.DownloadEntity
import com.abhinavxt.debforge.domain.DownloadState
import com.abhinavxt.debforge.domain.ProviderId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class BrowseViewTest {
    private fun row(id: String, artist: String? = null, album: String? = null, trackNo: Int? = null) = DownloadEntity(
        id = id, provider = ProviderId.REAL_DEBRID, filename = "$id.mp3", sourceRef = "", downloadUrl = "", host = "",
        filesize = 1, chunksAllowed = 1, state = DownloadState.COMPLETED, artist = artist, album = album, trackNo = trackNo,
        finalFilePath = "/music/$id.mp3"
    )

    private val rows = listOf(
        row("a", "X", "Alb", 2), row("b", "X", "Alb", 1), row("c", "Y", null)
    )
    private fun ids(l: List<DownloadEntity>) = l.map { it.id }

    @Test fun reservedIds() {
        assertEquals(BrowseMode.FOLDERS, browseModeOf("folders"))
        assertEquals(BrowseMode.ARTISTS, browseModeOf("artists"))
        assertEquals(BrowseMode.ALBUMS, browseModeOf("albums"))
        assertNull(browseModeOf(null))
        assertNull(browseModeOf("fav"))
        assertNull(browseModeOf("some-playlist-id"))
    }

    @Test fun nonBrowsePickIsNull() = assertNull(browseView(rows, "fav", null, "/music", ""))

    @Test fun closedShowsGroups() {
        val v = browseView(rows, "artists", null, "/music", "")!!
        assertEquals(listOf("x", "y"), v.groups!!.map { it.key })
        assertNull(v.open)
        assertEquals(emptyList<DownloadEntity>(), v.tracks)
    }

    @Test fun openShowsTracksOnly() {
        val key = groupsOf(rows, BrowseMode.ALBUMS, "/music").first().key
        val v = browseView(rows, "albums", key, "/music", "")!!
        assertNull(v.groups)
        assertNotNull(v.open)
        assertEquals(listOf("b", "a"), ids(v.tracks)) // track-number order
    }

    @Test fun queryFiltersInsideOpenGroupButKeepsHeader() {
        val v = browseView(rows, "artists", "x", "/music", "zzz")!!
        assertEquals("x", v.open!!.key)
        assertEquals(emptyList<DownloadEntity>(), v.tracks)
    }

    @Test fun missingOpenGroupFallsBackToList() {
        val v = browseView(rows, "artists", "gone", "/music", "")!!
        assertNull(v.open)
        assertEquals(2, v.groups!!.size)
    }

    @Test fun queryNarrowsGroupList() {
        val v = browseView(rows, "artists", null, "/music", "c")!!
        assertEquals(listOf("y"), v.groups!!.map { it.key })
    }
}
