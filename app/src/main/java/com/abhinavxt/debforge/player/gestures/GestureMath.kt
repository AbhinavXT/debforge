package com.abhinavxt.debforge.player.gestures

import kotlin.math.abs

/** The numbers behind the gestures. Pure: unit-tested in GestureMathTest. */
object GestureMath {

    /** Speeds a long press can slide through. */
    val SPEEDS = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 2.5f, 3f, 4f)

    /** Speed while a long press is held, before sliding. */
    const val HOLD_SPEED = 2f

    /** A horizontal drag across the whole screen moves this far. */
    const val SCRUB_FULL_WIDTH_MS = 90_000L

    const val MIN_ZOOM = 1f
    const val MAX_ZOOM = 4f

    /** The speed [steps] away from [HOLD_SPEED] (clamped to the list). */
    fun speedAfterSlide(steps: Int): Float {
        val i = (SPEEDS.indexOf(HOLD_SPEED) + steps).coerceIn(0, SPEEDS.lastIndex)
        return SPEEDS[i]
    }

    /** Where a run of seek taps lands: [anchorMs] ± [seconds], inside the video. */
    fun seekTarget(anchorMs: Long, forward: Boolean, seconds: Int, durationMs: Long?): Long {
        val t = anchorMs + (if (forward) 1 else -1) * seconds * 1000L
        return clamp(t, durationMs)
    }

    /** Where a horizontal drag of [fraction] of the width lands. */
    fun scrubTarget(anchorMs: Long, fraction: Float, durationMs: Long?): Long =
        clamp(anchorMs + (fraction * SCRUB_FULL_WIDTH_MS).toLong(), durationMs)

    private fun clamp(t: Long, durationMs: Long?): Long {
        val max = durationMs?.takeIf { it > 0 } ?: Long.MAX_VALUE
        return t.coerceIn(0, max)
    }

    /** Zoom after pinching by [scale] from [startZoom]. */
    fun zoom(startZoom: Float, scale: Float): Float = (startZoom * scale).coerceIn(MIN_ZOOM, MAX_ZOOM)

    /** Just above 1× is almost always meant as "fit": snap back. */
    fun snapZoom(zoom: Float): Float = if (zoom < 1.05f) 1f else zoom

    /** 0..1 level moved by [delta], kept in range ([min] keeps brightness from going black). */
    fun level(current: Float, delta: Float, min: Float = 0f, max: Float = 1f): Float = (current + delta).coerceIn(min, max)

    /**
     * Swiping volume up past the phone's maximum keeps going into a boost,
     * up to this level (2 = 200 %, like VLC).
     */
    const val MAX_VOLUME_LEVEL = 2f

    /**
     * Extra gain for a volume [level] above 1 (the phone's maximum), in
     * millibels for Android's LoudnessEnhancer: 20·log10(level) dB, so 200 %
     * is +6 dB. 0 at or below the maximum.
     */
    fun boostGainMb(level: Float): Int {
        if (level <= 1f) return 0
        val l = level.coerceAtMost(MAX_VOLUME_LEVEL).toDouble()
        return (2000 * kotlin.math.log10(l)).toInt()
    }

    /** "1:02:03", "12:34", "0:05". */
    fun time(ms: Long): String {
        val s = abs(ms) / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    /** "+1:10", "−0:05" (a real minus sign). */
    fun delta(ms: Long): String = (if (ms < 0) "−" else "+") + time(ms)

    /** "2×", "1.5×", "0.25×". */
    fun speedLabel(speed: Float): String {
        val s = if (speed == speed.toInt().toFloat()) speed.toInt().toString()
        else speed.toString().trimEnd('0').trimEnd('.')
        return "$s×"
    }
}
