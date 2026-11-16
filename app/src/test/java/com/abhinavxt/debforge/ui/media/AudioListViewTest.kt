package com.abhinavxt.debforge.ui.media

import com.abhinavxt.debforge.data.local.DownloadEntity
import com.abhinavxt.debforge.data.playback.AudioListsState
import com.abhinavxt.debforge.data.playback.Playlist
import com.abhinavxt.debforge.domain.DownloadState
import com.abhinavxt.debforge.domain.ProviderId
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioListViewTest {
    private fun row(id: String) = DownloadEntity(
        id = id, provider = ProviderId.REAL_DEBRID, filename = "$id.m4a", sourceRef = "", downloadUrl = "", host = "",
        filesize = 1, chunksAllowed = 1, state = DownloadState.COMPLETED
    )

    private val rows = listOf(row("a"), row("b"), row("c"))
    private val lists = AudioListsState(setOf("c", "a", "gone"), listOf(Playlist("p", "P", listOf("c", "gone", "a"))))
    private fun ids(l: List<DownloadEntity>) = l.map { it.id }

    @Test fun nullReturnsAll() = assertEquals(ids(rows), ids(audioListView(rows, lists, null)))
    @Test fun favouritesKeepRowOrder() = assertEquals(listOf("a", "c"), ids(audioListView(rows, lists, "fav")))
    @Test fun playlistOrderWinsAndMissingHidden() = assertEquals(listOf("c", "a"), ids(audioListView(rows, lists, "p")))
    @Test fun playlistOrderIndependentOfRowOrder() =
        assertEquals(listOf("c", "a"), ids(audioListView(rows.reversed(), lists, "p")))
    @Test fun unknownPlaylistFallsBackToAll() = assertEquals(ids(rows), ids(audioListView(rows, lists, "zzz")))
}
