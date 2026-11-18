package com.abhinavxt.debforge.data.net

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BlockingCallsTest {
    private val accepted = CopyOnWriteArrayList<Socket>()
    private val acceptedLatch = CountDownLatch(1)

    /**
     * A server that accepts and never answers: a stalled connection. Accepted
     * sockets are kept referenced: a garbage-collected socket is closed, which
     * the client would see as end-of-stream instead of a stall.
     */
    private fun stalledServer(): ServerSocket = ServerSocket(0).also { server ->
        Thread {
            runCatching { while (true) { accepted += server.accept(); acceptedLatch.countDown() } }
        }.apply { isDaemon = true }.start()
    }

    private val client = OkHttpClient.Builder()
        .readTimeout(60, TimeUnit.SECONDS)
        .eventListenerFactory(BlockingCalls.eventListenerFactory)
        .build()

    @Test fun cancelStopsBlockedRead() = runBlocking {
        val server = stalledServer()
        try {
            val started = CompletableDeferred<Unit>()
            var failure: Throwable? = null
            val job = async(Dispatchers.Default) {
                runCatching { BlockingCalls.run {
                    started.complete(Unit)
                    client.newCall(Request.Builder().url("http://127.0.0.1:${server.localPort}/").build()).execute().close()
                } }.also { failure = it.exceptionOrNull() }
            }
            started.await()
            assertTrue("server never accepted", acceptedLatch.await(10, TimeUnit.SECONDS))
            Thread.sleep(300) // let the read block
            val t0 = System.nanoTime()
            withTimeout(5_000) { job.cancelAndJoin() }
            val ms = (System.nanoTime() - t0) / 1_000_000
            assertTrue("cancel took $ms ms", ms < 3_000)
            assertTrue("expected CancellationException, got $failure", failure is kotlinx.coroutines.CancellationException)
        } finally {
            server.close()
            accepted.forEach { runCatching { it.close() } }
        }
    }

    @Test fun returnsResultWhenNotCancelled() = runBlocking {
        assertEquals(42, BlockingCalls.run { 42 })
    }
}
