package com.abhinavxt.debforge.player.subtitles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimingKeyOfTrackIdTest {
    @Test fun plainKeyIsKept() = assertEquals("local:a.srt|v", timingKeyOfTrackId("local:a.srt|v"))

    @Test fun mergedPrefixIsStripped() {
        assertEquals("local:Movies/a.en.srt|v", timingKeyOfTrackId("1:local:Movies/a.en.srt|v"))
        assertEquals("os:123", timingKeyOfTrackId("0:os:123"))
    }

    @Test fun nestedPrefixesAreStripped() = assertEquals("os:9", timingKeyOfTrackId("2:1:os:9"))

    @Test fun embeddedIdsGiveNull() {
        assertNull(timingKeyOfTrackId("1:en"))
        assertNull(timingKeyOfTrackId("en"))
        assertNull(timingKeyOfTrackId("1:"))
        assertNull(timingKeyOfTrackId(""))
        assertNull(timingKeyOfTrackId(null))
    }

    @Test fun nonNumericPrefixIsNotStripped() = assertNull(timingKeyOfTrackId("x:local:a"))
}
