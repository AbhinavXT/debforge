package com.abhinavxt.debforge.ui.media

import com.abhinavxt.debforge.data.playback.PlaybackEntity
import com.abhinavxt.debforge.domain.Resume

/** What a video tile shows about the viewer's progress. */
sealed interface WatchProgress {
    /** Never opened: no saved position at all. */
    data object Unplayed : WatchProgress

    /** Partly watched; [fraction] is 0..1. */
    data class InProgress(val fraction: Float) : WatchProgress {
        val percent: Int get() = (fraction * 100).toInt().coerceIn(1, 99)
    }

    /** Watched to the end, or only a peek (too little to count): no marker. */
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
