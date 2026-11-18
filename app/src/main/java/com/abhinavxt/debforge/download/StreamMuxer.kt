package com.abhinavxt.debforge.download

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import kotlinx.coroutines.ensureActive
import java.io.FileDescriptor
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.coroutines.coroutineContext

/**
 * Combines a stream's joined video and its joined separate audio into one
 * MP4, copying samples as they are (no re-encoding). Also rewrites a joined
 * fragmented MP4 on its own: players take its length from the first piece's
 * index (a few seconds) and stop there. MP4 takes H.264 / H.265
 * video and AAC audio; anything else fails with a clear message.
 *
 * Samples are written in time order across both tracks: the platform muxer
 * holds the leading track in memory while the other one catches up.
 */
internal object StreamMuxer {

    private const val MAX_SAMPLE = 8 * 1024 * 1024

    private const val TAG = "StreamMuxer"

    private class Source(val extractor: MediaExtractor, val track: Int, var first: Long = -1L) {
        /** The extractor's current sample time, read once per sample (negative: no more samples). */
        var raw: Long = extractor.sampleTime
        val done: Boolean get() = raw < 0
        /** Sample time from the track's own start: the two files' clocks start at different points. */
        val time: Long get() = (raw - first).coerceAtLeast(0L)
    }

    /** WebM for .webm / .weba (VP9 + Opus), MP4 for everything else (H.264 / AV1 + AAC). */
    enum class Output(val muxerFormat: Int) {
        MP4(MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4), WEBM(MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM);

        companion object {
            fun of(path: String) = if (path.substringAfterLast('.').lowercase() in setOf("webm", "weba")) WEBM else MP4
        }
    }

    /** [video] null: audio only. [audio] null: the video file's own audio, if it has any. */
    suspend fun mux(video: FileDescriptor?, audio: FileDescriptor?, out: FileDescriptor, output: Output = Output.MP4) {
        val extractors = mutableListOf<MediaExtractor>()
        var muxer: MediaMuxer? = null
        try {
            muxer = MediaMuxer(out, output.muxerFormat)
            val sources = listOf(video to "video/", (audio ?: video) to "audio/").mapNotNull { (fd, kind) ->
                if (fd == null) return@mapNotNull null
                val ex = MediaExtractor().also { extractors += it }
                ex.setDataSource(fd)
                val index = (0 until ex.trackCount).firstOrNull {
                    ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith(kind) == true
                } ?: if (kind == "audio/" && audio == null) return@mapNotNull null // a silent video
                else throw IOException("The stream's ${kind.trimEnd('/')} has no readable track")
                ex.selectTrack(index)
                val format = ex.getTrackFormat(index)
                val track = try {
                    muxer.addTrack(format)
                } catch (e: IllegalArgumentException) {
                    throw IOException("Can't put ${format.getString(MediaFormat.KEY_MIME)} in a ${output.name}", e)
                }
                Source(ex, track, ex.sampleTime)
            }
            // One file: one clock, so audio keeps its offset from the video.
            if (audio == null && sources.isNotEmpty()) sources.minOf { it.first }.let { start -> sources.forEach { it.first = start } }
            muxer.start()
            val buffer = ByteBuffer.allocateDirect(MAX_SAMPLE)
            val info = MediaCodec.BufferInfo()
            val all = sources.toTypedArray()
            var readNs = 0L
            var advanceNs = 0L
            var writeNs = 0L
            var samples = 0L
            val startNs = System.nanoTime()
            while (true) {
                coroutineContext.ensureActive()
                var next: Source? = null
                if (all.size == 1) {
                    if (!all[0].done) next = all[0]
                } else {
                    var best = Long.MAX_VALUE
                    for (s in all) {
                        if (s.done) continue
                        val t = s.time
                        if (next == null || t < best) { next = s; best = t }
                    }
                }
                if (next == null) break
                val ex = next.extractor
                val t0 = System.nanoTime()
                val size = ex.readSampleData(buffer, 0)
                val t1 = System.nanoTime()
                readNs += t1 - t0
                if (size < 0) break
                val key = ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0
                info.set(0, size, next.time, if (key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                muxer.writeSampleData(next.track, buffer, info)
                val t2 = System.nanoTime()
                writeNs += t2 - t1
                ex.advance()
                next.raw = ex.sampleTime
                advanceNs += System.nanoTime() - t2
                samples++
            }
            val totalNs = System.nanoTime() - startNs
            logTiming(totalNs, samples, readNs, advanceNs, writeNs)
            muxer.stop()
        } catch (e: IllegalStateException) {
            throw IOException("Couldn't combine the video and its audio: ${e.message}", e)
        } finally {
            runCatching { muxer?.release() }
            extractors.forEach { runCatching { it.release() } }
        }
    }

    private fun logTiming(totalNs: Long, samples: Long, readNs: Long, advanceNs: Long, writeNs: Long) {
        val totalMs = totalNs / 1e6
        fun pct(ns: Long) = if (totalNs > 0) ns * 100 / totalNs else 0L
        Log.i(
            TAG,
            "muxed %d samples in %.0f ms (%.3f ms/sample): read %d%%, advance %d%%, write %d%%".format(
                samples, totalMs, if (samples > 0) totalMs / samples else 0.0,
                pct(readNs), pct(advanceNs), pct(writeNs),
            ),
        )
    }
}
