package com.abhinavxt.debforge.ui.media

import com.abhinavxt.debforge.data.local.DownloadEntity
import com.abhinavxt.debforge.domain.DownloadState
import com.abhinavxt.debforge.domain.ProviderId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowseViewEdgeTest {
    private val root = "/d"
    private fun row(id: String, artist: String? = null, title: String = id) = DownloadEntity(
        id = id, provider = ProviderId.REAL_DEBRID, filename = "$id.mp3", sourceRef = "", downloadUrl = "", host = "",
        filesize = 1, chunksAllowed = 1, state = DownloadState.COMPLETED, finalFilePath = "$root/$id.mp3", title = title, artist = artist
    )

    @Test fun browseModeOfKnownAndUnknown() {
        assertEquals(BrowseMode.FOLDERS, browseModeOf("folders"))
        assertEquals(BrowseMode.ARTISTS, browseModeOf("artists"))
        assertEquals(BrowseMode.ALBUMS, browseModeOf("albums"))
        assertNull(browseModeOf(null))
        assertNull(browseModeOf(""))
        assertNull(browseModeOf("Folders"))
        assertNull(browseModeOf("favourites"))
        assertNull(browseModeOf("podcasts"))
    }

    @Test fun nonBrowsePickGivesNull() = assertNull(browseView(listOf(row("a")), "podcasts", null, root, ""))

    @Test fun emptyAudioGroupListEmpty() {
        val v = browseView(emptyList(), "artists", null, root, "")!!
        assertTrue(v.groups!!.isEmpty())
        assertNull(v.open)
        assertTrue(v.tracks.isEmpty())
    }

    @Test fun staleOpenKeyFallsBackToList() {
        val v = browseView(listOf(row("a", "A")), "artists", "gone", root, "")!!
        assertNotNull(v.groups)
        assertNull(v.open)
    }

    @Test fun openKeyOnEmptyListFallsBack() {
        val v = browseView(emptyList(), "albums", "x", root, "")!!
        assertNotNull(v.groups)
        assertNull(v.open)
    }

    @Test fun openGroupIgnoresQueryForGroupLookupButFiltersTracks() {
        val rows = listOf(row("a", "A", "foo"), row("b", "A", "bar"))
        val v = browseView(rows, "artists", "a", root, "foo")!!
        assertEquals("a", v.open!!.key)
        assertEquals(listOf("a"), v.tracks.map { it.id })
    }

    @Test fun openGroupWhoseTracksAllFilteredOutStaysOpen() {
        val v = browseView(listOf(row("a", "A", "foo")), "artists", "a", root, "zzz")!!
        assertNotNull(v.open)
        assertTrue(v.tracks.isEmpty())
    }

    @Test fun openUnknownGroupWithEmptyKey() {
        val v = browseView(listOf(row("a", null)), "artists", "", root, "")!!
        assertNotNull(v.open)
        assertEquals(1, v.tracks.size)
    }

    @Test fun queryAppliedToGroupListWhenNothingOpen() {
        val v = browseView(listOf(row("a", "A"), row("b", "B")), "artists", null, root, "B")!!
        assertEquals(1, v.groups!!.size)
    }
}
