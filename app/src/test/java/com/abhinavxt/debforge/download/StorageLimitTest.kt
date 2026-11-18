package com.abhinavxt.debforge.download

import com.abhinavxt.debforge.data.local.DownloadEntity
import com.abhinavxt.debforge.domain.DownloadState
import com.abhinavxt.debforge.domain.ProviderId
import com.abhinavxt.debforge.download.DownloadScheduler.Companion.shouldRestartOnStorageUnblock
import com.abhinavxt.debforge.download.conditions.storageBlocks
import com.abhinavxt.debforge.download.conditions.storageLimitBytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageLimitTest {
    private fun file(size: Long, done: Long = 0L) = DownloadEntity(
        id = "a", provider = ProviderId.REAL_DEBRID, filename = "f", sourceRef = "s", downloadUrl = "u",
        host = "h", filesize = size, chunksAllowed = 1, state = DownloadState.QUEUED, bytesDownloaded = done
    )

    private val cap = 10L

    @Test fun offNeverBlocks() {
        assertFalse(storageBlocks(0, 1_000, file(1_000)))
        assertFalse(storageBlocks(-1, 1_000, file(1_000)))
    }

    @Test fun nothingQueuedNeverBlocks() {
        assertFalse(storageBlocks(cap, 100, null))
    }

    @Test fun underTheLimitStarts() {
        assertFalse(storageBlocks(cap, 4, file(5)))
    }

    @Test fun exactlyAtTheLimitStarts() {
        assertFalse(storageBlocks(cap, 5, file(5)))
    }

    @Test fun overTheLimitWaits() {
        assertTrue(storageBlocks(cap, 6, file(5)))
    }

    @Test fun startedFileIsExempt() {
        assertFalse(storageBlocks(cap, 50, file(5, done = 2)))
    }

    @Test fun unknownSizeBlocksOnlyWhenAlreadyFull() {
        assertFalse(storageBlocks(cap, 9, file(0)))
        assertFalse(storageBlocks(cap, 9, file(-1)))
        assertTrue(storageBlocks(cap, 10, file(0)))
        assertTrue(storageBlocks(cap, 11, file(-1)))
    }

    @Test fun gigabytesToBytes() {
        assertEquals(0L, storageLimitBytes(0))
        assertEquals(1_000_000_000L, storageLimitBytes(1))
        assertEquals(500L * 1_000_000_000L, storageLimitBytes(500))
    }

    @Test fun restartsOnlyWhenTheLimitClears() {
        assertTrue(shouldRestartOnStorageUnblock(true, false))
        assertFalse(shouldRestartOnStorageUnblock(false, false))
        assertFalse(shouldRestartOnStorageUnblock(false, true))
        assertFalse(shouldRestartOnStorageUnblock(true, true))
    }
}
