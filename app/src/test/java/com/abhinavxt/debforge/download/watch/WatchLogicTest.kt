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
    @Test fun mutedIsSkippedEvenWhenReady() = assertEquals(WatchStep.SKIP, watchStep(WatchEntity(tb, "j", "n", muted = true), setOf(tb), ready = true))
    @Test fun mutedIsPurgedAfterFourteenDays() =
        assertEquals(WatchStep.DROP, watchStep(WatchEntity(tb, "j", "n", createdAt = 0, muted = true), setOf(tb), ready = true, now = PURGE_AFTER_MS + 1))
    @Test fun notReadyWaits() = assertEquals(WatchStep.WAIT, watchStep(watch(notifyOnly = true), setOf(tb), ready = false))
    @Test fun signedOutProviderIsSkippedNotDropped() {
        assertEquals(WatchStep.SKIP, watchStep(watch(), emptySet(), ready = true))
        assertEquals(WatchStep.SKIP, watchStep(watch(), emptySet(), ready = false))
    }

    @Test fun staleNotifyOnlyIsKeptButNotPolledThenPurged() {
        val w = watch(notifyOnly = true)
        assertEquals(WatchStep.WAIT, watchStep(w, setOf(tb), false, w.createdAt + GIVE_UP_AFTER_MS))
        assertEquals(WatchStep.SKIP, watchStep(w, setOf(tb), false, w.createdAt + GIVE_UP_AFTER_MS + 1))
        assertEquals(WatchStep.DROP, watchStep(w, setOf(tb), false, w.createdAt + PURGE_AFTER_MS + 1))
    }

    @Test fun oldAutoDownloadWatchIsStillPolled() =
        assertEquals(WatchStep.WAIT, watchStep(watch(), setOf(tb), false, System.currentTimeMillis() + PURGE_AFTER_MS * 2))

    @Test fun staleSignedOutAutoWatchIsPurged() {
        val w = watch()
        assertEquals(WatchStep.DROP, watchStep(w, emptySet(), false, w.createdAt + PURGE_AFTER_MS + 1))
    }

    @Test fun nullSelectionKeepsAll() {
        val files = listOf(item("a.mkv"), item("b.mkv"))
        assertEquals(files, filterSelected(files, null))
    }

    @Test fun selectionMatchesByPathSuffix() {
        val files = listOf(item("Folder/S01/a.mkv"), item("Folder/S01/b.mkv"), item("xa.mkv"), item("c.mkv"))
        assertEquals(listOf("Folder/S01/a.mkv", "c.mkv"), filterSelected(files, "S01/a.mkv\nc.mkv").map { it.filename })
    }

    @Test fun torrentPathWithFolderMatchesServiceName() {
        val files = listOf(item("a.mkv"), item("b.mkv"))
        assertEquals(listOf("a.mkv"), filterSelected(files, "Folder/a.mkv").map { it.filename })
    }

    @Test fun suffixNeedsSlashBoundary() {
        val files = listOf(item("xa.mkv"), item("b.mkv"))
        assertEquals(listOf("b.mkv"), filterSelected(files, "Folder/b.mkv\nFolder/xb.mkv").map { it.filename })
        assertEquals(listOf("xa.mkv"), filterSelected(files, "a.mkv\nxa.mkv").map { it.filename })
    }

    @Test fun noMatchOrEmptySelectionFallsBackToAll() {
        val files = listOf(item("a.mkv"), item("b.mkv"))
        assertEquals(files, filterSelected(files, "zzz.mkv"))
        assertEquals(files, filterSelected(files, ""))
    }

    @Test fun readyFilesAreNarrowedToTheTicked() {
        val ready = listOf(item("Show/a.mkv"), item("Show/b.mkv"))
        assertEquals(listOf("Show/b.mkv"), filterSelected(ready, "Show/b.mkv").map { it.filename })
    }

    @Test fun periodBacksOffAfterSixHours() {
        val now = 100L * 3_600_000
        assertEquals(15L, watchPeriodMinutes(now - 6 * 3_600_000, now))
        assertEquals(60L, watchPeriodMinutes(now - 6 * 3_600_000 - 1, now))
    }

    @Test fun notifyOnlyWatchDecision() {
        assertEquals(true, shouldWatchNotifyOnly(setting = true, autoDownload = false, jobRef = "j", ready = false))
        assertEquals(false, shouldWatchNotifyOnly(setting = false, autoDownload = false, jobRef = "j", ready = false))
        assertEquals(false, shouldWatchNotifyOnly(setting = true, autoDownload = true, jobRef = "j", ready = false))
        assertEquals(false, shouldWatchNotifyOnly(setting = true, autoDownload = false, jobRef = null, ready = false))
        assertEquals(false, shouldWatchNotifyOnly(setting = true, autoDownload = false, jobRef = "j", ready = true))
    }

    @Test fun cloudReadyTapGoesToSignedInProvider() {
        assertEquals(tb, cloudReadyTarget("TORBOX", "j", setOf(tb)))
        assertEquals(null, cloudReadyTarget("TORBOX", "j", emptySet()))
        assertEquals(null, cloudReadyTarget("TORBOX", null, setOf(tb)))
        assertEquals(null, cloudReadyTarget("NOPE", "j", setOf(tb)))
        assertEquals(null, cloudReadyTarget(null, "j", setOf(tb)))
    }
}
