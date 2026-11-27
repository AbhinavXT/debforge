package com.abhinavxt.debforge.download.watch

import com.abhinavxt.debforge.domain.DownloadItem
import com.abhinavxt.debforge.domain.ProviderId
import org.junit.Assert.assertEquals
import org.junit.Test

class WatchLogicTest {
    private val tb = ProviderId.TORBOX
    private fun watch(notifyOnly: Boolean = false) = WatchEntity(tb, "j", "n", notifyOnly = notifyOnly)
    private fun item(name: String) = DownloadItem(id = name, provider = tb, filename = name, sourceRef = name, downloadUrl = null, host = "", filesize = 1, maxConnections = 1, addedAt = null)

    @Test fun notifyOnlyReadyNotifies() = assertEquals(WatchStep.NOTIFY, watchStep(watch(notifyOnly = true), setOf(tb), ready = true))
    @Test fun normalReadyEnqueues() = assertEquals(WatchStep.ENQUEUE, watchStep(watch(), setOf(tb), ready = true))
    @Test fun notReadyWaits() = assertEquals(WatchStep.WAIT, watchStep(watch(notifyOnly = true), setOf(tb), ready = false))
    @Test fun signedOutProviderIsDropped() {
        assertEquals(WatchStep.DROP, watchStep(watch(), emptySet(), ready = true))
        assertEquals(WatchStep.DROP, watchStep(watch(), emptySet(), ready = false))
    }

    @Test fun nullSelectionKeepsAll() {
        val files = listOf(item("a.mkv"), item("b.mkv"))
        assertEquals(files, filterSelected(files, null))
    }

    @Test fun selectionMatchesByPathSuffix() {
        val files = listOf(item("Folder/S01/a.mkv"), item("Folder/S01/b.mkv"), item("xa.mkv"), item("c.mkv"))
        assertEquals(listOf("Folder/S01/a.mkv", "c.mkv"), filterSelected(files, "S01/a.mkv\nc.mkv").map { it.filename })
        assertEquals(emptyList<DownloadItem>(), filterSelected(files, ""))
    }

    @Test fun periodBacksOffAfterSixHours() {
        val now = 100L * 3_600_000
        assertEquals(15L, watchPeriodMinutes(now - 6 * 3_600_000, now))
        assertEquals(60L, watchPeriodMinutes(now - 6 * 3_600_000 - 1, now))
    }
}
