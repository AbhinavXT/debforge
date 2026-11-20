package com.abhinavxt.debforge.player.session

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import com.abhinavxt.debforge.R
import com.abhinavxt.debforge.player.NightMode
import com.abhinavxt.debforge.player.PlaybackStats
import com.abhinavxt.debforge.player.gestures.GestureMath

/** Volume boost and night mode on [VideoSession]'s audio session. */
@OptIn(UnstableApi::class)
internal class VideoEffects(private val s: VideoSession) {

    /** Volume past the phone's maximum (1 = none, up to [GestureMath.MAX_VOLUME_LEVEL]). */
    var boostLevel = 1f
        private set
    private var loudness: android.media.audiofx.LoudnessEnhancer? = null
    private var nightMode: NightMode? = null
    private var audioSessionId = C.AUDIO_SESSION_ID_UNSET

    /** The volume gesture can go past 100 % (the loudness enhancer is there). */
    val canBoost get() = loudness != null

    /** Volume boost past the phone's maximum, on the player's audio session. */
    fun attach(sessionId: Int) {
        audioSessionId = sessionId
        loudness?.release()
        loudness = null
        applyNightMode()
        if (sessionId == C.AUDIO_SESSION_ID_UNSET) return
        loudness = runCatching { android.media.audiofx.LoudnessEnhancer(sessionId) }.getOrNull()
        applyBoost()
    }

    /** The player is released: let go of the effects (the boost level stays for the next player). */
    fun release() {
        loudness?.release()
        loudness = null
        nightMode?.release()
        nightMode = null
        audioSessionId = C.AUDIO_SESSION_ID_UNSET
    }

    fun setBoost(level: Float) {
        if (level == boostLevel) return
        boostLevel = level
        applyBoost()
    }

    /** Night mode on the current audio session (re-attached when the session changes). */
    fun applyNightMode() {
        nightMode?.release()
        nightMode = null
        if (!s.controls.nightMode || audioSessionId == C.AUDIO_SESSION_ID_UNSET) return
        nightMode = NightMode.attach(audioSessionId)
        if (nightMode == null) {
            s.controls.nightMode = false
            s.showMessage(s.string(R.string.player_night_mode_unavailable))
        }
    }

    private fun applyBoost() {
        val gain = GestureMath.boostGainMb(boostLevel)
        loudness?.let { l ->
            runCatching {
                l.setTargetGain(gain)
                l.enabled = gain > 0
            }
        }
    }
}

/** Stats for nerds: decoders, dropped frames and bandwidth from the player, as text for the host. */
@OptIn(UnstableApi::class)
internal class VideoStats(private val s: VideoSession) {

    private var videoDecoder: String? = null
    private var audioDecoder: String? = null
    var droppedFrames = 0
    private var bandwidthBps = 0L

    fun update() {
        val p = s.player ?: return
        fun hdr(f: androidx.media3.common.Format): String? = when {
            f.sampleMimeType == androidx.media3.common.MimeTypes.VIDEO_DOLBY_VISION -> "Dolby Vision"
            f.colorInfo?.colorTransfer == C.COLOR_TRANSFER_ST2084 -> "HDR10"
            f.colorInfo?.colorTransfer == C.COLOR_TRANSFER_HLG -> "HLG"
            else -> null
        }
        val v = p.videoFormat
        val a = p.audioFormat
        s.host?.statsText(
            PlaybackStats.format(
                PlaybackStats.Snapshot(
                    video = v?.let {
                        PlaybackStats.Video(it.sampleMimeType, it.codecs, it.width, it.height, it.frameRate, it.bitrate, hdr(it))
                    },
                    videoDecoder = videoDecoder,
                    audio = a?.let { PlaybackStats.Audio(it.sampleMimeType, it.channelCount, it.sampleRate, it.bitrate, it.language) },
                    audioDecoder = audioDecoder,
                    bufferAheadMs = p.bufferedPosition - p.currentPosition,
                    networkBps = bandwidthBps,
                    droppedFrames = droppedFrames,
                    subtitleDelayMs = s.tuning.subtitleDelayMs,
                    audioDelayMs = s.tuning.audioDelayMs
                )
            )
        )
    }

    /** Decoders and dropped frames for the stats overlay. */
    val analytics = object : androidx.media3.exoplayer.analytics.AnalyticsListener {
        override fun onVideoDecoderInitialized(
            eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
            decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long
        ) { videoDecoder = decoderName }

        override fun onAudioDecoderInitialized(
            eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
            decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long
        ) { audioDecoder = decoderName }

        override fun onDroppedVideoFrames(
            eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long
        ) { this@VideoStats.droppedFrames += droppedFrames }

        override fun onBandwidthEstimate(
            eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
            totalLoadTimeMs: Int, totalBytesLoaded: Long, bitrateEstimate: Long
        ) { bandwidthBps = bitrateEstimate }
    }
}
