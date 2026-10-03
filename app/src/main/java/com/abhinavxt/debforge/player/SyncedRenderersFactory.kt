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

/** Subtitle and audio sync set in the player (milliseconds; + = later). Read on the playback thread. */
class AvSync {
    @Volatile var subtitleDelayMs = 0L
    @Volatile var audioDelayMs = 0L
}

/**
 * Media3 has no subtitle or audio delay, so the renderers are handed a
 * shifted clock:
 *  - subtitles [AvSync.subtitleDelayMs] later: the text renderer is told
 *    it's that much earlier than it is;
 *  - audio [AvSync.audioDelayMs] later: the same as the picture that much
 *    earlier, so the video renderer is told it's later than it is. The
 *    audio renderer drives the playback clock and is left alone.
 * A change takes effect from the next frame / subtitle.
 */
@OptIn(UnstableApi::class)
class SyncedRenderersFactory(context: Context, private val sync: AvSync) : DefaultRenderersFactory(context) {

    override fun buildTextRenderers(
        context: Context,
        output: TextOutput,
        outputLooper: Looper,
        extensionRendererMode: Int,
        out: ArrayList<Renderer>
    ) {
        val from = out.size
        super.buildTextRenderers(context, output, outputLooper, extensionRendererMode, out)
        shift(out, from) { sync.subtitleDelayMs * 1000 }
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
        shift(out, from) { -sync.audioDelayMs * 1000 }
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
