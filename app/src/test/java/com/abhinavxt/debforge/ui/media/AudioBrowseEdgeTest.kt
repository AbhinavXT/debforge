package com.abhinavxt.debforge.ui.media

import com.abhinavxt.debforge.data.local.DownloadEntity
import com.abhinavxt.debforge.domain.DownloadState
import com.abhinavxt.debforge.domain.ProviderId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioBrowseEdgeTest {
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

    // ---- empty input
    @Test fun emptyInputAllModes() {
        for (m in BrowseMode.values()) {
            assertTrue(groupsOf(emptyList(), m, root).isEmpty())
            assertTrue(groupsOf(emptyList(), m, root, "x").isEmpty())
            assertTrue(tracksOf(emptyList(), m, "k", root).isEmpty())
        }
    }

    @Test fun emptyRootString() {
        assertEquals("a", folderKeyOf("/x/a/b.mp3", ""))
        assertEquals("", folderKeyOf("/b.mp3", ""))
    }

    // ---- unicode and case merging
    @Test fun devanagariArtistsMergeByExactName() {
        val g = groupsOf(listOf(row("a", artist = "अरिजीत सिंह"), row("b", artist = " अरिजीत सिंह ")), BrowseMode.ARTISTS, root)
        assertEquals(1, g.size)
        assertEquals(2, g[0].trackCount)
    }

    @Test fun japaneseArtistsKeptSeparate() {
        val g = groupsOf(listOf(row("a", artist = "宇多田ヒカル"), row("b", artist = "米津玄師")), BrowseMode.ARTISTS, root)
        assertEquals(2, g.size)
    }

    @Test fun caseInsensitiveMergeLatinAndGreek() {
        val g = groupsOf(listOf(row("a", artist = "ΣΟΦΙΑ"), row("b", artist = "σοφια"), row("c", artist = "Daft Punk"), row("d", artist = "DAFT PUNK")), BrowseMode.ARTISTS, root)
        assertEquals(2, g.size)
    }

    @Test fun mergedGroupHasOneStableKey() {
        val g = groupsOf(listOf(row("a", artist = "Abba"), row("b", artist = "ABBA")), BrowseMode.ARTISTS, root).single()
        assertEquals(setOf("a", "b"), ids(tracksOf(listOf(row("a", artist = "Abba"), row("b", artist = "ABBA")), BrowseMode.ARTISTS, g.key, root)).toSet())
    }

    @Test fun whitespaceOnlyArtistAndAlbumAreUnknown() {
        val rows = listOf(row("a", artist = "   ", album = "\t "), row("b", artist = "", album = ""), row("c", artist = null, album = null))
        for (m in listOf(BrowseMode.ARTISTS, BrowseMode.ALBUMS)) {
            val g = groupsOf(rows, m, root).single()
            assertEquals(GroupMarker.UNKNOWN, g.marker)
            assertEquals("", g.key)
            assertEquals(3, g.trackCount)
        }
    }

    @Test fun unknownSortsLastEvenIfNamesAreLate() {
        val g = groupsOf(listOf(row("a", artist = null), row("b", artist = "Zzz"), row("c", artist = "Aaa")), BrowseMode.ARTISTS, root)
        assertEquals(listOf("Aaa", "Zzz", ""), g.map { it.name })
    }

    // ---- albums
    @Test fun sameAlbumOneTrackWithAlbumArtistOneWithout() {
        // spec: key = album + (albumArtist else artist). Same effective artist -> merged.
        val rows = listOf(row("a", artist = "X", album = "Hits", albumArtist = "X"), row("b", artist = "X", album = "Hits"))
        assertEquals(1, groupsOf(rows, BrowseMode.ALBUMS, root).size)
    }

    @Test fun sameAlbumAlbumArtistDiffersFromTrackArtistSplits() {
        // track a: albumArtist "Various"; track b: no albumArtist, artist "X" -> different keys per spec
        val rows = listOf(row("a", artist = "X", album = "Hits", albumArtist = "Various"), row("b", artist = "X", album = "Hits"))
        assertEquals(2, groupsOf(rows, BrowseMode.ALBUMS, root).size)
    }

    @Test fun compilationWithAlbumArtistStaysOneAlbum() {
        val rows = listOf(row("a", artist = "A", album = "Comp", albumArtist = "Various"), row("b", artist = "B", album = "Comp", albumArtist = "various"))
        assertEquals(1, groupsOf(rows, BrowseMode.ALBUMS, root).size)
    }

    @Test fun sameAlbumNameDifferentArtistsKeptApart() {
        val rows = listOf(row("a", artist = "A", album = "Greatest Hits"), row("b", artist = "B", album = "Greatest Hits"))
        val g = groupsOf(rows, BrowseMode.ALBUMS, root)
        assertEquals(2, g.size)
        assertEquals(setOf("A", "B"), g.map { it.secondary }.toSet())
    }

    @Test fun albumCaseAndWhitespaceMerge() {
        val rows = listOf(row("a", artist = "A", album = "Abbey Road"), row("b", artist = " a ", album = "  ABBEY ROAD "))
        assertEquals(1, groupsOf(rows, BrowseMode.ALBUMS, root).size)
    }

    @Test fun albumWithNoArtistAtAll() {
        val g = groupsOf(listOf(row("a", album = "Solo")), BrowseMode.ALBUMS, root).single()
        assertEquals("Solo", g.name)
        assertNull(g.secondary)
        assertNull(g.marker)
    }

    @Test fun trackOrderNullsLastThenTitle() {
        val rows = listOf(
            row("n1", title = "Zeta", artist = "A", album = "L"), row("n2", title = "alpha", artist = "A", album = "L"),
            row("t3", title = "c", artist = "A", album = "L", trackNo = 3), row("t1", title = "z", artist = "A", album = "L", trackNo = 1)
        )
        val g = groupsOf(rows, BrowseMode.ALBUMS, root).single()
        assertEquals(listOf("t1", "t3", "n2", "n1"), ids(g.tracks))
    }

    @Test fun duplicateTrackNumbersFallBackToTitleAndKeepAll() {
        val rows = listOf(
            row("x", title = "Beta", artist = "A", album = "L", trackNo = 2), row("y", title = "alpha", artist = "A", album = "L", trackNo = 2),
            row("z", title = "Gamma", artist = "A", album = "L", trackNo = 2)
        )
        val g = groupsOf(rows, BrowseMode.ALBUMS, root).single()
        assertEquals(listOf("y", "x", "z"), ids(g.tracks))
    }

    @Test fun hugeTrackNumberSortsBeforeNull() {
        val rows = listOf(row("n", artist = "A", album = "L"), row("h", artist = "A", album = "L", trackNo = Int.MAX_VALUE - 1))
        assertEquals(listOf("h", "n"), ids(groupsOf(rows, BrowseMode.ALBUMS, root).single().tracks))
    }

    @Test fun tracksOfAlbumIsOrderedLikeGroup() {
        val rows = listOf(row("b", artist = "A", album = "L", trackNo = 2), row("a", artist = "A", album = "L", trackNo = 1))
        val g = groupsOf(rows, BrowseMode.ALBUMS, root).single()
        assertEquals(ids(g.tracks), ids(tracksOf(rows, BrowseMode.ALBUMS, g.key, root)))
    }

    @Test fun sameAlbumNameDifferentCaseOfDelimiterDoesNotCollide() {
        // "a" + "bc" vs "ab" + "c" must not merge
        val rows = listOf(row("1", artist = "bc", album = "a"), row("2", artist = "c", album = "ab"))
        assertEquals(2, groupsOf(rows, BrowseMode.ALBUMS, root).size)
    }

    // ---- folder paths
    @Test fun safEncodedCharactersInTreeAreIgnored() {
        assertEquals("Music/eng", folderKeyOf("saf:content%3A%2F%2Fcom.android.externalstorage.documents%2Ftree%2Fprimary%3AMusic#Music/eng/a.mp3", "content://x"))
    }

    @Test fun safSegmentsWithSpacesAndPercentKept() {
        assertEquals("My Music/Old%20Hindi", folderKeyOf("saf:$tree#My Music/Old%20Hindi/a.mp3", "content://x"))
        assertEquals("a%2Fb", folderKeyOf("saf:$tree#a%2Fb/s.mp3", "content://x"))
    }

    @Test fun safDoubleSlashesAndTrailingIgnored() {
        assertEquals("Music/eng", folderKeyOf("saf:$tree#/Music//eng/a.mp3", "content://x"))
    }

    @Test fun safMalformedNoHashDoesNotCrash() {
        folderKeyOf("saf:$tree", "content://x")
        folderKeyOf("saf:#a.mp3", "content://x")
        folderKeyOf("saf:$tree#", "content://x")
    }

    @Test fun safPathWithFileRootUsesSafDirs() = assertEquals("A", folderKeyOf("saf:$tree#A/s.mp3", root))

    @Test fun trailingSlashesOnRoot() {
        assertEquals("Music", folderKeyOf("$root/Music/a.mp3", "$root///"))
        assertEquals("", folderKeyOf("$root/a.mp3", "$root/"))
    }

    @Test fun rootWithWhitespace() = assertEquals("Music", folderKeyOf("$root/Music/a.mp3", "  $root  "))

    @Test fun relativePathNoSlash() = assertEquals("", folderKeyOf("song.mp3", root))

    @Test fun relativePathWithFolder() = assertEquals("sub", folderKeyOf("sub/song.mp3", root))

    @Test fun windowsLikePathDoesNotCrash() {
        val k = folderKeyOf("C:\\Music\\a.mp3", root)
        assertNotNull(k)
    }

    @Test fun nullAndBlankPathAreRoot() {
        assertEquals("", folderKeyOf(null, root))
        assertEquals("", folderKeyOf("   ", root))
        val g = groupsOf(listOf(row("a", path = null)), BrowseMode.FOLDERS, root).single()
        assertEquals(GroupMarker.ROOT, g.marker)
    }

    @Test fun deepNestedUnderRootKeepsFullRelativePath() = assertEquals("a/b/c/d", folderKeyOf("$root/a/b/c/d/x.mp3", root))

    @Test fun unicodeFolderName() {
        val g = groupsOf(listOf(row("a", path = "$root/日本語/a.mp3"), row("b", path = "$root/हिन्दी/b.mp3")), BrowseMode.FOLDERS, root)
        assertEquals(setOf("日本語", "हिन्दी"), g.map { it.name }.toSet())
    }

    @Test fun nullPathAndRootPathMergeIntoOneRootGroup() {
        val g = groupsOf(listOf(row("a", path = null), row("b", path = "$root/b.mp3")), BrowseMode.FOLDERS, root)
        assertEquals(1, g.size)
        assertEquals(2, g[0].trackCount)
    }

    @Test fun folderCaseDifferenceStaysSeparate() {
        // folder keys are paths (case-sensitive filesystem); spec says sorted by path only.
        val g = groupsOf(listOf(row("a", path = "$root/Music/a.mp3"), row("b", path = "$root/music/b.mp3")), BrowseMode.FOLDERS, root)
        assertEquals(2, g.size)
    }

    // ---- search
    @Test fun searchMatchingGroupNameShowsWholeGroupAndMatchingTracksOfOthers() {
        val rows = listOf(
            row("a1", title = "One", artist = "Queen"), row("a2", title = "Two", artist = "Queen"),
            row("b1", title = "Queen of Hearts", artist = "Other"), row("b2", title = "Unrelated", artist = "Other")
        )
        val g = groupsOf(rows, BrowseMode.ARTISTS, root, "queen")
        assertEquals(2, g.size)
        val q = g.first { it.name == "Queen" }
        val o = g.first { it.name == "Other" }
        assertEquals(2, q.trackCount)
        assertEquals(listOf("b1"), ids(o.tracks))
        assertEquals(1, o.trackCount)
    }

    @Test fun searchNoMatchEmpty() = assertTrue(groupsOf(listOf(row("a", artist = "A")), BrowseMode.ARTISTS, root, "zzz").isEmpty())

    @Test fun whitespaceQueryIsNoFilter() {
        val rows = listOf(row("a", artist = "A"), row("b", artist = "B"))
        assertEquals(2, groupsOf(rows, BrowseMode.ARTISTS, root, "   ").size)
    }

    @Test fun searchUnicodeAndCase() {
        val rows = listOf(row("a", artist = "Björk"), row("b", artist = "Other"))
        assertEquals(1, groupsOf(rows, BrowseMode.ARTISTS, root, "BJÖRK").size)
    }

    @Test fun searchOnUnknownGroupFiltersTracksNotWholeGroup() {
        val rows = listOf(row("a", title = "hello"), row("b", title = "world"))
        val g = groupsOf(rows, BrowseMode.ARTISTS, root, "unknown").let { it }
        // "Unknown" is a marker, not a real name: must not match as a group name
        assertTrue(g.isEmpty())
    }

    @Test fun searchFolderNameMatchesWholeFolder() {
        val rows = listOf(row("a", title = "x", path = "$root/Rock/a.mp3"), row("b", title = "y", path = "$root/Rock/b.mp3"), row("c", title = "z", path = "$root/Pop/c.mp3"))
        val g = groupsOf(rows, BrowseMode.FOLDERS, root, "rock").single()
        assertEquals(2, g.trackCount)
    }

    @Test fun searchAlbumNameMatchesWholeAlbum() {
        val rows = listOf(row("a", title = "x", artist = "A", album = "Night"), row("b", title = "y", artist = "A", album = "Night"), row("c", title = "z", artist = "A", album = "Day"))
        assertEquals(2, groupsOf(rows, BrowseMode.ALBUMS, root, "night").single().trackCount)
    }

    @Test fun tracksOfUnknownKeyEmpty() = assertTrue(tracksOf(listOf(row("a", artist = "A")), BrowseMode.ARTISTS, "nope", root).isEmpty())

    @Test fun tracksOfWithQueryNarrows() {
        val rows = listOf(row("a", title = "foo", artist = "A"), row("b", title = "bar", artist = "A"))
        assertEquals(listOf("a"), ids(tracksOf(rows, BrowseMode.ARTISTS, "a", root, "foo")))
    }

    @Test fun trackCountMatchesTracksSize() {
        val rows = (1..20).map { row("r$it", artist = "A${it % 3}", album = "L${it % 2}", path = "$root/f${it % 4}/r$it.mp3") }
        for (m in BrowseMode.values()) for (g in groupsOf(rows, m, root)) assertEquals(g.trackCount, g.tracks.size)
    }

    @Test fun everyRowAppearsExactlyOnceInEachMode() {
        val rows = (1..50).map { row("r$it", artist = if (it % 5 == 0) null else "A${it % 7}", album = if (it % 3 == 0) "" else "L${it % 4}") }
        for (m in BrowseMode.values()) assertEquals(50, groupsOf(rows, m, root).flatMap { it.tracks }.map { it.id }.toSet().size)
    }

    // ---- perf
    @Test fun twoThousandRowsGroupQuickly() {
        val rows = (1..2000).map { row("r$it", title = "T$it", artist = "Artist${it % 150}", album = "Album${it % 300}", trackNo = it % 15, path = "$root/F${it % 40}/r$it.mp3") }
        val start = System.nanoTime()
        for (m in BrowseMode.values()) {
            groupsOf(rows, m, root)
            groupsOf(rows, m, root, "t1")
        }
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue("took ${ms}ms", ms < 5000)
    }

    // ---- group listed by its name: count and contents agree
    @Test fun nameMatchedGroupShowsAllTracks() {
        val rows = listOf(
            row("a", title = "One", artist = "Beatles", path = "$root/a.mp3"),
            row("b", title = "Two", artist = "Beatles", path = "$root/b.mp3")
        )
        val g = groupsOf(rows, BrowseMode.ARTISTS, root, "beat").single()
        assertEquals(2, g.trackCount)
        assertEquals(2, tracksOf(rows, BrowseMode.ARTISTS, g.key, root, "beat").size)
    }

    @Test fun trackMatchedGroupStillNarrows() {
        val rows = listOf(
            row("a", title = "One", artist = "X"),
            row("b", title = "Two", artist = "X")
        )
        val g = groupsOf(rows, BrowseMode.ARTISTS, root, "two").single()
        assertEquals(1, g.trackCount)
        assertEquals(listOf("b"), ids(tracksOf(rows, BrowseMode.ARTISTS, g.key, root, "two")))
    }
}
