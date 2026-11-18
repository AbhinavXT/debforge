package com.abhinavxt.debforge.player

/**
 * A service started with startForegroundService() must call startForeground() before it is stopped, or Android
 * kills the app. This tracks that window: while any foreground start is still owed its startForeground() a stop is
 * only recorded, and the service carries it out right after its startForeground() ([onForeground]).
 *
 * Starts are counted, one per startForegroundService() and one [onForeground] per resulting onStartCommand, so
 * a second start made while the first is being answered keeps later stops deferred. Nothing is cleared when a
 * service instance ends: every start it was owed has already been answered by then.
 */
class ForegroundStartGate {
    private var pending = 0
    private var stopRequested = false

    /** Just before startForegroundService(): the service will owe a startForeground(). */
    @Synchronized fun foregroundStartRequested() { pending++; stopRequested = false }

    /** startForegroundService() threw: nothing is owed for it. */
    @Synchronized fun foregroundStartFailed() { if (pending > 0) pending-- }

    /** A plain start went through: it wants the service running, so an earlier deferred stop no longer holds. */
    @Synchronized fun plainStartRequested() { stopRequested = false }

    /** True when the service may be stopped now; otherwise the stop waits for [onForeground]. */
    @Synchronized fun requestStop(): Boolean {
        if (pending == 0) return true
        stopRequested = true
        return false
    }

    /** The service called startForeground() for one start; true when it should stop now (nothing else is owed). */
    @Synchronized fun onForeground(): Boolean {
        if (pending > 0) pending--
        if (pending > 0 || !stopRequested) return false
        stopRequested = false
        return true
    }
}
