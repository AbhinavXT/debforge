package com.abhinavxt.debforge.ui.media

import com.abhinavxt.debforge.data.playback.PlaybackEntity
import com.abhinavxt.debforge.domain.ProviderId
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayAsAudioStartTest {
    private fun saved(pos: Long, finished: Boolean = false) = PlaybackEntity(
        itemId = "a", provider = ProviderId.entries.first(), sourceRef = "r", filename = "f.mkv",
        title = "t", showKey = null, positionMs = pos, durationMs = 1_200_000, finished = finished, updatedAt = 0L
    )

    @Test fun noSavedPositionStartsAtZero() = assertEquals(0L, playAsAudioStartMs(null))
    @Test fun savedPositionIsUsed() = assertEquals(600_000L, playAsAudioStartMs(saved(600_000)))
    @Test fun finishedStartsAtZero() = assertEquals(0L, playAsAudioStartMs(saved(1_190_000, finished = true)))
}
