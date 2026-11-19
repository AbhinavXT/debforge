package com.abhinavxt.debforge.download.youtube

import com.abhinavxt.debforge.domain.DefaultQuality
import com.abhinavxt.debforge.domain.FormatChoice
import com.abhinavxt.debforge.domain.pickDefaultChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubeEnqueuerTest {
    private val none: List<FormatChoice> = emptyList()

    @Test fun noChoicesGivesNull() {
        for (m in EnqueueMode.values()) assertNull(choiceForMode(none, m, DefaultQuality.BEST))
    }

    @Test fun defaultModeMatchesPickDefaultChoice() {
        assertEquals(pickDefaultChoice(none, DefaultQuality.P720), choiceForMode(none, EnqueueMode.DEFAULT, DefaultQuality.P720))
    }
}
