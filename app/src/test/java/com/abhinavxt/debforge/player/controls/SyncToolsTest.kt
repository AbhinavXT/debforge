package com.abhinavxt.debforge.player.controls

import com.abhinavxt.debforge.player.subtitles.RawCue
import com.abhinavxt.debforge.player.subtitles.SubtitleTiming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncToolsTest {
    private val cues = listOf(
        RawCue(10_000, 12_000, "<i>Far before</i>"),
        RawCue(100_000, 102_000, "Hello {\\an8}there\\Nfriend"),
        RawCue(110_000, 112_000, "Current line"),
        RawCue(118_000, 119_000, "Next line"),
        RawCue(300_000, 301_000, "Far after")
    )

    @Test fun listsLinesWithin20sAndMarksCurrent() {
        val lines = SyncMath.lines(cues, SubtitleTiming.IDENTITY, 0, 111_000)
        assertEquals(listOf("Hello there friend", "Current line", "Next line"), lines.map { it.text })
        assertEquals(listOf(false, true, false), lines.map { it.current })
        assertEquals(1, SyncMath.focusIndex(lines, 111_000))
    }

    @Test fun listUsesShownTimesIncludingNudge() {
        val lines = SyncMath.lines(cues, SubtitleTiming(1.0, 5_000.0), 1_000, 117_000)
        val current = lines.first { it.text == "Current line" }
        assertEquals(116_000L, current.shownStartMs)
        assertEquals(110_000L, current.rawStartMs)
        assertTrue(current.current)
    }

    @Test fun noLinesFocusIsZero() {
        assertEquals(0, SyncMath.focusIndex(emptyList(), 5_000))
    }

    @Test fun tapLineComposesWithCurrentTimingAndNudge() {
        val timing = SubtitleTiming(1.0, 2_000.0)
        val line = SyncMath.lines(cues, timing, 500, 112_000).first { it.text == "Current line" }
        // shown at 112,500; heard at 112,000 less 300 ms reaction: shift 2,000 + 500 + (111,700 - 112,500) = 1,700
        val result = SyncMath.tapLine(timing, 500, line, 112_000)
        assertEquals(1.0, result.a, 0.0)
        assertEquals(1_700.0, result.bMs, 1e-6)
    }

    @Test fun tapLineKeepsRate() {
        val timing = SubtitleTiming(0.99, 0.0)
        val line = SyncLine(100_000, 99_000, 100_000, "x", false)
        val result = SyncMath.tapLine(timing, 0, line, 100_000)
        assertEquals(0.99, result.a, 1e-12)
        assertEquals(700.0, result.bMs, 1e-6)
    }

    @Test fun presetAnchorsAtPositionAndFoldsNudge() {
        val t = SyncMath.preset(SubtitleTiming.IDENTITY, 1_000, 25.0, 23.976, 600_000)
        // The cue shown at the anchor (raw 599,000 plus the 1 s nudge) stays at the anchor.
        assertEquals(600_000L, t.transform(599_000))
        assertEquals(25.0 / 23.976, t.a, 1e-12)
    }

    @Test fun presetLabels() {
        assertEquals("25→23.976", SyncMath.presetLabel(25.0, 23.976))
        assertEquals("24→25", SyncMath.presetLabel(24.0, 25.0))
        assertEquals(6, SubtitleTiming.FPS_PRESETS.size)
    }

    @Test fun twoLinesFit() {
        val first = SyncMath.firstPoint("k", SyncLine(60_000, 60_000, 61_000, "a", false), 62_300)
        assertEquals(62_000L, first.targetMs)
        val second = SyncLine(3_600_000, 3_600_000, 3_601_000, "b", false)
        val (fit, why) = SyncMath.secondPoint(first, second, 3_745_300)
        assertNull(why)
        assertNotNull(fit)
        assertEquals(62_000L, fit!!.transform(60_000))
        assertEquals(3_745_000L, fit.transform(3_600_000))
    }

    @Test fun twoLinesRejectsCloseAndImplausible() {
        val first = TwoLinePoint("k", 60_000, 62_000)
        val close = SyncLine(100_000, 100_000, 101_000, "b", false)
        assertEquals(FitRejection.TOO_CLOSE, SyncMath.secondPoint(first, close, 105_000).second)
        val far = SyncLine(1_000_000, 1_000_000, 1_001_000, "b", false)
        // Heard 1.3 times as far from the first as the file says: the rate would be 1.3.
        val (fit, why) = SyncMath.secondPoint(first, far, 62_000 + 300 + (940_000 * 1.3).toLong())
        assertNull(fit)
        assertEquals(FitRejection.IMPLAUSIBLE, why)
    }

    @Test fun summaryOffsetOnly() {
        val s = SyncMath.summary(SubtitleTiming(1.0, 2_000.0), 300)
        assertEquals(2_300L, s.offsetMs)
        assertNull(s.fpsPair)
        assertNull(s.rate)
        assertTrue(SyncMath.summary(SubtitleTiming.IDENTITY).isIdentity)
    }

    @Test fun summaryNamesPresetRate() {
        val t = SubtitleTiming.fpsPreset(SubtitleTiming.IDENTITY, 25.0, 23.976, 0)
        val s = SyncMath.summary(t)
        assertEquals(25.0 to 23.976, s.fpsPair)
        assertNull(s.rate)
        val odd = SyncMath.summary(SubtitleTiming(1.0123, 0.0))
        assertNull(odd.fpsPair)
        assertNotNull(odd.rate)
    }

    @Test fun correctionText() {
        val unit = { n: String -> "$n s" }
        assertEquals("+2.3 s", correctionText(CorrectionSummary(2_300, null, null), unit))
        assertEquals("+2.3 s · 25→23.976", correctionText(CorrectionSummary(2_300, 25.0 to 23.976, null), unit))
        assertEquals("25→23.976", correctionText(CorrectionSummary(0, 25.0 to 23.976, null), unit))
        assertNull(correctionText(CorrectionSummary(0, null, null), unit))
    }
}
