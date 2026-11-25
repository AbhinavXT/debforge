package com.abhinavxt.debforge.ui.browse

import com.abhinavxt.debforge.domain.DownloadItem
import com.abhinavxt.debforge.domain.ProviderId
import org.junit.Assert.assertEquals
import org.junit.Test

class RemovalScopeTest {
    private fun item(id: String, parent: String?) = DownloadItem(
        id = id, provider = ProviderId.TORBOX, filename = id, sourceRef = id, downloadUrl = null,
        host = "", filesize = 1, maxConnections = 1, addedAt = null, parentRef = parent
    )

    // The key is the torrent, as with services that remove whole torrents.
    private val key: (DownloadItem) -> String? = { it.parentRef ?: it.id }

    private val a1 = item("a1", "A")
    private val a2 = item("a2", "A")
    private val a3 = item("a3", "A")
    private val solo = item("s", null)

    @Test fun singleFileIsPlain() = assertEquals(RemovalScope(false), removalScope(listOf(solo), listOf(solo, a1), true, key))

    @Test fun pickingEveryLoadedFileIsPlain() =
        assertEquals(RemovalScope(false), removalScope(listOf(a1, a2, a3), listOf(a1, a2, a3), true, key))

    @Test fun pickingSomeSaysWholeTorrentWithCount() =
        assertEquals(RemovalScope(true, 3), removalScope(listOf(a1), listOf(a1, a2, a3), true, key))

    @Test fun hiddenSiblingsEvenWhenNotAllPagesAreLoadedAreGeneric() =
        assertEquals(RemovalScope(true), removalScope(listOf(a1), listOf(a1, a2), false, key))

    @Test fun unloadedPagesMayHideSiblings() =
        assertEquals(RemovalScope(true), removalScope(listOf(a1), listOf(a1), false, key))

    @Test fun nothingRemovable() = assertEquals(RemovalScope(false), removalScope(listOf(a1), listOf(a1), true) { null })
}
