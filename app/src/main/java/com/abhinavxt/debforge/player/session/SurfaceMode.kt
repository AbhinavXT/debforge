package com.abhinavxt.debforge.player.session

/** Where a [VideoSurface] is shown: the whole screen, the watch page's video area, or the small mini player. */
enum class SurfaceMode { FULL, INLINE, MINI }

/** What a [VideoSurface] does in a [SurfaceMode]. */
data class SurfaceFeatures(
    /** Swipe up / down: brightness (left half) and volume (right half). */
    val levels: Boolean,
    /** Swipe sideways: scrub. */
    val scrub: Boolean,
    /** Hold: fast playback. */
    val holdSpeed: Boolean,
    /** Pinch: zoom the picture. */
    val pinchZoom: Boolean,
    /** Double tap the sides: seek; double tap the middle: play / pause. */
    val doubleTaps: Boolean,
    /** A tap shows / hides the controls. */
    val tapToggle: Boolean,
    /** The controls and gesture feedback over the video. */
    val overlays: Boolean,
    /** "Start over", skip, next-episode cards and stats. */
    val cards: Boolean,
    /** A downward drag is the host's (drag-follow minimise), not brightness / volume; taps and double taps still work. */
    val hostDrag: Boolean = false
) {
    companion object {
        /** Pure: the same answer for the same mode. */
        fun of(mode: SurfaceMode): SurfaceFeatures = when (mode) {
            SurfaceMode.FULL -> SurfaceFeatures(
                levels = true, scrub = true, holdSpeed = true, pinchZoom = true,
                doubleTaps = true, tapToggle = true, overlays = true, cards = true
            )
            // The watch page scrolls and swipes down to minimise: no brightness / volume / scrub / hold / pinch.
            SurfaceMode.INLINE -> SurfaceFeatures(
                levels = false, scrub = false, holdSpeed = false, pinchZoom = false,
                doubleTaps = true, tapToggle = true, overlays = true, cards = true, hostDrag = true
            )
            SurfaceMode.MINI -> SurfaceFeatures(
                levels = false, scrub = false, holdSpeed = false, pinchZoom = false,
                doubleTaps = false, tapToggle = false, overlays = false, cards = false
            )
        }
    }
}
