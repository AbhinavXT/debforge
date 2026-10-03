package com.abhinavxt.debforge.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackStatsTest {

    @Test fun fullSnapshot() {
        val text = PlaybackStats.format(
            PlaybackStats.Snapshot(
                video = PlaybackStats.Video("video/hevc", "hvc1.2.4.L153", 3840, 2160, 23.976f, 12_300_000, "HDR10"),
                videoDecoder = "c2.qti.hevc.decoder",
                audio = PlaybackStats.Audio("audio/eac3", 6, 48_000, 640_000, "eng"),
                audioDecoder = "ffmpegLib-eac3",
                bufferAheadMs = 42_100,
                networkBps = 85_000_000,
                droppedFrames = 3
            )
        )
        assertEquals(
            listOf(
                "Video   HEVC · 3840×2160 · 23.976 fps · HDR10 · 12.3 Mbps",
                "        c2.qti.hevc.decoder (hardware)",
                "Audio   E-AC3 · 5.1 · 48 kHz · 640 kbps · eng",
                "        ffmpegLib-eac3 (software)",
                "Buffer  42.1 s ahead · network 85.0 Mbps",
                "Dropped 3 frames"
            ).joinToString("\n"),
            text
        )
    }

    @Test fun unknownsAreLeftOut() {
        val text = PlaybackStats.format(
            PlaybackStats.Snapshot(
                video = PlaybackStats.Video("video/avc", null, 1920, 1080, 24f, -1, null),
                videoDecoder = null, audio = null, audioDecoder = null,
                bufferAheadMs = 0, networkBps = 0, droppedFrames = 0
            )
        )
        assertEquals("Video   H.264 · 1920×1080 · 24 fps\nBuffer  0.0 s ahead\nDropped 0 frames", text)
    }

    @Test fun syncShownOnlyWhenSet() {
        val base = PlaybackStats.Snapshot(null, null, null, null, 0, 0, 0)
        assertFalse(PlaybackStats.format(base).contains("Sync"))
        assertTrue(PlaybackStats.format(base.copy(subtitleDelayMs = 300)).contains("Sync    subtitles +0.3 s · audio +0.0 s"))
    }

    @Test fun softwareDecoders() {
        assertTrue(PlaybackStats.isSoftware("c2.android.hevc.decoder"))
        assertTrue(PlaybackStats.isSoftware("OMX.google.h264.decoder"))
        assertTrue(PlaybackStats.isSoftware("ffmpegLib-dts"))
        assertFalse(PlaybackStats.isSoftware("c2.exynos.hevc.decoder"))
        assertFalse(PlaybackStats.isSoftware("OMX.qcom.video.decoder.avc"))
    }
}
