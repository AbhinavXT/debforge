package com.abhinavxt.debforge.player.subtitles

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

class AudioSpeechScannerTest {
    private fun tone(freq: Double, rate: Int, seconds: Double, amp: Double = 0.5): DoubleArray =
        DoubleArray((rate * seconds).toInt()) { amp * sin(2 * PI * freq * it / rate) }

    private fun noise(rate: Int, seconds: Double, amp: Double, seed: Int = 1): DoubleArray {
        val r = Random(seed)
        return DoubleArray((rate * seconds).toInt()) { amp * (r.nextDouble() * 2 - 1) }
    }

    private fun pcm16(mono: DoubleArray, channels: Int = 1): ByteBuffer {
        val b = ByteBuffer.allocate(mono.size * channels * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (x in mono) repeat(channels) { b.putShort((x * 32767).toInt().toShort()) }
        b.flip()
        return b
    }

    private fun pcmFloat(mono: DoubleArray, channels: Int = 1): ByteBuffer {
        val b = ByteBuffer.allocate(mono.size * channels * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (x in mono) repeat(channels) { b.putFloat(x.toFloat()) }
        b.flip()
        return b
    }

    private fun slice(b: ByteBuffer, from: Int, to: Int): ByteBuffer {
        val d = b.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        d.position(from)
        d.limit(to)
        return d
    }

    private fun energy(buf: ByteBuffer, enc: PcmEncoding, channels: Int, rate: Int, chunk: Int): FloatArray {
        val e = SpeechBandEnergy()
        var pos = 0
        val frameBytes = enc.bytesPerSample * channels
        while (pos < buf.limit()) {
            // Chunks need not be a multiple of a sample frame in a real decoder, but MediaCodec hands whole ones.
            val end = minOf(buf.limit(), pos + chunk * frameBytes)
            e.feed(slice(buf, pos, end), enc, channels, rate)
            pos = end
        }
        return e.finish()
    }

    @Test fun oneFramePerTenMillisecondsAtAnyRate() {
        for (rate in intArrayOf(48_000, 44_100, 22_050, 16_000, 11_025)) {
            val f = energy(pcm16(tone(1000.0, rate, 10.0)), PcmEncoding.PCM_16, 1, rate, 1000)
            assertTrue("$rate: ${f.size}", f.size in 999..1001)
        }
    }

    @Test fun chunkSizeDoesNotChangeTheResult() {
        val buf = pcm16(tone(1000.0, 44_100, 3.0))
        val a = energy(buf, PcmEncoding.PCM_16, 1, 44_100, 100_000)
        val b = energy(buf, PcmEncoding.PCM_16, 1, 44_100, 777)
        val c = energy(buf, PcmEncoding.PCM_16, 1, 44_100, 1)
        assertEquals(a.size, b.size)
        assertEquals(a.size, c.size)
        for (i in a.indices) {
            assertEquals(a[i], b[i], 1e-6f)
            assertEquals(a[i], c[i], 1e-6f)
        }
    }

    @Test fun speechBandToneBeatsLowAndHighTones() {
        val rate = 16_000
        fun mid(f: Double) = energy(pcm16(tone(f, rate, 2.0)), PcmEncoding.PCM_16, 1, rate, 512).drop(50).average()
        val inBand = mid(1000.0)
        assertTrue(inBand > 20 * mid(50.0))
        assertTrue(inBand > 20 * mid(7000.0))
    }

    @Test fun toneStandsOutFromQuietNoise() {
        val rate = 16_000
        val sig = noise(rate, 2.0, 0.02) + tone(1000.0, rate, 2.0, 0.3) + noise(rate, 2.0, 0.02, 2)
        val f = energy(pcm16(sig), PcmEncoding.PCM_16, 1, rate, 640)
        val quiet = f.slice(20..180).average()
        val loud = f.slice(220..380).average()
        assertTrue("loud=$loud quiet=$quiet", loud > 30 * quiet)
    }

    @Test fun floatAndInt16Agree() {
        val sig = tone(800.0, 16_000, 2.0)
        val a = energy(pcm16(sig), PcmEncoding.PCM_16, 1, 16_000, 400)
        val b = energy(pcmFloat(sig), PcmEncoding.FLOAT, 1, 16_000, 400)
        assertEquals(a.size, b.size)
        for (i in 50 until a.size) assertEquals(b[i].toDouble(), a[i].toDouble(), b[i] * 0.01)
    }

    @Test fun stereoOfTheSameSignalEqualsMono() {
        val sig = tone(800.0, 16_000, 2.0)
        val mono = energy(pcm16(sig), PcmEncoding.PCM_16, 1, 16_000, 400)
        val stereo = energy(pcm16(sig, 2), PcmEncoding.PCM_16, 2, 16_000, 400)
        for (i in 50 until mono.size) assertEquals(mono[i], stereo[i], mono[i] * 0.001f)
    }

    @Test fun oppositeChannelsCancelInTheDownmix() {
        val sig = tone(800.0, 16_000, 1.0)
        val b = ByteBuffer.allocate(sig.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (x in sig) { b.putShort((x * 30000).toInt().toShort()); b.putShort((-x * 30000).toInt().toShort()) }
        b.flip()
        val f = energy(b, PcmEncoding.PCM_16, 2, 16_000, 400)
        assertTrue(f.all { it < 1e-9f })
    }

    @Test fun lfeIsLeftOutOfSixChannels() {
        // Loud tone only on channel 3 (LFE): silent mono result.
        val n = 16_000
        val b = ByteBuffer.allocate(n * 6 * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) for (c in 0 until 6) b.putShort(if (c == 3) (20000 * sin(2 * PI * 1000 * i / 16_000)).toInt().toShort() else 0)
        b.flip()
        val f = energy(b, PcmEncoding.PCM_16, 6, 16_000, 400)
        assertTrue(f.all { it < 1e-9f })
    }

    @Test fun rateChangeKeepsTimeAndFrameCount() {
        val e = SpeechBandEnergy()
        e.feed(pcm16(tone(1000.0, 48_000, 5.0)), PcmEncoding.PCM_16, 1, 48_000)
        e.feed(pcm16(tone(1000.0, 24_000, 5.0)), PcmEncoding.PCM_16, 1, 24_000)
        val f = e.finish()
        assertTrue("${f.size}", f.size in 998..1002)
        assertTrue(f[250] > 0.01f && f[750] > 0.01f)
    }

    @Test fun otherIntegerWidths() {
        val sig = tone(1000.0, 16_000, 1.0)
        val ref = energy(pcm16(sig), PcmEncoding.PCM_16, 1, 16_000, 400)
        val b24 = ByteBuffer.allocate(sig.size * 3).order(ByteOrder.LITTLE_ENDIAN)
        val b32 = ByteBuffer.allocate(sig.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        val b8 = ByteBuffer.allocate(sig.size)
        for (x in sig) {
            val v = (x * 8388607).toInt()
            b24.put(v.toByte()).put((v shr 8).toByte()).put((v shr 16).toByte())
            b32.putInt((x * 2147483647.0).toLong().toInt())
            b8.put((x * 127 + 128).toInt().toByte())
        }
        for (b in listOf(b24, b32, b8)) b.flip()
        assertEquals(ref[60].toDouble(), energy(b24, PcmEncoding.PCM_24, 1, 16_000, 400)[60].toDouble(), ref[60] * 0.02)
        assertEquals(ref[60].toDouble(), energy(b32, PcmEncoding.PCM_32, 1, 16_000, 400)[60].toDouble(), ref[60] * 0.02)
        assertEquals(ref[60].toDouble(), energy(b8, PcmEncoding.PCM_8, 1, 16_000, 400)[60].toDouble(), ref[60] * 0.05)
    }

    @Test fun ignoresBadInputAndKeepsNoAudio() {
        val e = SpeechBandEnergy()
        e.feed(ByteBuffer.allocate(100), PcmEncoding.PCM_16, 0, 16_000)
        e.feed(ByteBuffer.allocate(100), PcmEncoding.PCM_16, 1, 0)
        assertEquals(0, e.finish().size)
    }

    @Test fun trackOrderPutsTheSelectedLanguageFirst() {
        assertEquals(listOf(1, 0, 2), AudioSpeechScanner.orderTracks(listOf("fra", "eng", null), "en"))
        assertEquals(listOf(0, 1, 2), AudioSpeechScanner.orderTracks(listOf("fra", "eng", null), null))
        assertEquals(listOf(0, 2, 1), AudioSpeechScanner.orderTracks(listOf("fra", "eng", "fre"), "fr"))
        assertEquals(listOf(0, 1), AudioSpeechScanner.orderTracks(listOf("und", "jpn"), "en"))
    }

    @Test fun languageMatchesAcrossForms() {
        assertTrue(sameLanguage("en", "eng"))
        assertTrue(sameLanguage("de", "ger"))
        assertTrue(sameLanguage("deu", "ger"))
        assertTrue(sameLanguage("fr-CA", "fre"))
        assertFalse(sameLanguage("en", "fra"))
        assertFalse(sameLanguage("und", "und"))
        assertFalse(sameLanguage(null, "en"))
    }

    // --- the action's steps ----------------------------------------------------------------

    private val cues = listOf(CueSpan(1_000, 3_000), CueSpan(10_000, 12_000))
    private val match = AlignResult.Match(1.0, 1500.0, 0.5, 0.5)

    private fun run(
        scan: suspend ((Float) -> Unit) -> FloatArray,
        c: List<CueSpan> = cues,
        align: (BooleanArray, List<CueSpan>, () -> Unit) -> AlignResult = { _, _, _ -> match },
        states: MutableList<AutoSyncState> = mutableListOf(),
    ) = runBlocking { AutoSync.run(scan, c, { states += it }, align) }

    @Test fun syncedAppliesTheMatchAndReportsProgressThenMatching() {
        val states = mutableListOf<AutoSyncState>()
        val out = run({ p -> p(0.5f); FloatArray(100) }, states = states)
        assertEquals(AutoSyncOutcome.Synced(SubtitleTiming(1.0, 1500.0)), out)
        assertEquals(listOf(AutoSyncState.Listening(0f), AutoSyncState.Listening(0.5f), AutoSyncState.Matching), states)
    }

    @Test fun noMatchIsReported() {
        assertEquals(AutoSyncOutcome.NoMatch, run({ FloatArray(100) }, align = { _, _, _ -> AlignResult.NoMatch(0.0, 0.0) }))
    }

    @Test fun noCuesMeansNoMatchWithoutListening() {
        val states = mutableListOf<AutoSyncState>()
        assertEquals(AutoSyncOutcome.NoMatch, run({ error("must not scan") }, c = emptyList(), states = states))
        assertTrue(states.isEmpty())
    }

    @Test fun scanFailuresMapToOutcomes() {
        assertEquals(AutoSyncOutcome.NoDecoder, run({ throw ScanFailure(true, "x") }))
        assertEquals(AutoSyncOutcome.Failed, run({ throw ScanFailure(false, "x") }))
        assertEquals(AutoSyncOutcome.Failed, run({ throw IllegalStateException("codec") }))
    }

    @Test fun cancellationPropagatesAndIsNotAFailure() {
        try {
            run({ throw CancellationException("cancelled") })
            fail("expected cancellation")
        } catch (_: CancellationException) {
        }
    }

    @Test fun alignSeesTheDetectedSpeech() {
        var seen = -1
        run({ FloatArray(2000) { if (it in 500..900) 1f else 0.001f } }, align = { s, _, _ -> seen = s.count { it }; match })
        assertTrue("speech frames=$seen", seen in 380..460)
    }
}
