package com.abhinavxt.debforge.player

import java.util.Locale

/**
 * "Stats for nerds": what's playing and how well, as a few lines of text.
 * Plain values in, text out, so it's unit-tested (PlaybackStatsTest)
 * without a player.
 */
object PlaybackStats {

    data class Video(
        val mime: String?,
        val codecs: String?,
        val width: Int,
        val height: Int,
        val fps: Float,
        val bitrate: Int,
        /** "HDR10", "HLG", "Dolby Vision" or null for SDR. */
        val hdr: String?
    )

    data class Audio(
        val mime: String?,
        val channels: Int,
        val sampleRate: Int,
        val bitrate: Int,
        val language: String?
    )

    data class Snapshot(
        val video: Video?,
        val videoDecoder: String?,
        val audio: Audio?,
        val audioDecoder: String?,
        val bufferAheadMs: Long,
        /** Download speed estimate, bits per second; ≤ 0 = unknown. */
        val networkBps: Long,
        val droppedFrames: Int,
        val subtitleDelayMs: Long = 0,
        val audioDelayMs: Long = 0
    )

    /** Android's own software codecs and the FFmpeg extension run on the CPU. */
    fun isSoftware(decoder: String): Boolean {
        val d = decoder.lowercase(Locale.ROOT)
        return d.startsWith("omx.google.") || d.startsWith("c2.android.") || d.contains("ffmpeg") ||
            d.contains(".sw.") || d.endsWith(".sw")
    }

    fun format(s: Snapshot): String = buildList {
        s.video?.let { v ->
            add(
                "Video   " + listOfNotNull(
                    codecName(v.mime, v.codecs),
                    if (v.width > 0 && v.height > 0) "${v.width}×${v.height}" else null,
                    if (v.fps > 0) String.format(Locale.ROOT, "%.3f fps", v.fps).replace(Regex("\\.?0+ fps$"), " fps") else null,
                    v.hdr,
                    bitrate(v.bitrate)
                ).joinToString(" · ")
            )
        }
        s.videoDecoder?.let { add("        " + decoder(it)) }
        s.audio?.let { a ->
            add(
                "Audio   " + listOfNotNull(
                    codecName(a.mime, null),
                    channels(a.channels),
                    if (a.sampleRate > 0) String.format(Locale.ROOT, "%.1f kHz", a.sampleRate / 1000f).replace(".0 kHz", " kHz") else null,
                    bitrate(a.bitrate),
                    a.language?.takeIf { it.isNotBlank() && it != "und" }
                ).joinToString(" · ")
            )
        }
        s.audioDecoder?.let { add("        " + decoder(it)) }
        add(
            "Buffer  " + String.format(Locale.ROOT, "%.1f s ahead", s.bufferAheadMs.coerceAtLeast(0) / 1000f) +
                (bitrate(s.networkBps)?.let { " · network $it" } ?: "")
        )
        add("Dropped ${s.droppedFrames} frames")
        if (s.subtitleDelayMs != 0L || s.audioDelayMs != 0L) {
            add(String.format(Locale.ROOT, "Sync    subtitles %+.1f s · audio %+.1f s", s.subtitleDelayMs / 1000f, s.audioDelayMs / 1000f))
        }
    }.joinToString("\n")

    private fun decoder(name: String) = "$name (${if (isSoftware(name)) "software" else "hardware"})"

    /** "hevc", "avc", "eac3"…; the codecs string when the MIME type says little ("hvc1.2.4.L153"). */
    private fun codecName(mime: String?, codecs: String?): String? {
        val sub = mime?.substringAfter('/')?.lowercase(Locale.ROOT) ?: return codecs
        return when (sub) {
            "avc" -> "H.264"
            "hevc" -> "HEVC"
            "x-vnd.on2.vp9" -> "VP9"
            "av01" -> "AV1"
            "dolby-vision" -> "Dolby Vision" + (codecs?.let { " ($it)" } ?: "")
            "mp4a-latm" -> "AAC"
            "eac3" -> "E-AC3"
            "eac3-joc" -> "E-AC3 Atmos"
            "ac3" -> "AC3"
            "vnd.dts" -> "DTS"
            "vnd.dts.hd" -> "DTS-HD"
            "true-hd" -> "TrueHD"
            "opus" -> "Opus"
            "flac" -> "FLAC"
            "mpeg" -> "MP3"
            "raw" -> "PCM"
            else -> sub.removePrefix("x-")
        }
    }

    private fun channels(n: Int): String? = when {
        n <= 0 -> null
        n == 1 -> "mono"
        n == 2 -> "stereo"
        n == 6 -> "5.1"
        n == 8 -> "7.1"
        else -> "$n ch"
    }

    private fun bitrate(bps: Number): String? {
        val b = bps.toLong()
        return when {
            b <= 0 -> null
            b >= 1_000_000 -> String.format(Locale.ROOT, "%.1f Mbps", b / 1_000_000f)
            else -> "${b / 1000} kbps"
        }
    }
}
