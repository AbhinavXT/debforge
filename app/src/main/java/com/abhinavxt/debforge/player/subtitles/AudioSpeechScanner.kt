package com.abhinavxt.debforge.player.subtitles

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Process
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Reads a local video's sound and returns its speech-band energy per 10 ms frame ([SpeechBandEnergy]).
 *
 * The audio track in the selected audio language is tried first, then the others in file order; a track
 * with no decoder on this device (DTS, TrueHD) or one that fails to decode falls through to the next, and if
 * none works the call throws [ScanFailure]. Decoding is MediaCodec in ByteBuffer mode on a background-priority
 * thread; only the frame energies are kept (about 3 MB for a 2 h film). Cancelling the calling coroutine stops
 * it between buffers.
 */
class AudioSpeechScanner(private val context: Context) {

    suspend fun scan(uri: Uri, preferredLanguage: String?, onProgress: (Float) -> Unit): FloatArray =
        withContext(Dispatchers.IO) {
            // A pooled thread: its priority is put back when the scan ends.
            val priorityBefore = runCatching { Process.getThreadPriority(0) }.getOrNull()
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
            try {
                val languages = audioTracks(uri)
                if (languages.isEmpty()) throw ScanFailure(false, "no audio track")
                firstReadable(orderTracks(languages.map { it.second }, preferredLanguage), languages.size) { track ->
                    decode(uri, languages[track].first, onProgress)
                }
            } finally {
                priorityBefore?.let { runCatching { Process.setThreadPriority(it) } }
            }
        }

    /** (extractor track index, ISO language) of each audio track. */
    private fun audioTracks(uri: Uri): List<Pair<Int, String?>> {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(context, uri, null)
            return (0 until ex.trackCount).mapNotNull { i ->
                val f = ex.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    i to (if (f.containsKey(MediaFormat.KEY_LANGUAGE)) f.getString(MediaFormat.KEY_LANGUAGE) else null)
                } else null
            }
        } catch (e: java.io.IOException) {
            throw ScanFailure(false, "cannot open the file", e)
        } catch (e: RuntimeException) {
            throw ScanFailure(false, "cannot open the file", e)
        } finally {
            ex.release()
        }
    }

    internal class NoDecoder(mime: String, cause: Throwable? = null) : Exception("no decoder for $mime", cause)

    private suspend fun decode(uri: Uri, trackIndex: Int, onProgress: (Float) -> Unit): FloatArray {
        val ctx = currentCoroutineContext()
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(context, uri, null)
            val format = ex.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: throw ScanFailure(false, "no mime")
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            ex.selectTrack(trackIndex)
            val created = try {
                MediaCodec.createDecoderByType(mime)
            } catch (e: Exception) {
                throw NoDecoder(mime, e)
            }
            codec = created
            try {
                created.configure(format, null, null, 0)
            } catch (e: Exception) {
                throw NoDecoder(mime, e)
            }
            created.start()

            val energy = SpeechBandEnergy(if (durationUs > 0) (durationUs / 10_000).toInt() + 100 else 1 shl 16)
            var rate = format.intOr(MediaFormat.KEY_SAMPLE_RATE, 48_000)
            var channels = format.intOr(MediaFormat.KEY_CHANNEL_COUNT, 2)
            var encoding = PcmEncoding.PCM_16
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var lastReported = -1
            var idle = 0
            while (!outputDone) {
                ctx.ensureActive()
                var progressed = false
                // Keep the decoder fed: queue every input buffer that is free right now, without waiting.
                while (!inputDone) {
                    val i = created.dequeueInputBuffer(0)
                    if (i < 0) break
                    val buf = created.getInputBuffer(i)!!
                    val size = ex.readSampleData(buf, 0)
                    if (size < 0) {
                        created.queueInputBuffer(i, 0, size.coerceAtLeast(0), 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        created.queueInputBuffer(i, 0, size, ex.sampleTime, 0)
                        ex.advance()
                    }
                    progressed = true
                }
                // Drain every output buffer that is ready. Only when nothing moved does the call wait (briefly), so the
                // decoder is never stalled in lockstep with a 10 ms timeout per buffer.
                while (!outputDone) {
                    val o = created.dequeueOutputBuffer(info, if (progressed) 0L else TIMEOUT_US)
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val f = created.outputFormat
                        rate = f.intOr(MediaFormat.KEY_SAMPLE_RATE, rate)
                        channels = f.intOr(MediaFormat.KEY_CHANNEL_COUNT, channels)
                        encoding = encodingOf(f.intOr(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT))
                            ?: throw ScanFailure(false, "unsupported PCM encoding")
                        progressed = true
                    } else if (o >= 0) {
                        progressed = true
                        idle = 0
                        if (info.size > 0) {
                            val out = created.getOutputBuffer(o)!!
                            out.position(info.offset)
                            out.limit(info.offset + info.size)
                            energy.feed(out, encoding, channels, rate)
                            if (durationUs > 0) {
                                val pct = (info.presentationTimeUs * 100 / durationUs).toInt().coerceIn(0, 100)
                                if (pct != lastReported) {
                                    lastReported = pct
                                    onProgress(pct / 100f)
                                }
                            }
                        }
                        created.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    } else {
                        // INFO_TRY_AGAIN_LATER: a decoder that never produces anything would spin here forever.
                        if (inputDone && !progressed && ++idle > 500) throw ScanFailure(false, "decoder stalled")
                        break
                    }
                }
            }
            val frames = energy.finish()
            if (frames.size < MIN_FRAMES) throw ScanFailure(false, "too little audio")
            onProgress(1f)
            return frames
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            ex.release()
        }
    }

    private fun MediaFormat.intOr(key: String, fallback: Int) = if (containsKey(key)) getInteger(key) else fallback

    companion object {
        /**
         * The result of [decode] for the first of [order] that works. A failure falls through to the next track;
         * cancellation (an [IllegalStateException] subclass) must not, so it is rethrown first. Throws [ScanFailure]
         * when none works; [trackCount] tells whether every one lacked a decoder.
         */
        internal suspend fun <T> firstReadable(order: List<Int>, trackCount: Int, decode: suspend (Int) -> T): T {
            var noDecoder = 0
            var lastError: Throwable? = null
            for (track in order) {
                currentCoroutineContext().ensureActive()
                try {
                    return decode(track)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: NoDecoder) {
                    noDecoder++
                    lastError = e
                } catch (e: ScanFailure) {
                    lastError = e
                } catch (e: MediaCodec.CodecException) {
                    lastError = e
                } catch (e: IllegalStateException) {
                    lastError = e
                } catch (e: java.io.IOException) {
                    lastError = e
                }
            }
            throw ScanFailure(noDecoder == trackCount, "no audio track could be read", lastError)
        }

        private const val TIMEOUT_US = 10_000L
        /** Under 30 s of sound there is nothing to match. */
        private const val MIN_FRAMES = 3_000

        /**
         * Order to try the audio tracks in: those whose language is [preferred] first, then the rest, each group
         * in file order. [languages] are the tracks' tags (null when unknown); the result holds indices into it.
         */
        fun orderTracks(languages: List<String?>, preferred: String?): List<Int> {
            val all = languages.indices.toList()
            if (preferred.isNullOrBlank()) return all
            val (match, rest) = all.partition { sameLanguage(languages[it], preferred) }
            return match + rest
        }

        fun encodingOf(androidEncoding: Int): PcmEncoding? = when (androidEncoding) {
            AudioFormat.ENCODING_PCM_16BIT -> PcmEncoding.PCM_16
            AudioFormat.ENCODING_PCM_FLOAT -> PcmEncoding.FLOAT
            AudioFormat.ENCODING_PCM_8BIT -> PcmEncoding.PCM_8
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> PcmEncoding.PCM_24
            AudioFormat.ENCODING_PCM_32BIT -> PcmEncoding.PCM_32
            else -> null
        }
    }
}
