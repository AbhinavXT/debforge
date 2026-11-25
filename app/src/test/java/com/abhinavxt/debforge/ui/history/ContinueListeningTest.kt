package com.abhinavxt.debforge.ui.history

import com.abhinavxt.debforge.data.history.HistoryEntity
import com.abhinavxt.debforge.data.history.HistoryKind
import com.abhinavxt.debforge.data.history.HistoryOrigin
import org.junit.Assert.assertEquals
import org.junit.Test

class ContinueListeningTest {
    private fun row(
        id: String, pos: Long = 500, dur: Long = 1000, at: Long = 1, kind: String = HistoryKind.AUDIO,
        origin: String = HistoryOrigin.LOCAL
    ) = HistoryEntity(id, kind, origin, null, id, null, null, id, null, null, pos, dur, at)

    private fun ids(rows: List<HistoryEntity>, hidden: Map<String, Long> = emptyMap()) = continueListening(rows, hidden).map { it.itemId }

    @Test fun onlyUnfinishedLocalAudioNewestFirst() {
        val rows = listOf(
            row("old", at = 1), row("new", at = 9), row("done", pos = 1000), row("video", kind = HistoryKind.VIDEO),
            row("yt", origin = HistoryOrigin.YOUTUBE), row("nodur", dur = 0)
        )
        assertEquals(listOf("new", "old"), ids(rows))
    }

    @Test fun hiddenReturnsOnlyAfterANewerPlay() {
        val hidden = decodeHidden(setOf(encodeHidden("a", 5)))
        assertEquals(emptyList<String>(), ids(listOf(row("a", at = 5)), hidden))
        assertEquals(listOf("a"), ids(listOf(row("a", at = 6)), hidden))
    }

    @Test fun cappedAtTheContinueMax() = assertEquals(CONTINUE_MAX, ids((1..30).map { row("i$it", at = it.toLong()) }).size)
}
