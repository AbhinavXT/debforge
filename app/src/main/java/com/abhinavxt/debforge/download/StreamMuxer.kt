package com.abhinavxt.debforge.download

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
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

    private class Source(val extractor: MediaExtractor, val track: Int, var first: Long = -1L) {
        /** Sample time from the track's own start: the two files' clocks start at different points. */
        val time: Long get() = (extractor.sampleTime - first).coerceAtLeast(0L)
        val done: Boolean get() = extractor.sampleTime < 0
    }

    /** [audio] null: the video file's own audio, if it has any. */
    suspend fun mux(video: FileDescriptor, audio: FileDescriptor?, out: FileDescriptor) {
        val extractors = mutableListOf<MediaExtractor>()
        var muxer: MediaMuxer? = null
        try {
            muxer = MediaMuxer(out, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val sources = listOf(video to "video/", (audio ?: video) to "audio/").mapNotNull { (fd, kind) ->
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
                    throw IOException("Can't put ${format.getString(MediaFormat.KEY_MIME)} in an MP4", e)
                }
                Source(ex, track, ex.sampleTime)
            }
            // One file: one clock, so audio keeps its offset from the video.
            if (audio == null) sources.minOf { it.first }.let { start -> sources.forEach { it.first = start } }
            muxer.start()
            val buffer = ByteBuffer.allocateDirect(MAX_SAMPLE)
            val info = MediaCodec.BufferInfo()
            while (true) {
                coroutineContext.ensureActive()
                val next = sources.filter { !it.done }.minByOrNull { it.time } ?: break
                val size = next.extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val key = next.extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0
                info.set(0, size, next.time, if (key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                muxer.writeSampleData(next.track, buffer, info)
                next.extractor.advance()
            }
            muxer.stop()
        } catch (e: IllegalStateException) {
            throw IOException("Couldn't combine the video and its audio: ${e.message}", e)
        } finally {
            runCatching { muxer?.release() }
            extractors.forEach { runCatching { it.release() } }
        }
    }
}
