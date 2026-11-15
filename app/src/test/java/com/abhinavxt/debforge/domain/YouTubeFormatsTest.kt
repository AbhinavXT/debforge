package com.abhinavxt.debforge.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeFormatsTest {
    private fun v(itag: Int, h: Int, codec: String, c: Container, fps: Int = 30, br: Long = h * 1000L, len: Long = -1) =
        YouTubeStream(itag, "u$itag", StreamKind.VIDEO, c, codec, h, fps, br, len)

    private fun a(itag: Int, codec: String, c: Container, br: Long, len: Long = -1) =
        YouTubeStream(itag, "u$itag", StreamKind.AUDIO, c, codec, bitrateBps = br, contentLength = len)

    private val streams = listOf(
        v(299, 1080, "avc1.64002a", Container.MP4, 60), v(137, 1080, "avc1.640028", Container.MP4),
        v(298, 720, "avc1.4d401f", Container.MP4, 60), v(135, 480, "avc1.4d401e", Container.MP4),
        v(303, 1080, "vp9", Container.WEBM), v(308, 1440, "vp9", Container.WEBM, 60), v(315, 2160, "vp9", Container.WEBM, 60),
        v(399, 1080, "av01.0.08M.08", Container.MP4), v(400, 1440, "av01.0.12M.08", Container.MP4),
        v(401, 2160, "av01.0.12M.08", Container.MP4), v(402, 4320, "av01.0.12M.08", Container.MP4),
        a(139, "mp4a.40.5", Container.M4A, 48_000), a(140, "mp4a.40.2", Container.M4A, 128_000),
        a(249, "opus", Container.WEBM, 50_000), a(250, "opus", Container.WEBM, 70_000), a(251, "opus", Container.WEBM, 130_000),
    )

    @Test fun sdk30HasNoAv1() {
        val c = YouTubeFormats.choices(streams, 30)
        val video = c.filter { it.videoItag != null }
        assertEquals(listOf(2160, 1440, 1080, 720, 480), video.map { it.height })
        assertEquals(listOf(315, 308, 299, 298, 135), video.map { it.videoItag })
        assertEquals(listOf("webm", "webm", "mp4", "mp4", "mp4"), video.map { it.extension })
        assertTrue(c.none { it.videoItag in listOf(399, 400, 401, 402) })
    }

    @Test fun sdk34UnchangedWhereOtherCodecsExist_av1OnlyAlone() {
        val c = YouTubeFormats.choices(streams, 34).filter { it.videoItag != null }
        assertEquals(listOf(4320, 2160, 1440, 1080, 720, 480), c.map { it.height })
        assertEquals(listOf(402, 315, 308, 299, 298, 135), c.map { it.videoItag })
    }

    @Test fun audioPairingAndAudioOnly() {
        val c = YouTubeFormats.choices(streams, 30)
        assertEquals(140, c.first { it.videoItag == 299 }.audioItag)
        assertEquals(251, c.first { it.videoItag == 308 }.audioItag)
        val audio = c.filter { it.videoItag == null }
        assertEquals(listOf("m4a" to 140, "weba" to 251), audio.map { it.extension to it.audioItag })
    }

    @Test fun defaultIs1080Mp4() {
        val d = YouTubeFormats.choices(streams, 30).single { it.isDefault }
        assertEquals(299, d.videoItag)
        assertEquals("mp4", d.extension)
    }

    @Test fun defaultFallsBackToBestAvailable() {
        val only = listOf(v(315, 2160, "vp9", Container.WEBM), a(251, "opus", Container.WEBM, 130_000))
        assertEquals(315, YouTubeFormats.choices(only, 30).single { it.isDefault }.videoItag)
    }

    @Test fun sizeFromContentLengthElseEstimate() {
        val known = YouTubeFormats.choices(
            listOf(v(299, 1080, "avc1", Container.MP4, len = 1000), a(140, "mp4a", Container.M4A, 128_000, len = 200)), 30,
        ).first()
        assertEquals(1200, known.estimatedBytes); assertFalse(known.isEstimate)
        val est = YouTubeFormats.choices(
            listOf(v(299, 1080, "avc1", Container.MP4, br = 8_000_000), a(140, "mp4a", Container.M4A, 128_000)), 30, durationSec = 100,
        ).first()
        assertEquals(100_000_000L + 1_600_000L, est.estimatedBytes); assertTrue(est.isEstimate)
    }

    @Test fun noAudioNoVideoChoices() {
        assertTrue(YouTubeFormats.choices(streams.filter { it.kind == StreamKind.VIDEO }, 30).isEmpty())
        assertNull(YouTubeFormats.choices(emptyList(), 30).firstOrNull())
    }
}
