package com.abhinavxt.debforge.player.youtube

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleExtractor
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.abhinavxt.debforge.data.youtube.NewPipeDownloader
import com.abhinavxt.debforge.domain.Container
import com.abhinavxt.debforge.domain.DefaultQuality
import com.abhinavxt.debforge.domain.FormatChoice
import com.abhinavxt.debforge.domain.YouTubeLinks
import com.abhinavxt.debforge.domain.pickDefaultChoice

/** Marks a [MediaItem] as a YouTube stream. The item's uri is the video-only URL, or [audioUrl] when [hasVideo] is false. */
data class YouTubeStreamTag(val audioUrl: String, val hasVideo: Boolean = true)

/** One upstream request: bytes [start, endExclusive); endExclusive is C.LENGTH_UNSET never, a window always has an end. */
data class RangeWindow(val start: Long, val endExclusive: Long) {
    val length: Long get() = endExclusive - start
}

object YouTubeStreaming {
    /** Same size as the downloader's pieces: whole-file requests get throttled, ranged ones do not. */
    const val CHUNK = 4_000_000L

    /** The next request for a read at [position] with [length] bytes still wanted (C.LENGTH_UNSET = to the end). */
    fun rangeWindow(position: Long, length: Long, chunk: Long = CHUNK): RangeWindow {
        val size = if (length == C.LENGTH_UNSET.toLong()) chunk else minOf(chunk, length.coerceAtLeast(0L))
        return RangeWindow(position, position + size)
    }

    /** `ytstream:<id>` for video, `ytaudio:<id>` for audio only. */
    fun streamPositionKey(videoId: String, audioOnly: Boolean) =
        (if (audioOnly) "ytaudio:" else "ytstream:") + videoId

    /** What the Play button / row icon starts with; null when the setting is "ask every time". */
    fun quickPlayChoice(defaultQuality: DefaultQuality, choices: List<FormatChoice>): FormatChoice? =
        if (defaultQuality == DefaultQuality.ASK) null else pickDefaultChoice(choices, defaultQuality)

    /**
     * After re-resolving: the same itags when still offered, otherwise the tallest choice in the same mode
     * (video or audio only) at or below [oldHeight], otherwise null. Audio only keeps the same container if it can.
     */
    fun reselect(ref: YouTubeLinks.Ref, oldHeight: Int, choices: List<FormatChoice>): FormatChoice? {
        choices.firstOrNull { it.videoItag == ref.videoItag && it.audioItag == ref.audioItag }?.let { return it }
        if (ref.videoItag == null) {
            val audio = choices.filter { it.videoItag == null }
            return audio.firstOrNull { it.audioItag == ref.audioItag } ?: audio.firstOrNull { it.isDefault } ?: audio.firstOrNull()
        }
        return choices.filter { it.videoItag != null && it.height <= oldHeight }.maxByOrNull { it.height }
    }

    /**
     * After the device failed to decode the chosen video (usually VP9 or AV1): the tallest MP4 choice at or below
     * [oldHeight] that is not the one that failed, or null when there is none.
     */
    fun mp4Fallback(ref: YouTubeLinks.Ref, oldHeight: Int, choices: List<FormatChoice>): FormatChoice? =
        choices.filter { it.videoItag != null && it.videoItag != ref.videoItag && it.container == Container.MP4 && it.height <= oldHeight }
            .maxByOrNull { it.height }
}

/**
 * Reads [upstream] one byte window at a time but looks like one continuous stream to the player: when a window
 * ends the next one is opened, and the spec's position and length are honoured.
 */
@OptIn(UnstableApi::class)
class WindowedDataSource(
    private val upstream: HttpDataSource,
    private val chunk: Long = YouTubeStreaming.CHUNK,
) : DataSource {
    private var spec: DataSpec? = null
    private var position = 0L
    private var remaining = C.LENGTH_UNSET.toLong() // bytes the caller still wants; UNSET = to the end
    private var total = C.LENGTH_UNSET.toLong() // whole file size once the server told us
    private var windowLeft = 0L
    private var upstreamOpen = false
    private var finished = false
    private var whole = false // the server ignored ranges: one open request carries the whole remaining body

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        spec = dataSpec
        position = dataSpec.position
        remaining = dataSpec.length
        total = C.LENGTH_UNSET.toLong()
        finished = false
        whole = false
        if (!openWindow()) throw java.io.IOException("YouTube returned nothing")
        return when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
            total != C.LENGTH_UNSET.toLong() -> (total - dataSpec.position).coerceAtLeast(0L)
            else -> C.LENGTH_UNSET.toLong()
        }
    }

    /** Opens the window at [position]; false when there is nothing left to read. */
    private fun openWindow(): Boolean {
        if (remaining == 0L || (total != C.LENGTH_UNSET.toLong() && position >= total)) return false
        val w = YouTubeStreaming.rangeWindow(position, remaining, chunk)
        var opened = try {
            upstream.open(spec!!.buildUpon().setPosition(w.start).setLength(w.length).build())
        } catch (e: HttpDataSource.InvalidResponseCodeException) {
            // Asking for a window past the end of the file.
            if (e.responseCode == 416 && position > 0) return false
            throw e
        }
        upstreamOpen = true
        val fromHeaders = totalFromHeaders()
        if (fromHeaders == C.LENGTH_UNSET.toLong() && !hasContentRange()) {
            // A 200 without Content-Range: the range was ignored and the window above was cut short. Read the
            // rest of the body from one request instead of treating the cut as the end of the file.
            upstream.close(); upstreamOpen = false
            opened = upstream.open(spec!!.buildUpon().setPosition(position).setLength(remaining).build())
            upstreamOpen = true
            whole = true
            windowLeft = if (opened != C.LENGTH_UNSET.toLong()) opened else Long.MAX_VALUE
            if (total == C.LENGTH_UNSET.toLong() && opened != C.LENGTH_UNSET.toLong()) total = position + opened
            return true
        }
        windowLeft = if (opened != C.LENGTH_UNSET.toLong()) opened else w.length
        if (total == C.LENGTH_UNSET.toLong()) total = fromHeaders
        return true
    }

    private fun contentRange(): String? =
        upstream.responseHeaders.entries.firstOrNull { it.key.equals("Content-Range", true) }?.value?.firstOrNull()

    private fun hasContentRange() = contentRange() != null

    private fun totalFromHeaders(): Long {
        val range = contentRange()
        return range?.substringAfterLast('/')?.toLongOrNull() ?: C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        while (true) {
            if (finished) return C.RESULT_END_OF_INPUT
            if (!upstreamOpen) {
                if (!openWindow()) { finished = true; return C.RESULT_END_OF_INPUT }
            }
            val n = upstream.read(buffer, offset, length)
            if (n == C.RESULT_END_OF_INPUT || windowLeft <= 0L) {
                upstream.close(); upstreamOpen = false
                if (whole) { finished = true; return C.RESULT_END_OF_INPUT }
                // A short window with the file's end reached means we are done.
                if (n == C.RESULT_END_OF_INPUT && windowLeft > 0L && total == C.LENGTH_UNSET.toLong()) { finished = true; return C.RESULT_END_OF_INPUT }
                continue
            }
            position += n
            windowLeft -= n
            if (remaining != C.LENGTH_UNSET.toLong()) remaining -= n
            if (windowLeft <= 0L) {
                upstream.close(); upstreamOpen = false
                if (whole) finished = true
            }
            return n
        }
    }

    override fun getUri(): Uri? = if (upstreamOpen) upstream.uri else spec?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = if (upstreamOpen) upstream.responseHeaders else emptyMap()

    override fun close() {
        spec = null
        try { upstream.close() } finally { upstreamOpen = false }
    }

    class Factory(private val chunk: Long = YouTubeStreaming.CHUNK) : DataSource.Factory {
        override fun createDataSource(): DataSource = WindowedDataSource(
            DefaultHttpDataSource.Factory()
                .setUserAgent(NewPipeDownloader.USER_AGENT)
                .setConnectTimeoutMs(15_000)
                .setReadTimeoutMs(15_000)
                .setAllowCrossProtocolRedirects(true)
                .createDataSource(),
            chunk
        )
    }
}

/**
 * Builds a video-only + audio merge for items tagged with [YouTubeStreamTag] (just the audio for audio-only
 * ones); everything else goes to the default factory.
 */
@OptIn(UnstableApi::class)
class YouTubeMediaSourceFactory(context: Context, chunk: Long = YouTubeStreaming.CHUNK) : MediaSource.Factory {
    private val dataSources = WindowedDataSource.Factory(chunk)
    private val progressive = ProgressiveMediaSource.Factory(dataSources)
    private val default = DefaultMediaSourceFactory(context)
    // Subtitles can be http links or local files, so they do not go through the windowed reader.
    private val subtitleData = DefaultDataSource.Factory(context)
    private val subtitleParsers = DefaultSubtitleParserFactory()

    /** A subtitle file parsed into cues while it is read, as the default factory does (raw text samples are refused). Null when the format can't be parsed. */
    private fun subtitleSource(config: MediaItem.SubtitleConfiguration): MediaSource? {
        val format = Format.Builder()
            .setId(config.id)
            .setSampleMimeType(config.mimeType)
            .setLanguage(config.language)
            .setSelectionFlags(config.selectionFlags)
            .setRoleFlags(config.roleFlags)
            .setLabel(config.label)
            .build()
        if (!subtitleParsers.supportsFormat(format)) return null
        val extractors = ExtractorsFactory { arrayOf<Extractor>(SubtitleExtractor(subtitleParsers.create(format), format)) }
        return ProgressiveMediaSource.Factory(subtitleData, extractors).createMediaSource(MediaItem.fromUri(config.uri))
    }

    override fun setDrmSessionManagerProvider(p: DrmSessionManagerProvider): MediaSource.Factory {
        progressive.setDrmSessionManagerProvider(p); default.setDrmSessionManagerProvider(p); return this
    }

    override fun setLoadErrorHandlingPolicy(p: LoadErrorHandlingPolicy): MediaSource.Factory {
        progressive.setLoadErrorHandlingPolicy(p); default.setLoadErrorHandlingPolicy(p); return this
    }

    override fun getSupportedTypes(): IntArray = default.supportedTypes

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val tag = mediaItem.localConfiguration?.tag as? YouTubeStreamTag ?: return default.createMediaSource(mediaItem)
        val main = progressive.createMediaSource(MediaItem.fromUri(mediaItem.localConfiguration!!.uri))
        val sources = ArrayList<MediaSource>()
        sources += main
        if (tag.hasVideo) sources += progressive.createMediaSource(MediaItem.fromUri(tag.audioUrl))
        mediaItem.localConfiguration!!.subtitleConfigurations.forEach { subtitleSource(it)?.let { s -> sources += s } }
        return if (sources.size == 1) main else MergingMediaSource(*sources.toTypedArray())
    }
}
