package com.abhinavxt.debforge.player

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioResumeStartTest {
    private val minute = 60_000L

    @Test fun thePlayersRememberedSpotWins() = assertEquals(40 * minute, audioResumeStart(40 * minute, 10 * minute, 60 * minute))

    @Test fun shortTracksFallBackToTheHistoryPosition() = assertEquals(2 * minute, audioResumeStart(0, 2 * minute, 4 * minute))

    @Test fun theVeryStartOrEndStartsOver() {
        assertEquals(0L, audioResumeStart(0, 10_000, 4 * minute))
        assertEquals(0L, audioResumeStart(0, 4 * minute - 10_000, 4 * minute))
    }
}
