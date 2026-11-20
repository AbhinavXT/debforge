package com.abhinavxt.debforge.ui.media

import com.abhinavxt.debforge.data.local.DownloadEntity
import com.abhinavxt.debforge.domain.DownloadState
import com.abhinavxt.debforge.domain.ProviderId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioBrowseTest {
    private val root = "/storage/emulated/0/Download/DebForge"
    private val tree = "content%3A%2F%2Fx"

    private fun row(
        id: String, title: String = id, artist: String? = null, album: String? = null, albumArtist: String? = null,
        trackNo: Int? = null, path: String? = "$root/$id.mp3"
    ) = DownloadEntity(
        id = id, provider = ProviderId.REAL_DEBRID, filename = "$id.mp3", sourceRef = "", downloadUrl = "", host = "",
        filesize = 1, chunksAllowed = 1, state = DownloadState.COMPLETED, finalFilePath = path,
        title = title, artist = artist, album = album, albumArtist = albumArtist, trackNo = trackNo
    )

    private fun ids(l: List<DownloadEntity>) = l.map { it.id }

    @Test fun safPathKey() = assertEquals("Music/eng", folderKeyOf("saf:$tree#Music/eng/song.mp3", "content://x"))
    @Test fun safTopLevelIsRoot() = assertEquals("", folderKeyOf("saf:$tree#song.mp3", "content://x"))
    @Test fun filePathUnderRoot() = assertEquals("Music/eng", folderKeyOf("$root/Music/eng/a.mp3", root))
    @Test fun filePathUnderRootWithTrailingSlash() = assertEquals("Music", folderKeyOf("$root/Music/a.mp3", "$root/"))
    @Test fun filePathDirectlyInRoot() = assertEquals("", folderKeyOf("$root/a.mp3", root))
    @Test fun filePathOutsideRootUsesParentName() = assertEquals("Podcasts", folderKeyOf("/sdcard/Other/Podcasts/a.mp3", root))
    @Test fun siblingWithSharedPrefixIsOutside() = assertEquals("DebForge2", folderKeyOf("$root" + "2/a.mp3", root))
    @Test fun nullPathIsRoot() = assertEquals("", folderKeyOf(null, root))

    @Test fun folderGroupsSortedRootFirst() {
        val rows = listOf(row("a", path = "$root/Zed/a.mp3"), row("b", path = "$root/b.mp3"), row("c", path = "$root/alpha/c.mp3"))
        val g = groupsOf(rows, BrowseMode.FOLDERS, root)
        assertEquals(listOf("", "alpha", "Zed"), g.map { it.key })
        assertEquals(GroupMarker.ROOT, g[0].marker)
        assertNull(g[1].marker)
        assertEquals("alpha", g[1].name)
    }

    @Test fun nestedFolderNameIsPathAsKeyLastSegmentAsName() {
        val g = groupsOf(listOf(row("a", path = "$root/Music/eng/a.mp3")), BrowseMode.FOLDERS, root).single()
        assertEquals("Music/eng", g.key)
        assertEquals("eng", g.name)
    }

    @Test fun blankArtistIsUnknownAndLast() {
        val rows = listOf(row("a", artist = " "), row("b", artist = null), row("c", artist = "Zed"))
        val g = groupsOf(rows, BrowseMode.ARTISTS, root)
        assertEquals(listOf("zed", ""), g.map { it.key })
        assertEquals(GroupMarker.UNKNOWN, g[1].marker)
        assertEquals(2, g[1].trackCount)
    }

    @Test fun artistsMergeCaseAndWhitespace() {
        val rows = listOf(row("a", artist = "Daft Punk"), row("b", artist = "  daft punk "), row("c", artist = "DAFT PUNK"))
        val g = groupsOf(rows, BrowseMode.ARTISTS, root).single()
        assertEquals(3, g.trackCount)
        assertEquals("Daft Punk", g.name)
    }

    @Test fun artistCreditsNotSplit() {
        val rows = listOf(row("a", artist = "A & B"), row("b", artist = "A"), row("c", artist = "B"))
        assertEquals(3, groupsOf(rows, BrowseMode.ARTISTS, root).size)
    }

    @Test fun blankAlbumIsUnknown() {
        val rows = listOf(row("a", album = ""), row("b", album = null), row("c", album = "X", artist = "Q"))
        val g = groupsOf(rows, BrowseMode.ALBUMS, root)
        assertEquals(listOf("x\u0000q", ""), g.map { it.key })
        assertEquals(GroupMarker.UNKNOWN, g[1].marker)
        assertEquals(2, g[1].trackCount)
    }

    @Test fun sameAlbumNameDifferentAlbumArtistsApart() {
        val rows = listOf(
            row("a", album = "Greatest Hits", albumArtist = "Queen"),
            row("b", album = "greatest hits ", albumArtist = " queen"),
            row("c", album = "Greatest Hits", albumArtist = "ABBA")
        )
        val g = groupsOf(rows, BrowseMode.ALBUMS, root)
        assertEquals(2, g.size)
        assertEquals(listOf("ABBA", "Queen"), g.map { it.secondary })
        assertEquals(2, g[1].trackCount)
    }

    @Test fun albumArtistFallsBackToArtist() {
        val rows = listOf(row("a", album = "LP", artist = "Foo"), row("b", album = "LP", artist = "Bar"), row("c", album = "LP", artist = "foo"))
        val g = groupsOf(rows, BrowseMode.ALBUMS, root)
        assertEquals(2, g.size)
        assertEquals(listOf("Bar", "Foo"), g.map { it.secondary })
    }

    @Test fun albumAlbumArtistWinsOverTrackArtist() {
        val rows = listOf(row("a", album = "LP", albumArtist = "VA", artist = "One"), row("b", album = "LP", albumArtist = "VA", artist = "Two"))
        val g = groupsOf(rows, BrowseMode.ALBUMS, root).single()
        assertEquals(2, g.trackCount)
        assertEquals("VA", g.secondary)
    }

    @Test fun albumTracksByTrackNoNullsLastThenTitle() {
        val rows = listOf(
            row("a", title = "Zeta", album = "LP", trackNo = null),
            row("b", title = "Two", album = "LP", trackNo = 2),
            row("c", title = "Alpha", album = "LP", trackNo = null),
            row("d", title = "One", album = "LP", trackNo = 1)
        )
        val key = groupsOf(rows, BrowseMode.ALBUMS, root).single().key
        assertEquals(listOf("d", "b", "c", "a"), ids(tracksOf(rows, BrowseMode.ALBUMS, key, root)))
        assertEquals(listOf("d", "b", "c", "a"), ids(groupsOf(rows, BrowseMode.ALBUMS, root).single().tracks))
    }

    @Test fun groupNameSortIgnoresCase() {
        val rows = listOf(row("a", artist = "beta"), row("b", artist = "Alpha"), row("c", artist = "Gamma"))
        assertEquals(listOf("Alpha", "beta", "Gamma"), groupsOf(rows, BrowseMode.ARTISTS, root).map { it.name })
    }

    @Test fun searchMatchingGroupNameShowsWholeGroup() {
        val rows = listOf(row("a", title = "One", artist = "Daft Punk"), row("b", title = "Two", artist = "Daft Punk"), row("c", artist = "Other"))
        val g = groupsOf(rows, BrowseMode.ARTISTS, root, "daft").single()
        assertEquals(2, g.trackCount)
    }

    @Test fun searchByTrackKeepsOnlyMatchingTracksAndDropsEmptyGroups() {
        val rows = listOf(row("a", title = "Around", artist = "X"), row("b", title = "Beyond", artist = "X"), row("c", title = "Cold", artist = "Y"))
        val g = groupsOf(rows, BrowseMode.ARTISTS, root, " around ").single()
        assertEquals(1, g.trackCount)
        assertEquals(listOf("a"), ids(g.tracks))
    }

    @Test fun searchInsideGroupFiltersTracks() {
        val rows = listOf(row("a", title = "Around", artist = "X"), row("b", title = "Beyond", artist = "X"))
        assertEquals(listOf("b"), ids(tracksOf(rows, BrowseMode.ARTISTS, "x", root, "bey")))
        assertEquals(listOf("a", "b"), ids(tracksOf(rows, BrowseMode.ARTISTS, "x", root, "")))
    }

    @Test fun searchMatchesFilenameAndArtist() {
        val rows = listOf(row("a", title = "T", artist = "Zz", album = "Q"))
        assertEquals(1, groupsOf(rows, BrowseMode.ALBUMS, root, "zz").size)
        assertEquals(1, groupsOf(rows, BrowseMode.ALBUMS, root, "a.mp3").size)
        assertEquals(0, groupsOf(rows, BrowseMode.ALBUMS, root, "nope").size)
    }

    @Test fun unknownGroupNotMatchedByName() {
        val rows = listOf(row("a", title = "T", artist = null))
        assertEquals(0, groupsOf(rows, BrowseMode.ARTISTS, root, "unknown").size)
    }

    @Test fun folderTracksKeepInputOrder() {
        val rows = listOf(row("b", path = "$root/F/b.mp3"), row("a", path = "$root/F/a.mp3"), row("c", path = "$root/c.mp3"))
        assertEquals(listOf("b", "a"), ids(tracksOf(rows, BrowseMode.FOLDERS, "F", root)))
        assertEquals(listOf("c"), ids(tracksOf(rows, BrowseMode.FOLDERS, "", root)))
    }
}
