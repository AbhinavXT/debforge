package com.abhinavxt.debforge.player.subtitles

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** A subtitle cue interval in milliseconds. */
data class CueSpan(val startMs: Long, val endMs: Long)

sealed interface AlignResult {
    /** Subtitle time t maps to video time a*t + b. [score] is the normalized correlation peak, [confidence] is 1 - runnerUp/best. */
    data class Match(val a: Double, val bMs: Double, val score: Double, val confidence: Double) : AlignResult

    data class NoMatch(val bestScore: Double, val confidence: Double) : AlignResult
}

/**
 * Finds the timing transform t' = a*t + b that best lines subtitle cues up with detected speech.
 *
 * For each candidate rate a the cues are scaled, rasterised at 100 ms (fraction of each bin covered),
 * and cross-correlated with the speech activity (also 100 ms bins) through a radix-2 FFT, lag limited
 * to +-maxLagMs. Score is the mean-removed normalized correlation. The winner is compared with the
 * runner-up (best of any clearly different rate (>0.15%), or the same rate with a peak more than 1 s away); too weak a
 * score or too small a margin yields [AlignResult.NoMatch]. The winner's offset is then refined at
 * 10 ms steps within +-200 ms by direct overlap against the 10 ms activity.
 *
 * Complexity: speech FFT once plus two FFTs per rate, each O(N log N) with N = 2^18 for a 2 h film
 * (72k bins per signal, each clipped to 2^17 bins, about 3.6 h); refinement is O(cues * 41).
 * Memory is a few 2 MB double arrays.
 */
object SpeechAlign {
    val RATES = doubleArrayOf(
        1.0, 25.0 / 23.976, 23.976 / 25.0, 25.0 / 24.0, 24.0 / 25.0, 24.0 / 23.976, 23.976 / 24.0,
    )
    private const val BIN_MS = 100
    private const val FRAME_MS = SpeechDetector.FRAME_MS
    private const val MAX_BINS = 1 shl 17

    fun align(
        speech: BooleanArray,
        cues: List<CueSpan>,
        maxLagMs: Int = 600_000,
        minScore: Double = 0.12,
        minConfidence: Double = 0.10,
        onProgress: (Float) -> Unit = {},
        ensureActive: () -> Unit = {},
    ): AlignResult {
        val valid = cues.filter { it.endMs > it.startMs }
        if (speech.isEmpty() || valid.isEmpty()) return AlignResult.NoMatch(0.0, 0.0)

        val nS = min((speech.size * FRAME_MS + BIN_MS - 1) / BIN_MS, MAX_BINS)
        val sp = DoubleArray(nS)
        for (i in speech.indices) {
            if (speech[i]) {
                val bin = i * FRAME_MS / BIN_MS
                if (bin < nS) sp[bin] += FRAME_MS.toDouble() / BIN_MS
            }
        }
        val spNorm = removeMean(sp)
        if (spNorm == 0.0) return AlignResult.NoMatch(0.0, 0.0)

        val maxEnd = valid.maxOf { it.endMs }
        val maxScale = RATES.max()
        val nCmax = min((maxEnd * maxScale / BIN_MS).toInt() + 2, MAX_BINS)
        var size = 1
        while (size < nS + nCmax) size = size shl 1
        val fft = Fft(size)
        val sRe = DoubleArray(size)
        val sIm = DoubleArray(size)
        System.arraycopy(sp, 0, sRe, 0, nS)
        fft.transform(sRe, sIm)
        onProgress(0.05f)

        val maxLag = min(maxLagMs / BIN_MS, size / 2 - 1)
        val peaks = DoubleArray(RATES.size) { Double.NEGATIVE_INFINITY }
        val peakLag = IntArray(RATES.size)
        val corrs = arrayOfNulls<DoubleArray>(RATES.size)
        for ((ri, a) in RATES.withIndex()) {
            ensureActive()
            val cRe = DoubleArray(size)
            val cIm = DoubleArray(size)
            val nC = rasterise(valid, a, cRe)
            val cNorm = removeMean(cRe, nC)
            if (cNorm > 0.0) {
                fft.transform(cRe, cIm)
                // R = IFFT(S * conj(C)): R[L] = sum_j speech[j+L] * cue[j]
                for (k in 0 until size) {
                    val r = sRe[k] * cRe[k] + sIm[k] * cIm[k]
                    val i = sIm[k] * cRe[k] - sRe[k] * cIm[k]
                    cRe[k] = r
                    cIm[k] = i
                }
                fft.transform(cRe, cIm, inverse = true)
                val norm = spNorm * cNorm
                val lags = DoubleArray(2 * maxLag + 1)
                for (l in -maxLag..maxLag) {
                    val idx = if (l >= 0) l else size + l
                    val v = cRe[idx] / norm
                    lags[l + maxLag] = v
                    if (v > peaks[ri]) {
                        peaks[ri] = v
                        peakLag[ri] = l
                    }
                }
                corrs[ri] = lags
            }
            onProgress(0.05f + 0.8f * (ri + 1) / RATES.size)
        }

        var best = 0
        for (i in RATES.indices) if (peaks[i] > peaks[best]) best = i
        val bestScore = peaks[best]
        val lags = corrs[best]
        if (lags == null || bestScore <= 0.0) return AlignResult.NoMatch(max(bestScore, 0.0), 0.0)

        var runner = 0.0
        for (i in RATES.indices) {
            // Rates within 0.15% of the winner (e.g. 1 and 24/23.976) are indistinguishable on short
            // files; they are not treated as a rival.
            if (i != best && abs(RATES[i] / RATES[best] - 1.0) > 0.0015 && peaks[i] > runner) runner = peaks[i]
        }
        val guard = 1000 / BIN_MS
        for (l in -maxLag..maxLag) {
            if (abs(l - peakLag[best]) > guard) runner = max(runner, lags[l + maxLag])
        }
        val confidence = (1.0 - runner / bestScore).coerceIn(0.0, 1.0)
        if (bestScore < minScore || confidence < minConfidence) {
            return AlignResult.NoMatch(bestScore, confidence)
        }

        ensureActive()
        val a = RATES[best]
        val b = refine(speech, valid, a, peakLag[best] * BIN_MS.toDouble())
        onProgress(1f)
        return AlignResult.Match(a, b, bestScore, confidence)
    }

    /** Scales cues by [a], writes fractional bin coverage into [out], returns the number of bins used. */
    private fun rasterise(cues: List<CueSpan>, a: Double, out: DoubleArray): Int {
        var used = 0
        for (c in cues) {
            val s = c.startMs * a
            val e = c.endMs * a
            val first = floor(s / BIN_MS).toInt()
            val last = floor(e / BIN_MS).toInt()
            if (first >= MAX_BINS) continue
            for (bin in max(first, 0)..min(last, MAX_BINS - 1)) {
                val lo = max(s, bin.toDouble() * BIN_MS)
                val hi = min(e, (bin + 1).toDouble() * BIN_MS)
                if (hi > lo) {
                    out[bin] = min(1.0, out[bin] + (hi - lo) / BIN_MS)
                    if (bin + 1 > used) used = bin + 1
                }
            }
        }
        return used
    }

    /** Subtracts the mean over [n] leading elements (default all), returns the L2 norm of the result. */
    private fun removeMean(x: DoubleArray, n: Int = x.size): Double {
        if (n == 0) return 0.0
        var sum = 0.0
        for (i in 0 until n) sum += x[i]
        val mean = sum / n
        var sq = 0.0
        for (i in 0 until n) {
            x[i] -= mean
            sq += x[i] * x[i]
        }
        return sqrt(sq)
    }

    /** Direct overlap search at 10 ms steps within +-200 ms of [centerMs]; ties go to the smallest shift. */
    private fun refine(speech: BooleanArray, cues: List<CueSpan>, a: Double, centerMs: Double): Double {
        val prefix = IntArray(speech.size + 1)
        for (i in speech.indices) prefix[i + 1] = prefix[i] + if (speech[i]) 1 else 0
        fun overlap(b: Double): Long {
            var total = 0L
            for (c in cues) {
                val s = ((a * c.startMs + b) / FRAME_MS).roundToInt().coerceIn(0, speech.size)
                val e = ((a * c.endMs + b) / FRAME_MS).roundToInt().coerceIn(0, speech.size)
                if (e > s) total += prefix[e] - prefix[s]
            }
            return total
        }
        var bestB = centerMs
        var bestO = overlap(centerMs)
        for (d in 1..20) {
            for (sign in intArrayOf(-1, 1)) {
                val b = centerMs + sign * d * FRAME_MS
                val o = overlap(b)
                if (o > bestO) {
                    bestO = o
                    bestB = b
                }
            }
        }
        return bestB
    }
}
