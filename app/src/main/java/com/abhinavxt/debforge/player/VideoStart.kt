package com.abhinavxt.debforge.player

import android.content.Intent

/** Where and how fast the video player starts when the audio player hands an item over (see [VideoStartExtras]). */
data class VideoStart(val positionMs: Long, val speed: Float = 1f)

/** The intent extras that carry a [VideoStart] to PlayerActivity; build with `PlayerActivity.intent(context, request, start)`. */
object VideoStartExtras {
    const val EXTRA_START_MS = "startMs"
    const val EXTRA_SPEED = "speed"
    const val EXTRA_FROM_AUDIO = "fromAudio"

    fun put(intent: Intent, start: VideoStart): Intent = intent
        .putExtra(EXTRA_START_MS, start.positionMs)
        .putExtra(EXTRA_SPEED, start.speed)
        .putExtra(EXTRA_FROM_AUDIO, true)

    /** The start carried by [intent], or null when it has none (then the saved position applies). Bad values are clamped. */
    fun from(intent: Intent): VideoStart? {
        if (!intent.hasExtra(EXTRA_START_MS)) return null
        val speed = intent.getFloatExtra(EXTRA_SPEED, 1f).takeIf { it.isFinite() && it in 0.25f..4f } ?: 1f
        return VideoStart(intent.getLongExtra(EXTRA_START_MS, 0L).coerceAtLeast(0L), speed)
    }
}

/** Whether the saved position is looked up: not when the audio player handed the item over at its own position. */
fun restoresSavedPosition(start: VideoStart?): Boolean = start == null

/** Whether the video player shows "Play as audio". */
fun playAsAudioVisible(live: Boolean, durationMs: Long, hasAudioTrack: Boolean, casting: Boolean, debridStream: Boolean): Boolean =
    !live && durationMs > 0 && hasAudioTrack && !casting && !debridStream
