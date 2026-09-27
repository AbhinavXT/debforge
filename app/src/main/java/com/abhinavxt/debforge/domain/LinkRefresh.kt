package com.abhinavxt.debforge.domain

/**
 * When the player may quietly fetch a fresh link after a network error.
 * Debrid links expire (hours after unrestricting, or when paused overnight),
 * and a fresh one from the same file usually fixes playback at once. A real
 * outage fails again straight away, so attempts are capped per minute; after
 * that the user gets the Retry dialog. Pure: unit-tested in LinkRefreshTest.
 */
object LinkRefresh {

    const val MAX_ATTEMPTS = 2
    const val WINDOW_MS = 60_000L

    data class State(val attempts: Int = 0, val windowStart: Long = 0L)

    /** The state after one more attempt at [now], or null when it's time to give up. */
    fun next(state: State, now: Long): State? {
        val fresh = now - state.windowStart >= WINDOW_MS
        val current = if (fresh) State(0, now) else state
        return if (current.attempts >= MAX_ATTEMPTS) null else current.copy(attempts = current.attempts + 1)
    }
}
