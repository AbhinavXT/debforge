package com.abhinavxt.debforge.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class DefaultQualityTest {
    private fun fc(h: Int, default: Boolean = false, audio: Boolean = false) = FormatChoice(
        if (audio) null else h, 1, "mp4", 0, false, if (audio) 0 else h, 30, Container.MP4, 0, default,
    )

    private val list = listOf(fc(2160), fc(1080, default = true), fc(720), fc(480), fc(0, audio = true), fc(0, default = false, audio = true))
    private fun h(q: DefaultQuality, l: List<FormatChoice> = list) = pickDefaultChoice(l, q)?.height

    @Test fun exactHeight() = assertEquals(720, h(DefaultQuality.P720))
    @Test fun absentHeightFallsToLower() = assertEquals(480, h(DefaultQuality.P480, list.filter { it.height != 720 && it.height != 0 }.let { it }))
    @Test fun missingHeightFallsToNextLower() = assertEquals(720, h(DefaultQuality.P1080, list.filter { it.height != 1080 }))
    @Test fun nothingAtOrBelowFallsToLowestVideo() = assertEquals(720, h(DefaultQuality.P480, listOf(fc(2160), fc(720), fc(0, audio = true))))
    @Test fun best() = assertEquals(2160, h(DefaultQuality.BEST))
    @Test fun audioOnly() = assertEquals(null, pickDefaultChoice(list, DefaultQuality.AUDIO)?.videoItag)
    @Test fun askKeepsDefault() = assertEquals(1080, h(DefaultQuality.ASK))
    @Test fun audioOnlyListHeightSetting() = assertEquals(0, h(DefaultQuality.P720, listOf(fc(0, true, true), fc(0, audio = true))))
}
