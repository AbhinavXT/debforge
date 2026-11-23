package com.abhinavxt.debforge.ui.history

import com.abhinavxt.debforge.data.history.HistoryEntity
import com.abhinavxt.debforge.data.history.HistoryKind
import com.abhinavxt.debforge.data.history.HistoryOrigin
import org.junit.Assert.assertEquals
import org.junit.Test

class ContinueWatchingTest {
    private fun row(
        id: String, pos: Long = 500, dur: Long = 1000, at: Long = 1, kind: String = HistoryKind.VIDEO,
        show: String? = null, origin: String = HistoryOrigin.CLOUD
    ) = HistoryEntity(id, kind, origin, "TORBOX", id, null, show, id, null, null, pos, dur, at)

    private fun ids(rows: List<HistoryEntity>, hidden: Map<String, Long> = emptyMap()) = continueWatching(rows, hidden).map { it.itemId }

    @Test fun thresholdsAreInclusiveAtFiveAndNinetyFivePercent() {
        assertEquals(listOf("a", "b"), ids(listOf(row("a", 50, at = 2), row("b", 950, at = 1), row("lo", 49), row("hi", 951))))
    }

    @Test fun unknownDurationAndFinishedAreSkipped() {
        assertEquals(emptyList<String>(), ids(listOf(row("a", 500, 0), row("b", 1000, 1000))))
    }

    @Test fun onlyCloudOrLocalVideo() {
        assertEquals(listOf("v"), ids(listOf(row("v"), row("a", kind = HistoryKind.AUDIO), row("y", origin = HistoryOrigin.YOUTUBE))))
    }

    @Test fun newestFirstOneEpisodePerShowAndCapped() {
        val rows = listOf(row("e1", at = 1, show = "s"), row("e2", at = 5, show = "s"), row("m", at = 3))
        assertEquals(listOf("e2", "m"), ids(rows))
        assertEquals(CONTINUE_MAX, ids((1..30).map { row("i$it", at = it.toLong()) }).size)
    }

    @Test fun hiddenReturnsOnlyAfterANewerPlay() {
        val hidden = decodeHidden(setOf(encodeHidden("a", 5)))
        assertEquals(emptyList<String>(), ids(listOf(row("a", at = 5)), hidden))
        assertEquals(listOf("a"), ids(listOf(row("a", at = 6)), hidden))
    }

    @Test fun decodeKeepsNewestAndIgnoresJunk() {
        assertEquals(mapOf("a|b" to 9L), decodeHidden(setOf("3|a|b", "9|a|b", "junk")))
    }
}
