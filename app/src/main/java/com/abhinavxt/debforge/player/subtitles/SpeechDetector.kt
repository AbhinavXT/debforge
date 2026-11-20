package com.abhinavxt.debforge.player.subtitles

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Turns per-frame speech-band energy into speech activity.
 *
 * Input: one non-negative energy value per 10 ms frame (any linear scale). Output: one boolean per frame.
 *
 * Loudness alone does not separate dialogue from the rest of a film (music and effects fill the speech band
 * almost continuously; on real films 80% of frames were louder than the adaptive floor). What does is
 * burstiness: speech is syllables and pauses, so its level swings by many dB within a second, while music
 * beds and ambience are comparatively steady. The detector therefore scores each frame by the standard
 * deviation of the dB level over a [BURST_FRAMES] (1 s) window centred on it, and thresholds that score
 * adaptively: the threshold follows the 10th percentile of the score in a +-[windowFrames] window
 * (re-evaluated every [hopFrames]) plus [marginFraction] of the distance up to its 95th percentile, and at
 * least [minMarginDb]. The frame must also be above the local noise floor (10th percentile of the dB level)
 * by [minLevelDb], so digital silence is never speech. A speech frame stays speech for [hangoverFrames]
 * after its last one.
 *
 * Complexity: O(n) for the scores plus O(n log w) for the percentiles (w = 2*windowFrames per hop). No Android APIs.
 */
object SpeechDetector {
    const val FRAME_MS = 10
    const val HANGOVER_FRAMES = 10
    const val BURST_FRAMES = 100

    fun detect(
        energy: FloatArray,
        windowFrames: Int = 1500,
        hopFrames: Int = 500,
        minMarginDb: Float = 1.5f,
        hangoverFrames: Int = HANGOVER_FRAMES,
        marginFraction: Float = 0.5f,
        minLevelDb: Float = 6f,
        ensureActive: () -> Unit = {},
    ): BooleanArray {
        val n = energy.size
        val out = BooleanArray(n)
        if (n == 0) return out
        val db = FloatArray(n) { 10f * log10(max(energy[it], 0f) + 1e-12f) }

        // Levels below the 10th percentile of the whole film are clamped so silence cannot create swings.
        val globalSorted = db.copyOf().also { it.sort() }
        val clampLo = globalSorted[((n - 1) * 0.10).toInt()]
        val p1 = DoubleArray(n + 1)
        val p2 = DoubleArray(n + 1)
        for (i in 0 until n) {
            val v = max(db[i], clampLo).toDouble()
            p1[i + 1] = p1[i] + v
            p2[i + 1] = p2[i] + v * v
        }
        val score = FloatArray(n)
        val half = BURST_FRAMES / 2
        for (i in 0 until n) {
            val from = max(0, i - half)
            val to = minOf(n, i + half)
            val c = (to - from).toDouble()
            val mean = (p1[to] - p1[from]) / c
            score[i] = sqrt(max(0.0, (p2[to] - p2[from]) / c - mean * mean)).toFloat()
        }

        var hangLeft = 0
        var blockStart = 0
        while (blockStart < n) {
            ensureActive()
            val blockEnd = minOf(n, blockStart + hopFrames)
            val center = (blockStart + blockEnd) / 2
            val from = max(0, center - windowFrames)
            val to = minOf(n, center + windowFrames)
            val sc = score.copyOfRange(from, to).also { it.sort() }
            val lv = db.copyOfRange(from, to).also { it.sort() }
            val scFloor = sc[((sc.size - 1) * 0.10).toInt()]
            val scPeak = sc[((sc.size - 1) * 0.95).toInt()]
            val levelFloor = lv[((lv.size - 1) * 0.10).toInt()]
            val range = scPeak - scFloor
            // Too flat to contain speech over the background: silence.
            val scThreshold = if (range < minMarginDb) Float.POSITIVE_INFINITY
            else scFloor + max(minMarginDb, marginFraction * range)
            val levelThreshold = levelFloor + minLevelDb
            for (i in blockStart until blockEnd) {
                if (score[i] > scThreshold && db[i] > levelThreshold) {
                    hangLeft = hangoverFrames
                    out[i] = true
                } else if (hangLeft > 0) {
                    hangLeft--
                    out[i] = true
                }
            }
            blockStart = blockEnd
        }
        return out
    }
}
