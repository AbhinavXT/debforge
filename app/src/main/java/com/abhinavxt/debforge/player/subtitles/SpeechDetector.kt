package com.abhinavxt.debforge.player.subtitles

import kotlin.math.log10
import kotlin.math.max

/**
 * Turns per-frame speech-band energy into speech activity with an adaptive threshold.
 *
 * Input: one non-negative energy value per 10 ms frame (any linear scale). Output: one boolean per
 * frame. The threshold follows a running noise floor (10th percentile of the log energy in a
 * +-[windowFrames] window, re-evaluated every [hopFrames]) plus a margin that is at least
 * [minMarginDb] and grows with the dynamic range (25% of floor-to-95th-percentile). A frame above
 * the threshold is speech and stays speech for [HANGOVER_FRAMES] (200 ms) after its last loud frame.
 *
 * Complexity: O(n log w) with w = 2*windowFrames per hop, so about 1.5k sorts of 3k values for a 2 h
 * film (720k frames). Deterministic, no Android APIs.
 */
object SpeechDetector {
    const val FRAME_MS = 10
    const val HANGOVER_FRAMES = 20

    fun detect(
        energy: FloatArray,
        windowFrames: Int = 1500,
        hopFrames: Int = 500,
        minMarginDb: Float = 6f,
        hangoverFrames: Int = HANGOVER_FRAMES,
        ensureActive: () -> Unit = {},
    ): BooleanArray {
        val n = energy.size
        val out = BooleanArray(n)
        if (n == 0) return out
        val db = FloatArray(n) { 10f * log10(max(energy[it], 0f) + 1e-12f) }
        var hangLeft = 0
        var blockStart = 0
        while (blockStart < n) {
            ensureActive()
            val blockEnd = minOf(n, blockStart + hopFrames)
            val center = (blockStart + blockEnd) / 2
            val from = max(0, center - windowFrames)
            val to = minOf(n, center + windowFrames)
            val sorted = db.copyOfRange(from, to)
            sorted.sort()
            val floor = sorted[((sorted.size - 1) * 0.10).toInt()]
            val peak = sorted[((sorted.size - 1) * 0.95).toInt()]
            val range = peak - floor
            // Too flat to contain speech over noise: treat the block as silence.
            val threshold = if (range < minMarginDb) Float.POSITIVE_INFINITY
            else floor + max(minMarginDb, 0.25f * range)
            for (i in blockStart until blockEnd) {
                if (db[i] > threshold) {
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
