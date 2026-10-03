package com.abhinavxt.debforge.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ForwardingRenderer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.video.VideoRendererEventListener

/** Player settings the renderers read on the playback thread. */
class RendererTuning {
    /** Subtitle and audio sync (milliseconds; + = later). */
    @Volatile var subtitleDelayMs = 0L
    @Volatile var audioDelayMs = 0L
    /** Video on Android's software decoders first (takes effect when a decoder starts). */
    @Volatile var preferSoftwareVideo = false
}

/**
 * Renderers with two additions. Software video decoding on request (see
 * [RendererTuning.preferSoftwareVideo]), and sync: Media3 has no subtitle
 * or audio delay, so the renderers are handed a shifted clock:
 *  - subtitles [RendererTuning.subtitleDelayMs] later: the text renderer is
 *    told it's that much earlier than it is;
 *  - audio [RendererTuning.audioDelayMs] later: the same as the picture that much
 *    earlier, so the video renderer is told it's later than it is. The
 *    audio renderer drives the playback clock and is left alone.
 * A change takes effect from the next frame / subtitle.
 */
@OptIn(UnstableApi::class)
class SyncedRenderersFactory(context: Context, private val tuning: RendererTuning) : DefaultRenderersFactory(context) {

    init {
        // "Software decoding" (More): the device's own CPU decoders before the
        // hardware ones, for files a chipset decodes badly. Audio is unaffected.
        setMediaCodecSelector { mimeType, secure, tunneling ->
            val all = MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, secure, tunneling)
            if (tuning.preferSoftwareVideo && mimeType.startsWith("video/")) all.sortedByDescending { it.softwareOnly } else all
        }
    }

    override fun buildTextRenderers(
        context: Context,
        output: TextOutput,
        outputLooper: Looper,
        extensionRendererMode: Int,
        out: ArrayList<Renderer>
    ) {
        val from = out.size
        super.buildTextRenderers(context, output, outputLooper, extensionRendererMode, out)
        shift(out, from) { tuning.subtitleDelayMs * 1000 }
    }

    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>
    ) {
        val from = out.size
        super.buildVideoRenderers(
            context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback,
            eventHandler, eventListener, allowedVideoJoiningTimeMs, out
        )
        shift(out, from) { -tuning.audioDelayMs * 1000 }
    }

    private fun shift(out: ArrayList<Renderer>, from: Int, delayUs: () -> Long) {
        for (i in from until out.size) out[i] = Shifted(out[i], delayUs)
    }

    /** Renders as if the clock read [delayUs] earlier. */
    private class Shifted(delegate: Renderer, private val delayUs: () -> Long) : ForwardingRenderer(delegate) {
        override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
            super.render(positionUs - delayUs(), elapsedRealtimeUs)
        }
    }
}
