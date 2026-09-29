package com.abhinavxt.debforge.player.tracks

import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleStyleTest {

    @Test fun roundTrip() {
        val s = SubtitleStyle(SubtitleStyle.Size.XL, SubtitleStyle.Look.YELLOW, SubtitleStyle.Position.HIGH, fileStyling = false)
        assertEquals(s, SubtitleStyle.decode(s.encode()))
        assertEquals(SubtitleStyle(), SubtitleStyle.decode(SubtitleStyle().encode()))
    }

    @Test fun badInputFallsBackPartByPart() {
        assertEquals(SubtitleStyle(), SubtitleStyle.decode(null))
        assertEquals(SubtitleStyle(), SubtitleStyle.decode("garbage"))
        assertEquals(SubtitleStyle(size = SubtitleStyle.Size.L), SubtitleStyle.decode("L|NOPE"))
    }
}
