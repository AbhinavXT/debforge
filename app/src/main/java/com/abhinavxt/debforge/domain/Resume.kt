package com.abhinavxt.debforge.domain

/**
 * Where to pick up a video, and when it counts as watched. Pure: unit-tested
 * in ResumeTest.
 */
object Resume {

    /** Less than this watched is a peek: start from the beginning, don't list it. */
    const val MIN_RESUME_MS = 30_000L

    /** Resuming steps back a little so the viewer finds the thread again. */
    const val REWIND_MS = 5_000L

    /** End credits: the last 5% or the last minute, whichever is longer. */
    fun isFinished(positionMs: Long, durationMs: Long): Boolean =
        durationMs > 0 && durationMs - positionMs <= maxOf(60_000L, durationMs / 20)

    /** Position to start at; 0 = from the beginning. */
    fun resumeAt(positionMs: Long, durationMs: Long, finished: Boolean): Long = when {
        finished || positionMs < MIN_RESUME_MS || isFinished(positionMs, durationMs) -> 0L
        else -> (positionMs - REWIND_MS).coerceAtLeast(0L)
    }

    /** 0..1, or null when the length is unknown. */
    fun progress(positionMs: Long, durationMs: Long): Float? =
        if (durationMs <= 0) null else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)

    /** What "Continue watching" needs from a saved position. */
    data class Entry(val id: String, val positionMs: Long, val durationMs: Long, val finished: Boolean, val updatedAt: Long)

    /** Started, not finished, most recent first. */
    fun <T> continueWatching(items: List<T>, entry: (T) -> Entry, limit: Int = 12): List<T> =
        items.filter {
            val e = entry(it)
            !e.finished && e.positionMs >= MIN_RESUME_MS && !isFinished(e.positionMs, e.durationMs)
        }
            .sortedByDescending { entry(it).updatedAt }
            .take(limit)
}
