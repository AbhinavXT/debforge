package com.abhinavxt.debforge.data.net

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.EventListener
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Makes blocking OkHttp code (`newCall(...).execute()` and body reads, as in
 * link resolution and the YouTube extractor) stop when its coroutine is
 * cancelled. `withContext(Dispatchers.IO)` alone can't: the blocked socket
 * read ignores coroutine cancellation and thread interrupts, so a pause or a
 * system stop waited out the read timeout on a stalled connection.
 *
 * Clients carry [eventListenerFactory]; inside [run], every call started by the
 * running thread is tracked and cancelled the moment the coroutine is.
 */
object BlockingCalls {
    private class Scope {
        val calls = ConcurrentHashMap.newKeySet<Call>()
        val cancelled = AtomicBoolean(false)
    }

    private val scopes = ConcurrentHashMap<Thread, Scope>()

    val eventListenerFactory = EventListener.Factory { call ->
        // Only calls started on a thread inside run() are tracked.
        val scope = scopes[Thread.currentThread()]
        if (scope == null) EventListener.NONE else Tracker(scope)
    }

    private class Tracker(private val scope: Scope) : EventListener() {
        override fun callStart(call: Call) {
            scope.calls.add(call)
            if (scope.cancelled.get()) call.cancel()
        }

        override fun callEnd(call: Call) { scope.calls.remove(call) }
        override fun callFailed(call: Call, ioe: IOException) { scope.calls.remove(call) }
        override fun canceled(call: Call) { scope.calls.remove(call) }
    }

    /** Runs [block] on the IO dispatcher; cancelling the coroutine cancels every OkHttp call it has in flight. */
    suspend fun <T> run(block: () -> T): T = withContext(Dispatchers.IO) {
        val thread = Thread.currentThread()
        val scope = Scope()
        scopes[thread] = scope
        val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                scope.cancelled.set(true)
                scope.calls.toList().forEach { it.cancel() }
            }
        }
        try {
            block()
        } finally {
            // Completed or cancelled: nothing more to track.
            scopes.remove(thread, scope)
            scope.calls.clear()
            watcher.cancel()
        }
    }
}
