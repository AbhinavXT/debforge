package com.abhinavxt.debforge.player.subtitles

import android.text.Spanned
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.SubtitleParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RetimingParserFactoryTest {
    private val srt = """
        1
        00:00:10,000 --> 00:00:12,000
        Hello

        2
        00:01:00,000 --> 00:01:03,000
        World
    """.trimIndent().toByteArray()

    private fun parse(factory: RetimingParserFactory, format: Format, data: ByteArray): List<CuesWithTiming> {
        val out = ArrayList<CuesWithTiming>()
        factory.create(format).parse(data, SubtitleParser.OutputOptions.allCues()) { out.add(it) }
        return out.sortedBy { it.startTimeUs }
    }

    private fun srtFormat(id: String?) = Format.Builder().setId(id).setSampleMimeType(MimeTypes.APPLICATION_SUBRIP).build()

    @Test fun shiftsAndStretchesCues() {
        val book = CueBook()
        val f = RetimingParserFactory({ if (it == "os:1") SubtitleTiming(1.1, 500.0) else null }, book)
        val cues = parse(f, srtFormat("os:1"), srt)
        assertEquals(2, cues.size)
        assertEquals(11_500_000L, cues[0].startTimeUs) // 1.1*10 s + 0.5 s
        assertEquals(2_200_000L, cues[0].durationUs)
        assertEquals(66_500_000L, cues[1].startTimeUs)
        assertEquals(3_300_000L, cues[1].durationUs)
        // raw cues are recorded unchanged
        assertEquals(listOf(10_000L, 60_000L), book.cues("os:1").map { it.startMs })
        assertEquals("Hello", book.cues("os:1")[0].text)
    }

    @Test fun identityAndForeignIdsUntouched() {
        val f = RetimingParserFactory({ SubtitleTiming(2.0, 0.0) }, CueBook())
        assertEquals(10_000_000L, parse(f, srtFormat("3"), srt)[0].startTimeUs) // embedded-style id, no ':'
        assertEquals(10_000_000L, parse(f, srtFormat(null), srt)[0].startTimeUs)
        val none = RetimingParserFactory({ null }, CueBook())
        assertEquals(10_000_000L, parse(none, srtFormat("os:9"), srt)[0].startTimeUs)
    }

    @Test fun cuesMovedBeforeZeroAreClampedOrDropped() {
        val f = RetimingParserFactory({ SubtitleTiming(1.0, -11_000.0) }, null)
        val cues = parse(f, srtFormat("os:2"), srt)
        assertEquals(2, cues.size)
        assertEquals(0L, cues[0].startTimeUs)
        assertEquals(1_000_000L, cues[0].durationUs)
        val f2 = RetimingParserFactory({ SubtitleTiming(1.0, -13_000.0) }, null)
        assertEquals(1, parse(f2, srtFormat("os:2"), srt).size)
    }

    @Test fun assStylingSurvives() {
        val ass = """
            [Script Info]
            ScriptType: v4.00+

            [V4+ Styles]
            Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
            Style: Default,Arial,20,&H00FFFFFF,&H000000FF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,2,2,2,10,10,10,1

            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:10.00,0:00:12.00,Default,,0,0,0,,{\i1}Slanted{\i0} text
        """.trimIndent().toByteArray()
        val format = Format.Builder().setId("os:3").setSampleMimeType(MimeTypes.TEXT_SSA)
            .setInitializationData(listOf(
                ("Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text").toByteArray(),
                ByteArray(0)
            )).build()
        val plain = RetimingParserFactory({ null }, null)
        val moved = RetimingParserFactory({ SubtitleTiming(1.0, 2_000.0) }, null)
        val a = parse(plain, format, ass)
        val b = parse(moved, format, ass)
        assertEquals(a.size, b.size)
        assertEquals(1, a.size)
        run {
            assertEquals(a[0].startTimeUs + 2_000_000L, b[0].startTimeUs)
            val ta = a[0].cues[0].text as CharSequence
            val tb = b[0].cues[0].text as CharSequence
            assertEquals(ta.toString(), tb.toString())
            if (ta is Spanned) {
                assertEquals(ta.getSpans(0, ta.length, Any::class.java).size, (tb as Spanned).getSpans(0, tb.length, Any::class.java).size)
            }
            assertTrue(tb.toString().contains("Slanted"))
        }
    }
}
