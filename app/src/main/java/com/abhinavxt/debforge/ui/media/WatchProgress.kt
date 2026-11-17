package com.abhinavxt.debforge.ui.media

import androidx.compose.runtime.Immutable
import com.abhinavxt.debforge.data.playback.PlaybackEntity
import com.abhinavxt.debforge.domain.Resume

/** What a video tile shows about the viewer's progress. */
@Immutable
sealed interface WatchProgress {
    /** Never opened: no saved position at all. */
    @Immutable
    data object Unplayed : WatchProgress

    /** Partly watched; [fraction] is 0..1. */
    @Immutable
    data class InProgress(val fraction: Float) : WatchProgress {
        val percent: Int get() = (fraction * 100).toInt().coerceIn(1, 99)
    }

    /** Watched to the end, or only a peek (too little to count): no marker. */
    @Immutable
    data object Finished : WatchProgress
}

/**
 * Pure decision for a tile. Uses [Resume]'s own rules: under MIN_RESUME_MS is
 * a peek (shows nothing, but is not "unplayed" either), and the end credits
 * count as finished. Unknown length cannot show a fraction, so it shows nothing.
 */
internal fun watchProgress(saved: PlaybackEntity?): WatchProgress {
    if (saved == null) return WatchProgress.Unplayed
    if (saved.finished || Resume.isFinished(saved.positionMs, saved.durationMs)) return WatchProgress.Finished
    if (saved.positionMs < Resume.MIN_RESUME_MS) return WatchProgress.Finished
    val fraction = Resume.progress(saved.positionMs, saved.durationMs) ?: return WatchProgress.Finished
    return WatchProgress.InProgress(fraction)
}

/**
 * What a tile actually draws. Nothing until the positions have loaded (no flash
 * of "unplayed"), and no "unplayed" dot when an external player is used, since
 * only the in-app player writes positions.
 */
internal fun tileProgress(loaded: Boolean, internalPlayer: Boolean, saved: PlaybackEntity?): WatchProgress {
    if (!loaded) return WatchProgress.Finished
    val p = watchProgress(saved)
    return if (p == WatchProgress.Unplayed && !internalPlayer) WatchProgress.Finished else p
}
