package com.abhinavxt.debforge.player.subtitles

import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Developer harness, skipped unless DEBFORGE_PCM and DEBFORGE_SRT are set. Feeds raw s16le PCM through the same
 * SpeechBandEnergy -> SpeechDetector -> SpeechAlign path as the app, with the cues of a real .srt shifted by
 * DEBFORGE_SHIFT_MS (default 0) and stretched by DEBFORGE_STRETCH (default 1.0).
 *
 *   ffmpeg -i film.mkv -vn -f s16le -ac 2 -ar 44100 film.pcm
 *   DEBFORGE_PCM=film.pcm DEBFORGE_RATE=44100 DEBFORGE_CHANNELS=2 DEBFORGE_SRT=film.srt DEBFORGE_SHIFT_MS=4000 \
 *     ./gradlew :app:testDebugUnitTest --tests '*RealFilmSyncHarness*' -i
 */
class RealFilmSyncHarness {
    private fun env(n: String) = System.getenv(n)?.takeIf { it.isNotBlank() }

    private fun parseSrt(text: String): List<CueSpan> {
        val re = Regex("(\\d+):(\\d+):(\\d+)[,.](\\d+)\\s*-->\\s*(\\d+):(\\d+):(\\d+)[,.](\\d+)")
        fun ms(h: String, m: String, s: String, f: String) =
            ((h.toLong() * 60 + m.toLong()) * 60 + s.toLong()) * 1000 + f.padEnd(3, '0').take(3).toLong()
        return re.findAll(text).map {
            val g = it.groupValues
            CueSpan(ms(g[1], g[2], g[3], g[4]), ms(g[5], g[6], g[7], g[8]))
        }.toList()
    }

    @Test fun realFilm() {
        val pcm = env("DEBFORGE_PCM")
        val srt = env("DEBFORGE_SRT")
        assumeTrue("set DEBFORGE_PCM and DEBFORGE_SRT", pcm != null && srt != null)
        val rate = env("DEBFORGE_RATE")?.toInt() ?: 44100
        val ch = env("DEBFORGE_CHANNELS")?.toInt() ?: 1
        val shift = env("DEBFORGE_SHIFT_MS")?.toLong() ?: 0L
        val stretch = env("DEBFORGE_STRETCH")?.toDouble() ?: 1.0
        val cues = parseSrt(File(srt!!).readText()).map {
            CueSpan((it.startMs * stretch).toLong() + shift, (it.endMs * stretch).toLong() + shift)
        }

        val t0 = System.nanoTime()
        val energy = SpeechBandEnergy()
        val buf = ByteArray(65536 * ch)
        FileInputStream(pcm!!).use { ins ->
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                energy.feed(ByteBuffer.wrap(buf, 0, n - n % (2 * ch)), PcmEncoding.PCM_16, ch, rate)
            }
        }
        val e = energy.finish()
        val t1 = System.nanoTime()
        val speech = SpeechDetector.detect(e)
        val t2 = System.nanoTime()
        val ratio = speech.count { it }.toDouble() / speech.size
        val cueMs = cues.sumOf { it.endMs - it.startMs }
        println("HARNESS frames=${e.size} speechRatio=${"%.3f".format(ratio)} cues=${cues.size} cueCoverage=${"%.3f".format(cueMs / (e.size * 10.0))}")
        val r = SpeechAlign.align(speech, cues)
        val t3 = System.nanoTime()
        println("HARNESS result=$r")
        println("HARNESS timing energy=${(t1 - t0) / 1_000_000}ms detect=${(t2 - t1) / 1_000_000}ms align=${(t3 - t2) / 1_000_000}ms")
        for (fr in floatArrayOf(0.2f, 0.35f, 0.5f, 0.65f)) for (m in floatArrayOf(1.5f, 3f)) {
            val sp = SpeechDetector.detect(e, minMarginDb = m, marginFraction = fr)
            var inCue = 0L; var cueFrames = 0L
            for (c in cues) for (f in (c.startMs / 10).toInt() until minOf((c.endMs / 10).toInt(), sp.size)) { if (f >= 0) { cueFrames++; if (sp[f]) inCue++ } }
            val tot = sp.count { it }
            println("HARNESS frac=$fr margin=$m ratio=${"%.3f".format(tot.toDouble() / sp.size)} recall=${"%.3f".format(inCue.toDouble() / maxOf(1, cueFrames))} prec=${"%.3f".format(inCue.toDouble() / maxOf(1, tot))} -> ${SpeechAlign.align(sp, cues, minScore = 0.0, minConfidence = 0.0)}")
        }
        println("HARNESS atTruth(+-300ms)=${SpeechAlign.align(speech, cues, maxLagMs = 300, minScore = 0.0, minConfidence = 0.0)}")
        env("DEBFORGE_DUMP")?.let { d -> File(d).writeBytes(ByteArray(speech.size) { if (speech[it]) 1 else 0 }) }
        val loose = SpeechAlign.align(speech, cues, minScore = 0.0, minConfidence = 0.0)
        println("HARNESS loose=$loose")
    }
}
