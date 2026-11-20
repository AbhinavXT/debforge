package com.abhinavxt.debforge.player.subtitles

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * A linear correction of subtitle times: a cue at `t` ms in the file shows at `a * t + bMs`.
 * `a` fixes drift (frame-rate mismatch), `bMs` a plain offset. Pure.
 */
data class SubtitleTiming(val a: Double = 1.0, val bMs: Double = 0.0) {
    val isIdentity: Boolean get() = a == 1.0 && bMs == 0.0

    fun transform(ms: Long): Long = (a * ms + bMs).roundToLong()

    fun transformUs(us: Long): Long = (a * us + bMs * 1000.0).roundToLong()

    /** This correction first, then [next]. */
    fun compose(next: SubtitleTiming): SubtitleTiming = SubtitleTiming(next.a * a, next.a * bMs + next.bMs)

    /** Needs a > 0; the store rejects anything else on load. */
    fun inverse(): SubtitleTiming = SubtitleTiming(1.0 / a, -bMs / a)

    /** Scales by [ratio] around [anchorMs] (displayed time), so the cue shown there stays put. */
    fun scaledAround(ratio: Double, anchorMs: Long): SubtitleTiming =
        compose(SubtitleTiming(ratio, anchorMs * (1.0 - ratio)))

    fun shiftedBy(deltaMs: Long): SubtitleTiming = compose(SubtitleTiming(1.0, deltaMs.toDouble()))

    companion object {
        val IDENTITY = SubtitleTiming()

        /** Accepted range of the rate in a two-point fit: real frame-rate mismatches are within 4.2%. */
        val FIT_RANGE = 0.9..1.1

        /**
         * The correction that sends the file times [rawAMs] and [rawBMs] to [targetAMs] and [targetBMs]
         * (the two lines the user matched). Null when the points coincide or the rate is implausible.
         */
        fun fitTwoPoints(rawAMs: Long, targetAMs: Long, rawBMs: Long, targetBMs: Long): SubtitleTiming? {
            if (rawAMs == rawBMs) return null
            val a = (targetBMs - targetAMs).toDouble() / (rawBMs - rawAMs).toDouble()
            if (a.isNaN() || a !in FIT_RANGE) return null
            return SubtitleTiming(a, targetAMs - a * rawAMs)
        }

        const val DEFAULT_REACTION_MS = 300L

        /**
         * Shift to add when the user tapped the cue starting at [cueStartMs] (as shown now) at the moment
         * the line was heard, [positionMs]; the person reacts [reactionMs] late.
         */
        fun offsetFromTappedLine(cueStartMs: Long, positionMs: Long, reactionMs: Long = DEFAULT_REACTION_MS): Long =
            positionMs - reactionMs - cueStartMs

        /** Frame-rate presets as (subtitle fps, video fps); both directions are listed. */
        val FPS_PRESETS: List<Pair<Double, Double>> = listOf(
            23.976 to 25.0, 25.0 to 23.976,
            24.0 to 25.0, 25.0 to 24.0,
            23.976 to 24.0, 24.0 to 23.976
        )

        /** Ratio for subtitles made for [subtitleFps] played with a video of [videoFps]. */
        fun fpsRatio(subtitleFps: Double, videoFps: Double): Double = subtitleFps / videoFps

        /** [current] with a frame-rate fix that leaves the cue at [anchorMs] where it is. */
        fun fpsPreset(current: SubtitleTiming, subtitleFps: Double, videoFps: Double, anchorMs: Long): SubtitleTiming =
            current.scaledAround(fpsRatio(subtitleFps, videoFps), anchorMs)

        fun approximatelyEquals(x: SubtitleTiming, y: SubtitleTiming, eps: Double = 1e-9): Boolean =
            abs(x.a - y.a) < eps && abs(x.bMs - y.bMs) < 1e-3
    }
}

/**
 * The key a subtitle's correction is remembered under: an OpenSubtitles pick by file id, a file from the
 * debrid service by provider and source reference, a local file by its path (below the storage root) and video.
 * Null when none applies.
 */
fun timingKeyFor(
    videoId: String?,
    url: String,
    provider: String?,
    sourceRef: String?,
    osFileId: Long? = null
): String? {
    if (osFileId != null) return "os:$osFileId"
    if (provider != null && sourceRef != null) return "$provider:$sourceRef"
    val path = when {
        url.startsWith("file:") || url.startsWith("/") ->
            decodeQuietly(url.substringBefore('?').removePrefix("file://").removePrefix("file:"))
        // A sidecar linked through the file provider or the picked folder: the last part of the (percent-encoded)
        // URL; the text after its last ':' is the path inside the storage (the document id).
        url.startsWith("content:") -> decodeQuietly(url.substringBefore('?').substringAfterLast('/')).substringAfterLast(':')
        else -> return null
    }
    val relative = relativeStoragePath(path)
    if (relative.substringAfterLast('/').isEmpty()) return null
    return "local:$relative|${videoId.orEmpty()}"
}

private fun decodeQuietly(s: String) = runCatching { java.net.URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") }.getOrDefault(s)

private val STORAGE_ROOT = Regex("^/(?:storage/emulated/\\d+|storage/[^/]+|sdcard|mnt/sdcard)/")

/** [path] without a leading storage root, so one file gives one key whether linked as a path or a document id. */
internal fun relativeStoragePath(path: String): String = path.replaceFirst(STORAGE_ROOT, "").trimStart('/')

/** The key used before local keys held the whole path (`local:<file name>|video`); null for other keys. */
fun legacyTimingKey(key: String): String? {
    if (!key.startsWith("local:")) return null
    val bar = key.lastIndexOf('|')
    if (bar < 0) return null
    val name = key.substring(6, bar).substringAfterLast('/')
    return "local:$name${key.substring(bar)}".takeIf { it != key }
}

/** True for the ids [timingKeyFor] makes; false for ids of embedded tracks (such as "1:en" in HLS). */
fun isTimingKey(id: String): Boolean =
    id.startsWith("os:") || id.startsWith("local:") ||
        com.abhinavxt.debforge.domain.ProviderId.values().any { id.startsWith(it.name + ":") }

