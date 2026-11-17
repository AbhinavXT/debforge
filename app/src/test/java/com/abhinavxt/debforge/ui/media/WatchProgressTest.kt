package com.abhinavxt.debforge.ui.media

import com.abhinavxt.debforge.data.playback.PlaybackEntity
import com.abhinavxt.debforge.domain.ProviderId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchProgressTest {
    private fun saved(pos: Long, dur: Long, finished: Boolean = false) = PlaybackEntity(
        itemId = "a", provider = ProviderId.entries.first(), sourceRef = "r", filename = "f.mkv",
        title = "t", showKey = null, positionMs = pos, durationMs = dur, finished = finished, updatedAt = 0L
    )

    @Test fun noRowIsUnplayed() = assertEquals(WatchProgress.Unplayed, watchProgress(null))

    @Test fun midwayIsInProgress() {
        val r = watchProgress(saved(600_000, 1_200_000))
        assertTrue(r is WatchProgress.InProgress)
        assertEquals(0.5f, (r as WatchProgress.InProgress).fraction, 0.001f)
        assertEquals(50, r.percent)
    }

    @Test fun finishedFlagWins() = assertEquals(WatchProgress.Finished, watchProgress(saved(100_000, 1_200_000, finished = true)))

    @Test fun endCreditsAreFinished() = assertEquals(WatchProgress.Finished, watchProgress(saved(1_150_000, 1_200_000)))

    @Test fun aPeekShowsNothing() = assertEquals(WatchProgress.Finished, watchProgress(saved(10_000, 1_200_000)))

    @Test fun unknownLengthShowsNothing() = assertEquals(WatchProgress.Finished, watchProgress(saved(100_000, 0)))

    @Test fun percentStaysWithin1To99() {
        assertEquals(3, WatchProgress.InProgress(0.031f).percent)
        assertEquals(1, WatchProgress.InProgress(0.001f).percent)
        assertEquals(99, WatchProgress.InProgress(0.999f).percent)
    }

    @Test fun negativePositionShowsNothing() = assertEquals(WatchProgress.Finished, watchProgress(saved(-5_000, 1_200_000)))

    @Test fun positionBeyondDurationIsFinished() = assertEquals(WatchProgress.Finished, watchProgress(saved(2_000_000, 1_200_000)))

    @Test fun nothingShownBeforeLoad() {
        assertEquals(WatchProgress.Finished, tileProgress(false, true, null))
        assertEquals(WatchProgress.Finished, tileProgress(false, true, saved(600_000, 1_200_000)))
    }

    @Test fun unplayedOnlyWithInternalPlayer() {
        assertEquals(WatchProgress.Unplayed, tileProgress(true, true, null))
        assertEquals(WatchProgress.Finished, tileProgress(true, false, null))
    }

    @Test fun barStaysWithExternalPlayer() {
        assertTrue(tileProgress(true, false, saved(600_000, 1_200_000)) is WatchProgress.InProgress)
    }
}
