package com.abhinavxt.debforge.player.subtitles

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sin

/** Sample formats a decoder can hand back. */
enum class PcmEncoding(val bytesPerSample: Int) { PCM_8(1), PCM_16(2), PCM_24(3), PCM_32(4), FLOAT(4) }

/**
 * Turns decoded PCM into the speech-band energy of every 10 ms frame, streaming: chunks of any size
 * go in through [feed], no audio is kept. Pure (no Android APIs).
 *
 * Each chunk is downmixed to mono (the mean of the channels, LFE left out of 6+ channel layouts), band-passed to
 * speech (300 Hz high-pass then 3400 Hz low-pass, 2nd-order Butterworth) and its squares averaged per frame. Frame
 * boundaries are exact in time for any rate (fractional samples per frame), so a 22.05 kHz track does not
 * drift. When the rate changes the filter restarts and the partial frame is closed early.
 */
class SpeechBandEnergy(expectedFrames: Int = 1 shl 16) {
    private var frames = FloatArray(maxOf(expectedFrames, 16))
    var frameCount = 0
        private set

    private var rate = 0
    private var hp = Biquad.Z
    private var lp = Biquad.Z
    private var hpState = DoubleArray(2)
    private var lpState = DoubleArray(2)
    private var segFrames = 0L
    private var segSamples = 0L
    private var nextBoundary = 0L
    private var sum = 0.0
    private var count = 0

    /** Adds the remaining bytes of [buf] (little-endian [encoding], [channels] interleaved) at [sampleRate]. */
    fun feed(buf: ByteBuffer, encoding: PcmEncoding, channels: Int, sampleRate: Int) {
        if (channels < 1 || sampleRate < 1000) return
        if (sampleRate != rate) startSegment(sampleRate)
        val b = buf.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        var n = b.remaining() / (encoding.bytesPerSample * channels)
        while (n-- > 0) {
            var mono = 0.0
            var used = 0
            for (c in 0 until channels) {
                val v = read(b, encoding)
                if (channels >= 6 && c == 3) continue
                mono += v
                used++
            }
            push(mono / used)
        }
    }

    /** Closes the partial last frame (if it is at least half a frame) and returns the energies. */
    fun finish(): FloatArray {
        if (count > 0 && rate > 0 && count * 2 >= samplesPerFrame()) closeFrame()
        return frames.copyOf(frameCount)
    }

    private fun samplesPerFrame() = rate / 100.0

    private fun startSegment(newRate: Int) {
        if (count > 0) closeFrame()
        rate = newRate
        hp = Biquad.highPass(newRate, 300.0)
        lp = Biquad.lowPass(newRate, 3400.0)
        hpState = DoubleArray(2)
        lpState = DoubleArray(2)
        segFrames = 0
        segSamples = 0
        nextBoundary = samplesPerFrame().roundToLong().coerceAtLeast(1)
    }

    private fun push(x: Double) {
        val y = lp.step(hp.step(x, hpState), lpState)
        sum += y * y
        count++
        segSamples++
        if (segSamples >= nextBoundary) {
            closeFrame()
            segFrames++
            nextBoundary = ((segFrames + 1) * samplesPerFrame()).roundToLong()
        }
    }

    private fun closeFrame() {
        if (frameCount == frames.size) frames = frames.copyOf(frames.size * 2)
        frames[frameCount++] = (sum / count).toFloat()
        sum = 0.0
        count = 0
    }

    private fun read(b: ByteBuffer, e: PcmEncoding): Double = when (e) {
        PcmEncoding.PCM_8 -> ((b.get().toInt() and 0xFF) - 128) / 128.0
        PcmEncoding.PCM_16 -> b.short / 32768.0
        PcmEncoding.PCM_24 -> {
            val v = (b.get().toInt() and 0xFF) or ((b.get().toInt() and 0xFF) shl 8) or (b.get().toInt() shl 16)
            v / 8388608.0
        }
        PcmEncoding.PCM_32 -> b.int / 2147483648.0
        PcmEncoding.FLOAT -> b.float.toDouble().let { if (it.isNaN()) 0.0 else it }
    }

    /** Direct form II transposed biquad. */
    private class Biquad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {
        fun step(x: Double, s: DoubleArray): Double {
            val y = b0 * x + s[0]
            s[0] = b1 * x - a1 * y + s[1]
            s[1] = b2 * x - a2 * y
            return y
        }

        companion object {
            val Z = Biquad(1.0, 0.0, 0.0, 0.0, 0.0)
            private const val Q = 0.70710678

            /** Cut-off is held below Nyquist for very low sample rates. */
            fun lowPass(rate: Int, f: Double): Biquad {
                val w = 2 * PI * min(f, rate * 0.45) / rate
                val alpha = sin(w) / (2 * Q)
                val c = cos(w)
                val a0 = 1 + alpha
                return Biquad((1 - c) / 2 / a0, (1 - c) / a0, (1 - c) / 2 / a0, -2 * c / a0, (1 - alpha) / a0)
            }

            fun highPass(rate: Int, f: Double): Biquad {
                val w = 2 * PI * f / rate
                val alpha = sin(w) / (2 * Q)
                val c = cos(w)
                val a0 = 1 + alpha
                return Biquad((1 + c) / 2 / a0, -(1 + c) / a0, (1 + c) / 2 / a0, -2 * c / a0, (1 - alpha) / a0)
            }
        }
    }
}
