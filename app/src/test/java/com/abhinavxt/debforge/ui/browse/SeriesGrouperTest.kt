package com.abhinavxt.debforge.ui.browse

import com.abhinavxt.debforge.domain.DownloadItem
import com.abhinavxt.debforge.domain.ProviderId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SeriesGrouperTest {

    private fun item(name: String) = DownloadItem(
        id = name, provider = ProviderId.TORBOX, filename = name, sourceRef = "",
        downloadUrl = null, host = "", filesize = 1, maxConnections = 1, addedAt = null
    )

    @Test fun sxxexx() {
        val ep = SeriesGrouper.classify(item("The.Bear.S02E05.1080p.WEB.mkv"))!!
        assertEquals("the bear", ep.showKey)
        assertEquals(2, ep.season)
        assertEquals(5, ep.episode)
    }

    @Test fun nxnn() {
        val ep = SeriesGrouper.classify(item("Show Name 1x03.mp4"))!!
        assertEquals(1, ep.season)
        assertEquals(3, ep.episode)
    }

    // These two used to crash: their patterns have no "s" group.
    @Test fun episodeWord() {
        val ep = SeriesGrouper.classify(item("Some Show Episode 12.mkv"))!!
        assertNull(ep.season)
        assertEquals(12, ep.episode)
    }

    @Test fun animeDash() {
        val ep = SeriesGrouper.classify(item("[SubsPlease] Frieren - 12 (1080p) [ABCD1234].mkv"))!!
        assertEquals("frieren", ep.showKey) // release-group tag stripped
        assertNull(ep.season)
        assertEquals(12, ep.episode)
    }

    @Test fun nonEpisode() {
        assertNull(SeriesGrouper.classify(item("Oppenheimer.2023.2160p.mkv")))
    }
}
