package com.abhinavxt.debforge.player.youtube

import com.abhinavxt.debforge.domain.Container
import com.abhinavxt.debforge.domain.DefaultQuality
import com.abhinavxt.debforge.data.provider.StreamQuality
import com.abhinavxt.debforge.domain.FormatChoice
import com.abhinavxt.debforge.domain.YouTubeLinks
import com.abhinavxt.debforge.domain.pickDefaultChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubeStreamingTest {
    private val unset = -1L // C.LENGTH_UNSET

    @Test fun windowIsOneChunkWhenLengthUnknown() =
        assertEquals(RangeWindow(100, 1100), YouTubeStreaming.rangeWindow(100, unset, 1000))

    @Test fun windowIsShortenedToWhatIsWanted() =
        assertEquals(RangeWindow(0, 300), YouTubeStreaming.rangeWindow(0, 300, 1000))

    @Test fun windowIsCappedAtChunkWhenMoreIsWanted() =
        assertEquals(RangeWindow(5000, 6000), YouTubeStreaming.rangeWindow(5000, 10_000, 1000))

    @Test fun windowOfNothingIsEmpty() =
        assertEquals(0L, YouTubeStreaming.rangeWindow(40, 0, 1000).length)

    @Test fun positionKeys() {
        assertEquals("ytstream:abc", YouTubeStreaming.streamPositionKey("abc", false))
        assertEquals("ytaudio:abc", YouTubeStreaming.streamPositionKey("abc", true))
    }

    private fun c(v: Int?, a: Int, h: Int, ext: String = "mp4", default: Boolean = false) =
        FormatChoice(v, a, ext, 0, false, h, 30, Container.MP4, 128_000, default)

    private val choices = listOf(
        c(299, 140, 2160), c(137, 140, 1080, default = true), c(135, 140, 480),
        c(null, 140, 0, "m4a", default = true), c(null, 251, 0, "weba"),
    )

    @Test fun qualityOptionsListVideoChoicesOnlyAndMarkCurrent() {
        val ref = YouTubeLinks.Ref("x", 137, 140)
        val o = YouTubeStreaming.qualityOptions(ref, choices)
        assertEquals(listOf(2160, 1080, 480), o.map { it.choice!!.height })
        assertEquals(listOf(false, true, false), o.map { it.current })
    }

    private val original = StreamQuality(StreamQuality.ORIGINAL, "Original", null)
    private val q720 = StreamQuality("720", "720p", 720)
    private val q480 = StreamQuality("480", "480p", 480)

    @Test fun streamOptionsPutOriginalFirstAndCurrentByDefault() {
        val o = YouTubeStreaming.streamOptions(listOf(q720, original, q480), null)
        assertEquals(listOf(original, q720, q480), o.map { it.stream })
        assertEquals(listOf(true, false, false), o.map { it.current })
    }

    @Test fun streamOptionsMarkTheChosenQualityCurrent() {
        val o = YouTubeStreaming.streamOptions(listOf(original, q720, q480), q480)
        assertEquals(listOf(false, false, true), o.map { it.current })
        assertEquals(q720, o[1].stream) // selecting a row hands back its own StreamQuality
        assertEquals(null, o[1].choice)
    }

    @Test fun noStreamQualitiesNoRows() = assertEquals(emptyList<QualityOption>(), YouTubeStreaming.streamOptions(emptyList(), null))

    @Test fun sameChoiceNeedsBothItags() {
        assertEquals(true, YouTubeStreaming.sameChoice(YouTubeLinks.Ref("x", 137, 140), choices[1]))
        assertEquals(false, YouTubeStreaming.sameChoice(YouTubeLinks.Ref("x", 137, 251), choices[1]))
    }

    @Test fun reselectKeepsSameItags() =
        assertEquals(137, YouTubeStreaming.reselect(YouTubeLinks.Ref("x", 137, 140), 1080, choices)?.videoItag)

    @Test fun reselectFallsBackToTallestAtOrBelowOldHeight() =
        assertEquals(135, YouTubeStreaming.reselect(YouTubeLinks.Ref("x", 22, 140), 720, choices)?.videoItag)

    @Test fun reselectNeverGoesAboveOldHeight() =
        assertNull(YouTubeStreaming.reselect(YouTubeLinks.Ref("x", 22, 140), 360, choices))

    @Test fun reselectAudioKeepsItagThenDefault() {
        assertEquals(251, YouTubeStreaming.reselect(YouTubeLinks.Ref("x", null, 251), 0, choices)?.audioItag)
        assertEquals(140, YouTubeStreaming.reselect(YouTubeLinks.Ref("x", null, 999), 0, choices)?.audioItag)
    }

    @Test fun reselectAudioNeverPicksVideo() =
        assertNull(YouTubeStreaming.reselect(YouTubeLinks.Ref("x", null, 140), 0, choices.filter { it.videoItag != null }))

    @Test fun quickPlayAsksWhenSettingIsAsk() =
        assertNull(YouTubeStreaming.quickPlayChoice(DefaultQuality.ASK, choices))

    @Test fun quickPlayMatchesPreviewPick() {
        for (q in DefaultQuality.values().filter { it != DefaultQuality.ASK })
            assertEquals(pickDefaultChoice(choices, q), YouTubeStreaming.quickPlayChoice(q, choices))
        assertEquals(1080, YouTubeStreaming.quickPlayChoice(DefaultQuality.P1080, choices)?.height)
        assertEquals(null, YouTubeStreaming.quickPlayChoice(DefaultQuality.AUDIO, choices)?.videoItag)
    }

    @Test fun mp4FallbackPicksTallestMp4BelowAndNotTheFailedOne() {
        val mixed = listOf(
            FormatChoice(248, 251, "webm", 0, false, 1080, 30, Container.WEBM, 0),
            c(137, 140, 1080), c(135, 140, 480), c(299, 140, 2160),
        )
        assertEquals(135, YouTubeStreaming.mp4Fallback(YouTubeLinks.Ref("x", 137, 140), 1080, mixed)?.videoItag)
        assertEquals(137, YouTubeStreaming.mp4Fallback(YouTubeLinks.Ref("x", 248, 251), 1080, mixed)?.videoItag)
    }

    @Test fun mp4FallbackIsNullWhenNothingFits() =
        assertNull(YouTubeStreaming.mp4Fallback(YouTubeLinks.Ref("x", 135, 140), 480, choices))
}
