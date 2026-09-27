package com.abhinavxt.debforge.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueSummaryTest {

    private fun row(name: String, state: DownloadState, done: Long = 0, total: Long = 100, at: Long = 0) =
        QueueRow(name, state, done, total, at)

    @Test fun empty() {
        val s = QueueSummary.of(emptyList())
        assertEquals(QueueSummary.EMPTY, s)
        assertEquals(false, s.hasActive)
    }

    @Test fun countsAndRunningFileFirst() {
        val s = QueueSummary.of(
            listOf(
                row("a", DownloadState.QUEUED, at = 1),
                row("b", DownloadState.DOWNLOADING, done = 25, at = 5),
                row("c", DownloadState.PAUSED),
                row("d", DownloadState.FAILED),
                row("e", DownloadState.COMPLETED)
            )
        )
        assertEquals(1, s.running); assertEquals(1, s.queued); assertEquals(1, s.paused); assertEquals(1, s.failed)
        assertEquals("b", s.current?.name)
        assertEquals(25, s.currentPercent)
        assertTrue(s.hasActive)
    }

    @Test fun oldestQueuedIsNext() {
        val s = QueueSummary.of(listOf(row("new", DownloadState.QUEUED, at = 9), row("old", DownloadState.QUEUED, at = 2)))
        assertEquals("old", s.current?.name)
    }

    @Test fun pausedShownWhenNothingElse() {
        val s = QueueSummary.of(listOf(row("p", DownloadState.PAUSED, done = 50)))
        assertEquals("p", s.current?.name)
        assertEquals(false, s.hasActive)
    }

    @Test fun unknownSizeHasNoPercent() {
        val s = QueueSummary.of(listOf(row("x", DownloadState.DOWNLOADING, done = 10, total = 0)))
        assertNull(s.currentPercent)
    }

    @Test fun percentIsClamped() {
        val s = QueueSummary.of(listOf(row("x", DownloadState.DOWNLOADING, done = 150, total = 100)))
        assertEquals(100, s.currentPercent)
    }
}
