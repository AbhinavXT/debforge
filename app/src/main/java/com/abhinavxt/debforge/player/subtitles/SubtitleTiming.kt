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
 * debrid service by provider and source reference, a local file by name and video. Null when none applies.
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
    if (url.startsWith("file:") || url.startsWith("/")) {
        val name = url.substringBefore('?').substringAfterLast('/').ifEmpty { return null }
        return "local:$name|${videoId.orEmpty()}"
    }
    return null
}
