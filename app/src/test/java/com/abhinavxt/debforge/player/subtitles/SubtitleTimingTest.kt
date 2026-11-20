package com.abhinavxt.debforge.player.subtitles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleTimingTest {
    @Test fun transformAndInverse() {
        val t = SubtitleTiming(1.04, -500.0)
        assertEquals(9_900L, t.transform(10_000))
        assertEquals(10_000L, t.inverse().transform(9_900))
        assertTrue(SubtitleTiming.approximatelyEquals(SubtitleTiming.IDENTITY, t.compose(t.inverse())))
    }

    @Test fun composeAppliesFirstThenSecond() {
        val x = SubtitleTiming(1.0, 1000.0)
        val y = SubtitleTiming(2.0, 0.0)
        assertEquals(y.transform(x.transform(500)), x.compose(y).transform(500))
    }

    @Test fun twoPointFit() {
        val t = SubtitleTiming.fitTwoPoints(60_000, 62_000, 3_600_000, 3_750_000)!!
        assertEquals(62_000L, t.transform(60_000))
        assertEquals(3_750_000L, t.transform(3_600_000))
    }

    @Test fun twoPointFitRejects() {
        assertNull(SubtitleTiming.fitTwoPoints(1000, 1000, 1000, 2000))
        assertNull(SubtitleTiming.fitTwoPoints(0, 0, 1000, 5000)) // a = 5
        assertNull(SubtitleTiming.fitTwoPoints(0, 0, 100_000, 50_000)) // a = 0.5
        assertNotNull(SubtitleTiming.fitTwoPoints(0, 0, 100_000, 104_271))
    }

    @Test fun tappedLine() {
        // line heard at 10.0 s, cue shown at 8.0 s: move 1.7 s later (300 ms reaction)
        assertEquals(1_700L, SubtitleTiming.offsetFromTappedLine(8_000, 10_000))
        assertEquals(1_500L, SubtitleTiming.offsetFromTappedLine(8_000, 10_000, 500))
    }

    @Test fun fpsPresetKeepsAnchor() {
        val t = SubtitleTiming.fpsPreset(SubtitleTiming.IDENTITY, 25.0, 23.976, 600_000)
        assertEquals(600_000L, t.transform(600_000))
        assertTrue(t.a > 1.04 && t.a < 1.044)
        // far later cues move later by the rate
        assertTrue(t.transform(3_600_000) > 3_600_000)
        assertEquals(6, SubtitleTiming.FPS_PRESETS.size)
        // reverse preset undoes it
        val back = SubtitleTiming.fpsPreset(t, 23.976, 25.0, 600_000)
        assertTrue(SubtitleTiming.approximatelyEquals(SubtitleTiming.IDENTITY, back, 1e-6))
    }

    @Test fun shiftComposes() {
        assertEquals(1_300L, SubtitleTiming().shiftedBy(300).transform(1_000))
    }

    @Test fun keys() {
        assertEquals("os:42", timingKeyFor("v1", "file:///x/a.srt", null, null, 42))
        assertEquals("REAL_DEBRID:abc", timingKeyFor("v1", "https://x/y", "REAL_DEBRID", "abc"))
        assertEquals("local:Movie.en.srt|v1", timingKeyFor("v1", "file:///data/Movie.en.srt", null, null))
        assertEquals("local:a.srt|", timingKeyFor(null, "/sdcard/a.srt", null, null))
        assertNull(timingKeyFor("v1", "https://x/y", null, null))
    }

    @Test fun storeEncodingAndLru() {
        var m = LinkedHashMap<String, SubtitleTiming>()
        for (i in 0 until 5) m = SubtitleTimingStore.withEntry(m, "k$i|x y\t:", SubtitleTiming(1.01, i * 10.0), max = 3)
        assertEquals(listOf("k2|x y\t:", "k3|x y\t:", "k4|x y\t:"), m.keys.toList())
        m = SubtitleTimingStore.withEntry(m, "k2|x y\t:", SubtitleTiming(1.02, 1.0), max = 3)
        assertEquals("k2|x y\t:", m.keys.last())
        val round = SubtitleTimingStore.decode(SubtitleTimingStore.encode(m))
        assertEquals(m, round)
        assertEquals(m.keys.toList(), round.keys.toList())
        assertTrue(SubtitleTimingStore.withEntry(m, "k2|x y\t:", SubtitleTiming.IDENTITY, max = 3).size == 2)
    }
}
