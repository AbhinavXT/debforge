package com.abhinavxt.debforge.player

/**
 * A service started with startForegroundService() must call startForeground() before it is stopped, or Android
 * kills the app. This tracks that window: while the foreground start is pending a stop is only recorded, and the
 * service carries it out right after its startForeground() ([onForeground]).
 */
class ForegroundStartGate {
    private var pending = false
    private var stopRequested = false

    /** startForegroundService() went through: the service owes a startForeground(). */
    @Synchronized fun foregroundStartRequested() { pending = true; stopRequested = false }

    /** A plain start went through: it wants the service running, so an earlier deferred stop no longer holds. */
    @Synchronized fun plainStartRequested() { stopRequested = false }

    /** True when the service may be stopped now; otherwise the stop waits for [onForeground]. */
    @Synchronized fun requestStop(): Boolean {
        if (!pending) return true
        stopRequested = true
        return false
    }

    /** The service called startForeground(); true when it should stop again at once. */
    @Synchronized fun onForeground(): Boolean {
        pending = false
        return stopRequested.also { stopRequested = false }
    }

    /** The service is gone: nothing is owed any more. */
    @Synchronized fun reset() { pending = false; stopRequested = false }
}
