package com.abhinavxt.debforge.download

import com.abhinavxt.debforge.domain.DownloadState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadProgressTrackerTest {
    @Test
    fun finishingFlagIsCarriedUntilNextUpdateAndCleared() {
        val t = DownloadProgressTracker()
        t.update("a", 10, 10, DownloadState.DOWNLOADING)
        assertFalse(t.progress.value.getValue("a").finishing)
        t.update("a", 10, 10, DownloadState.DOWNLOADING, finishing = true)
        assertTrue(t.progress.value.getValue("a").finishing)
        t.clear("a")
        assertTrue(t.progress.value.isEmpty())
    }
}
