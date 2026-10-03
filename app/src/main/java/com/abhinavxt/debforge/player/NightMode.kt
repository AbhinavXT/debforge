package com.abhinavxt.debforge.player

import android.media.audiofx.DynamicsProcessing

/**
 * Night mode: evens out loud and quiet. A compressor brings explosions and
 * music down, make-up gain lifts dialogue, and a limiter catches peaks, so
 * the volume can stay low without missing what people say. Android's
 * DynamicsProcessing effect on the player's audio session (API 28+).
 */
class NightMode private constructor(private val effect: DynamicsProcessing) {

    fun release() = runCatching { effect.release() }

    companion object {
        /** Settings for every channel; surround downmixes and stereo alike. */
        private const val CHANNELS = 8

        /** Null if the device has no DynamicsProcessing for this session. */
        fun attach(sessionId: Int): NightMode? = runCatching {
            val config = DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                CHANNELS,
                /* preEqInUse = */ false, 0,
                /* mbcInUse = */ true, 1,
                /* postEqInUse = */ false, 0,
                /* limiterInUse = */ true
            ).build()
            val band = DynamicsProcessing.MbcBand(
                /* enabled = */ true,
                /* cutoffFrequency = */ 20_000f,
                /* attackTime ms = */ 3f,
                /* releaseTime ms = */ 120f,
                /* ratio = */ 4f,
                /* threshold dB = */ -30f,
                /* kneeWidth dB = */ 10f,
                /* noiseGateThreshold dB = */ -90f,
                /* expanderRatio = */ 1f,
                /* preGain dB = */ 0f,
                /* postGain dB = */ 9f
            )
            val mbc = DynamicsProcessing.Mbc(true, true, 1).apply { setBand(0, band) }
            config.setMbcAllChannelsTo(mbc)
            config.setLimiterAllChannelsTo(
                DynamicsProcessing.Limiter(
                    /* inUse = */ true, /* enabled = */ true, /* linkGroup = */ 0,
                    /* attackTime ms = */ 1f, /* releaseTime ms = */ 60f,
                    /* ratio = */ 10f, /* threshold dB = */ -2f, /* postGain dB = */ 0f
                )
            )
            NightMode(DynamicsProcessing(0, sessionId, config).apply { enabled = true })
        }.getOrNull()
    }
}
