package com.abhinavxt.debforge.player.subtitles

import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechAlignTest {
    private class Scene(val speech: BooleanArray, val bursts: List<CueSpan>)

    private fun scene(durationMs: Long, seed: Long): Scene {
        val r = Random(seed)
        val frames = BooleanArray((durationMs / 10).toInt())
        val bursts = ArrayList<CueSpan>()
        var t = 5_000L
        while (t < durationMs - 15_000) {
            val len = 800L + r.nextInt(4200)
            bursts += CueSpan(t, t + len)
            for (f in (t / 10).toInt() until ((t + len) / 10).toInt()) frames[f] = true
            t += len + 700L + r.nextInt(7000)
        }
        return Scene(frames, bursts)
    }

    private fun shifted(b: List<CueSpan>, delta: Long) = b.map { CueSpan(it.startMs + delta, it.endMs + delta) }

    @Test fun findsPlus3200Offset() {
        val s = scene(30 * 60_000L, 1)
        val r = SpeechAlign.align(s.speech, shifted(s.bursts, 3200)) as AlignResult.Match
        assertEquals(1.0, r.a, 1e-9)
        assertEquals(-3200.0, r.bMs, 20.0)
        assertTrue(r.confidence > 0.3)
    }

    @Test fun survivesFlippedFrames() {
        val s = scene(30 * 60_000L, 2)
        val r = Random(99)
        val noisy = s.speech.copyOf()
        for (i in noisy.indices) if (r.nextInt(10) == 0) noisy[i] = !noisy[i]
        val res = SpeechAlign.align(noisy, shifted(s.bursts, 3200)) as AlignResult.Match
        assertEquals(1.0, res.a, 1e-9)
        assertEquals(-3200.0, res.bMs, 20.0)
    }

    @Test fun findsStretchedRateWithOffset() {
        val s = scene(60 * 60_000L, 3)
        val a = 25.0 / 23.976
        val b = -5000.0
        // video time = a*cue + b  =>  cue = (t - b) / a
        val cues = s.bursts.map { CueSpan(((it.startMs - b) / a).toLong(), ((it.endMs - b) / a).toLong()) }
        val res = SpeechAlign.align(s.speech, cues) as AlignResult.Match
        assertEquals(a, res.a, 1e-9)
        assertEquals(b, res.bMs, 40.0)
    }

    @Test fun unrelatedCuesGiveNoMatch() {
        val s = scene(30 * 60_000L, 4)
        val other = scene(30 * 60_000L, 12345)
        assertTrue(SpeechAlign.align(s.speech, other.bursts) is AlignResult.NoMatch)
    }

    @Test fun cancellationAndProgress() {
        val s = scene(10 * 60_000L, 5)
        var last = 0f
        SpeechAlign.align(s.speech, s.bursts, onProgress = { last = it })
        assertEquals(1f, last, 0f)
        var thrown = false
        try {
            SpeechAlign.align(s.speech, s.bursts, ensureActive = { throw IllegalStateException("cancelled") })
        } catch (e: IllegalStateException) {
            thrown = true
        }
        assertTrue(thrown)
    }

    @Test fun twoHourFilmIsFast() {
        val s = scene(2 * 3600_000L, 6)
        val t0 = System.nanoTime()
        val res = SpeechAlign.align(s.speech, shifted(s.bursts, -7300)) as AlignResult.Match
        val ms = (System.nanoTime() - t0) / 1_000_000
        println("2h align took $ms ms")
        assertEquals(7300.0, res.bMs, 20.0)
        assertTrue("took $ms ms", ms < 10_000)
    }

    @Test fun detectorFindsBurstsWithHangoverAndDrift() {
        val n = 60_000 // 10 min
        val r = Random(7)
        val energy = FloatArray(n)
        for (i in 0 until n) {
            val floor = 1e-4 * (1.0 + 9.0 * i / n) // floor drifts 10 dB upward
            energy[i] = (floor * (0.8 + 0.4 * r.nextDouble())).toFloat()
        }
        val starts = intArrayOf(5_000, 15_000, 30_000, 45_000)
        for (st in starts) for (i in st until st + 500) energy[i] = 1f
        val out = SpeechDetector.detect(energy)
        for (st in starts) {
            for (i in st until st + 500 + SpeechDetector.HANGOVER_FRAMES) assertTrue("frame $i", out[i])
            assertTrue(!out[st - 5])
            assertTrue(!out[st + 500 + SpeechDetector.HANGOVER_FRAMES + 2])
        }
        assertEquals(starts.size * (500 + SpeechDetector.HANGOVER_FRAMES), out.count { it })
    }

    @Test fun fftMatchesNaiveDft() {
        for (n in intArrayOf(1, 2, 8, 64)) {
            val r = Random(n.toLong())
            val re = DoubleArray(n) { r.nextDouble() - 0.5 }
            val im = DoubleArray(n) { r.nextDouble() - 0.5 }
            val er = DoubleArray(n)
            val ei = DoubleArray(n)
            for (k in 0 until n) for (j in 0 until n) {
                val ang = -2 * PI * k * j / n
                er[k] += re[j] * cos(ang) - im[j] * sin(ang)
                ei[k] += re[j] * sin(ang) + im[j] * cos(ang)
            }
            val fr = re.copyOf()
            val fi = im.copyOf()
            Fft(n).transform(fr, fi)
            assertArrayEquals(er, fr, 1e-9)
            assertArrayEquals(ei, fi, 1e-9)
            Fft(n).transform(fr, fi, inverse = true)
            assertArrayEquals(re, fr, 1e-9)
            assertArrayEquals(im, fi, 1e-9)
        }
    }
}
