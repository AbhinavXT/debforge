package com.abhinavxt.debforge.data.provider.offcloud

import com.abhinavxt.debforge.data.provider.JobFailedException
import com.abhinavxt.debforge.data.provider.ProviderException
import com.abhinavxt.debforge.domain.AddRequest
import com.abhinavxt.debforge.domain.TorrentHash
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class OffcloudProviderTest {

    private class FakeApi : OffcloudApi {
        var historyData: MutableList<OcRequest> = mutableListOf()
        val historyCalls = AtomicInteger()
        val exploreCalls = AtomicInteger()
        val inFlight = AtomicInteger()
        val maxInFlight = AtomicInteger()
        val exploreAttempts = java.util.concurrent.ConcurrentHashMap<String, AtomicInteger>()
        /** When set, every explore suspends on it after counting itself in flight. */
        var exploreGate: CompletableDeferred<Unit>? = null
        /** (id, attempt number from 1) -> body, or throw */
        var explore: (String, Int) -> String = { id, _ -> fileJson(id) }
        val added = mutableListOf<String>()
        var removeManyCalls = 0
        var removeOneCalls = 0
        var removeMany: () -> ResponseBody = { """{"success":true}""".toResponseBody() }
        var removeOne: () -> ResponseBody = { """{"success":true}""".toResponseBody() }
        var onRemoveManyHistoryDrop: String? = null
        /** When set, history() suspends on it after counting the call. */
        var historyGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        var historyResult: (() -> List<OcRequest>)? = null

        override suspend fun accountInfo() = OcAccount()
        override suspend fun validate(bearer: String) = OcAccount()

        override suspend fun history(): List<OcRequest> {
            historyCalls.incrementAndGet()
            val snapshot = historyResult?.invoke() ?: historyData.toList()
            historyGate?.await()
            return snapshot
        }

        override suspend fun explore(requestId: String, format: String): ResponseBody {
            exploreCalls.incrementAndGet()
            val attempt = exploreAttempts.getOrPut(requestId) { AtomicInteger() }.incrementAndGet()
            val now = inFlight.incrementAndGet()
            maxInFlight.updateAndGet { maxOf(it, now) }
            try {
                exploreGate?.await()
                return explore(requestId, attempt).toResponseBody()
            } finally {
                inFlight.decrementAndGet()
            }
        }

        override suspend fun addCloud(body: OcUrlBody): OcRequest {
            added += body.url
            return OcRequest(requestId = "new1", status = "created", fileName = "n")
        }

        override suspend fun removeMany(body: OcRemoveBody): ResponseBody {
            removeManyCalls++
            return removeMany()
        }

        override suspend fun removeOne(requestId: String): ResponseBody {
            removeOneCalls++
            return removeOne()
        }

        override suspend fun cache(body: OcCacheBody) = OcCache()
    }

    companion object {
        fun fileJson(id: String) =
            """{"files":[{"path":"/f$id.mkv","size":10,"url":"https://s.offcloud.com/cloud/download/$id/f$id.mkv"}]}"""

        fun folder(id: String, createdOn: String?, status: String = "downloaded") = OcRequest(
            requestId = id, fileName = "folder$id", status = status, isDirectory = true,
            server = "s", createdOn = createdOn
        )
    }

    @Volatile private var token: String? = "A"

    private fun provider(api: FakeApi) = OffcloudProvider(api) { token }

    private suspend fun waitUntil(what: String, cond: () -> Boolean) {
        try {
            withTimeout(5_000) { while (!cond()) delay(5) }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("timed out waiting for $what")
        }
    }

    // 1
    @Test fun exploreConcurrencyIsBounded() = runBlocking<Unit> {
        val api = FakeApi()
        val gate = CompletableDeferred<Unit>()
        api.exploreGate = gate
        api.historyData = (1..20).map { folder("r$it", "2026-01-01T00:00:%02dZ".format(it)) }.toMutableList()
        val job = async(Dispatchers.Default) { provider(api).listFiles(1, 50) }
        try {
            waitUntil("6 explores in flight") { api.inFlight.get() >= 6 }
            // no 7th may start while the 6 are blocked
            delay(300)
            assertEquals(6, api.inFlight.get())
        } finally {
            gate.complete(Unit)
        }
        val page = withTimeout(5_000) { job.await() }
        assertEquals(20, page.items.size)
        assertTrue("max in flight was ${api.maxInFlight.get()}", api.maxInFlight.get() <= 6)
    }

    // 2
    @Test fun failedExploreIsRetriedOnce() = runBlocking {
        val api = FakeApi()
        api.historyData = mutableListOf(folder("a", "2026-01-01T00:00:00Z"))
        api.explore = { id, attempt -> if (attempt == 1) throw IOException("boom") else fileJson(id) }
        val page = provider(api).listFiles(1, 50)
        assertEquals(1, page.items.size)
        assertTrue(page.items.single().filename.contains("fa"))
        assertEquals(2, api.exploreCalls.get())
    }

    // 3
    @Test fun allExploresFailingThrows() {
        val api = FakeApi()
        api.historyData = mutableListOf(
            folder("a", "2026-01-01T00:00:00Z"), folder("b", "2026-01-01T00:00:01Z")
        )
        api.explore = { _, _ -> throw IOException("down") }
        assertThrows(Exception::class.java) { runBlocking { provider(api).listFiles(1, 50) } }
    }

    @Test fun partialExploreFailureReturnsTheRest() = runBlocking {
        val api = FakeApi()
        api.historyData = mutableListOf(
            folder("a", "2026-01-01T00:00:00Z"), folder("b", "2026-01-01T00:00:01Z")
        )
        api.explore = { id, _ -> if (id == "a") throw IOException("down") else fileJson(id) }
        val page = provider(api).listFiles(1, 50)
        assertEquals(1, page.items.size)
        assertTrue(page.items.single().filename.contains("fb"))
    }

    // 4
    @Test fun sortedNewestFirstByRealTimeAndUndatedLast() = runBlocking {
        val api = FakeApi()
        api.historyData = mutableListOf(
            folder("old", "2026-01-01T00:00:00Z"),
            folder("nodate", null),
            // Same second; as strings "...05Z" > "...05.500Z" is false ('Z' > '.'), so a string sort gets this wrong.
            folder("plain", "2026-03-01T10:00:05Z"),
            folder("frac", "2026-03-01T10:00:05.500Z")
        )
        val names = provider(api).listFiles(1, 50).items.map { it.filename }
        fun idx(s: String) = names.indexOfFirst { it.contains(s) }
        assertEquals(4, names.size)
        assertTrue(idx("ffrac") < idx("fplain"))
        assertTrue(idx("fplain") < idx("fold"))
        assertEquals(3, idx("fnodate"))
    }

    // 5
    @Test fun downloadedFolderWithNoFilesThrowsPlainProviderException() {
        val api = FakeApi()
        api.historyData = mutableListOf(folder("a", "2026-01-01T00:00:00Z"))
        api.explore = { _, _ -> """{"files":[]}""" }
        val e = assertThrows(ProviderException::class.java) { runBlocking { provider(api).filesForJob("req:a") } }
        assertFalse(e is JobFailedException)
    }

    @Test fun errorAndCanceledJobsThrowJobFailed() {
        for (status in listOf("error", "canceled")) {
            val api = FakeApi()
            api.historyData = mutableListOf(folder("a", "2026-01-01T00:00:00Z", status))
            assertThrows(JobFailedException::class.java) { runBlocking { provider(api).filesForJob("req:a") } }
        }
    }

    @Test fun missingJobThrowsPlainProviderException() {
        val api = FakeApi()
        api.historyData = mutableListOf(folder("a", "2026-01-01T00:00:00Z"))
        val e = assertThrows(ProviderException::class.java) { runBlocking { provider(api).filesForJob("req:nope") } }
        assertFalse(e is JobFailedException)
    }

    @Test fun stillDownloadingJobIsNull() = runBlocking {
        val api = FakeApi()
        api.historyData = mutableListOf(folder("a", "2026-01-01T00:00:00Z", "downloading"))
        assertNull(provider(api).filesForJob("req:a"))
    }

    // 6
    @Test fun listProcessingIncludesFailedExcludesDownloaded() = runBlocking {
        val api = FakeApi()
        api.historyData = mutableListOf(
            folder("q", "2026-01-01T00:00:00Z", "queued"),
            folder("d", "2026-01-01T00:00:01Z", "downloading"),
            folder("e", "2026-01-01T00:00:02Z", "error"),
            folder("c", "2026-01-01T00:00:03Z", "canceled"),
            folder("ok", "2026-01-01T00:00:04Z", "downloaded")
        )
        val refs = provider(api).listProcessing().map { it.ref }.toSet()
        assertEquals(setOf("req:q", "req:d", "req:e", "req:c"), refs)
    }

    // 7
    @Test fun torrentFileBecomesMagnetWithHashAndTrackers() = runBlocking {
        val info = "d6:lengthi12345e4:name8:file.mkv12:piece lengthi16384e6:pieces20:AAAAAAAAAAAAAAAAAAAAe"
        val torrent = "d8:announce18:http://t1/announce13:announce-listll15:udp://t2:80/annee4:info${info}e"
        val bytes = torrent.toByteArray(Charsets.ISO_8859_1)
        val hash = TorrentHash.fromTorrentFile(bytes)!!
        val api = FakeApi()
        provider(api).add(AddRequest.TorrentFile("x.torrent", bytes))
        val url = api.added.single()
        assertTrue(url, url.startsWith("magnet:?"))
        assertTrue(url, url.contains(hash, ignoreCase = true))
        val trs = Regex("[?&]tr=([^&]+)").findAll(url).map {
            java.net.URLDecoder.decode(it.groupValues[1], "UTF-8")
        }.toList()
        assertEquals(setOf("http://t1/announce", "udp://t2:80/ann"), trs.toSet())
    }

    // 8
    @Test fun removeSuccessSkipsGet() = runBlocking {
        val api = FakeApi()
        provider(api).remove("req:a")
        assertEquals(0, api.removeOneCalls)
    }

    @Test fun removeErrorBodyFallsBackToGet() = runBlocking {
        val api = FakeApi()
        api.removeMany = { """{"error":"x"}""".toResponseBody() }
        provider(api).remove("req:a")
        assertEquals(1, api.removeOneCalls)
    }

    @Test fun removeIoExceptionFallsBackToGet() = runBlocking {
        val api = FakeApi()
        api.removeMany = { throw IOException("net") }
        provider(api).remove("req:a")
        assertEquals(1, api.removeOneCalls)
    }

    @Test fun removeNonJsonAndStillInHistoryThrows() {
        val api = FakeApi()
        api.historyData = mutableListOf(folder("a", "2026-01-01T00:00:00Z"))
        api.removeMany = { "<html>".toResponseBody() }
        api.removeOne = { "<html>".toResponseBody() }
        assertThrows(Exception::class.java) { runBlocking { provider(api).remove("req:a") } }
    }

    @Test fun removeNonJsonAndGoneFromHistorySucceeds() = runBlocking {
        val api = FakeApi()
        api.historyData = mutableListOf()
        api.removeMany = { "<html>".toResponseBody() }
        api.removeOne = { "<html>".toResponseBody() }
        provider(api).remove("req:a")
        assertEquals(1, api.removeOneCalls)
        assertEquals(1, api.historyCalls.get())
    }

    // 9
    @Test fun historyIsCachedBetweenCalls() = runBlocking {
        val api = FakeApi()
        api.historyData = mutableListOf(folder("a", "2026-01-01T00:00:00Z", "downloading"))
        val p = provider(api)
        p.filesForJob("req:a")
        p.filesForJob("req:a")
        assertEquals(1, api.historyCalls.get())
    }

    @Test fun firstPageListFilesAlwaysFetchesFresh() = runBlocking {
        val api = FakeApi()
        api.historyData = mutableListOf(folder("a", "2026-01-01T00:00:00Z", "downloading"))
        val p = provider(api)
        p.filesForJob("req:a")
        p.listFiles(1, 50)
        assertEquals(2, api.historyCalls.get())
        p.listFiles(1, 50)
        assertEquals(3, api.historyCalls.get())
    }

    @Test fun historyFetchedFreshAfterRemove() = runBlocking {
        val api = FakeApi()
        api.historyData = mutableListOf(folder("a", "2026-01-01T00:00:00Z", "downloading"))
        val p = provider(api)
        p.filesForJob("req:a")
        val before = api.historyCalls.get()
        p.remove("req:a")
        api.historyData = mutableListOf()
        val e = runCatching { p.filesForJob("req:a") }.exceptionOrNull()
        assertTrue(api.historyCalls.get() > before)
        assertTrue(e is ProviderException)
    }

    // task 11 (b)
    @Test fun historyFetchedAgainAfterAdd() = runBlocking {
        val api = FakeApi()
        api.historyData = mutableListOf(folder("a", "2026-01-01T00:00:00Z", "downloading"))
        val p = provider(api)
        p.listProcessing()
        p.add(AddRequest.Magnet("magnet:?xt=urn:btih:abc"))
        val before = api.historyCalls.get()
        p.listProcessing()
        assertEquals(before + 1, api.historyCalls.get())
    }

    @Test fun historyFetchedAgainAfterRemove() = runBlocking {
        val api = FakeApi()
        api.historyData = mutableListOf(folder("a", "2026-01-01T00:00:00Z", "downloading"))
        val p = provider(api)
        p.listProcessing()
        p.remove("req:a")
        val before = api.historyCalls.get()
        p.listProcessing()
        assertEquals(before + 1, api.historyCalls.get())
    }

    @Test fun fetchInFlightDuringRemoveDoesNotRepopulateCache() = runBlocking {
        val api = FakeApi()
        val stale = listOf(folder("a", "2026-01-01T00:00:00Z", "downloading"))
        api.historyData = stale.toMutableList()
        api.historyGate = CompletableDeferred()
        val p = provider(api)
        val inFlight = async(Dispatchers.Default) { p.listProcessing() }
        waitUntil("history fetch to start") { api.historyCalls.get() > 0 }
        // remove() needs no history on a recognised response; the fetch above is still blocked.
        p.remove("req:a")
        api.historyData = mutableListOf()
        api.historyGate!!.complete(Unit)
        // the in-flight caller still gets its own result
        assertEquals(1, withTimeout(5_000) { inFlight.await() }.size)
        val before = api.historyCalls.get()
        assertEquals(0, p.listProcessing().size)
        assertEquals(before + 1, api.historyCalls.get())
    }

    @Test fun fetchInFlightDuringAddDoesNotRepopulateCache() = runBlocking {
        val api = FakeApi()
        api.historyData = mutableListOf(folder("a", "2026-01-01T00:00:00Z", "downloading"))
        api.historyGate = CompletableDeferred()
        val p = provider(api)
        val inFlight = async(Dispatchers.Default) { p.listProcessing() }
        waitUntil("history fetch to start") { api.historyCalls.get() > 0 }
        p.add(AddRequest.Magnet("magnet:?xt=urn:btih:abc"))
        api.historyGate!!.complete(Unit)
        withTimeout(5_000) { inFlight.await() }
        val before = api.historyCalls.get()
        p.listProcessing()
        assertEquals(before + 1, api.historyCalls.get())
    }

    // task 12 (a)
    @Test fun historyCachedUnderOneTokenIsNotServedUnderAnother() = runBlocking {
        val api = FakeApi()
        api.historyData = mutableListOf(folder("a", "2026-01-01T00:00:00Z", "downloading"))
        val p = provider(api)
        token = "A"
        p.listProcessing()
        token = "B"
        p.listProcessing()
        assertEquals(2, api.historyCalls.get())
    }

    @Test fun historyCacheIsUsedWithUnchangedToken() = runBlocking {
        val api = FakeApi()
        api.historyData = mutableListOf(folder("a", "2026-01-01T00:00:00Z", "downloading"))
        val p = provider(api)
        p.listProcessing()
        p.listProcessing()
        p.filesForJob("req:a")
        assertEquals(1, api.historyCalls.get())
    }

    @Test fun nullTokenNeverServedFromCache() = runBlocking {
        val api = FakeApi()
        api.historyData = mutableListOf(folder("a", "2026-01-01T00:00:00Z", "downloading"))
        val p = provider(api)
        token = null
        p.listProcessing()
        p.listProcessing()
        assertEquals(2, api.historyCalls.get())
        // and a null read does not serve an entry cached under a real token
        token = "A"
        p.listProcessing()
        token = null
        p.listProcessing()
        assertEquals(4, api.historyCalls.get())
    }

    @Test fun fetchInFlightWhenTokenChangesIsNotServedUnderNewToken() = runBlocking {
        val api = FakeApi()
        api.historyData = mutableListOf(folder("a", "2026-01-01T00:00:00Z", "downloading"))
        api.historyGate = CompletableDeferred()
        val p = provider(api)
        token = "A"
        val inFlight = async(Dispatchers.Default) { p.listProcessing() }
        waitUntil("history fetch to start") { api.historyCalls.get() > 0 }
        token = "B"
        api.historyGate!!.complete(Unit)
        withTimeout(5_000) { inFlight.await() }
        val before = api.historyCalls.get()
        p.listProcessing()
        assertEquals(before + 1, api.historyCalls.get())
    }

    // task 11 (c)
    @Test fun validateTokenClearsHistoryCache() = runBlocking {
        val api = FakeApi()
        api.historyData = mutableListOf(folder("a", "2026-01-01T00:00:00Z", "downloading"))
        val p = provider(api)
        p.listProcessing()
        p.validateToken("new")
        val before = api.historyCalls.get()
        p.listProcessing()
        assertEquals(before + 1, api.historyCalls.get())
    }

    @Test fun validateTokenClearsFolderCache() = runBlocking {
        val api = FakeApi()
        api.historyData = mutableListOf(folder("a", "2026-01-01T00:00:00Z"))
        val p = provider(api)
        p.listFiles(1, 50)
        assertEquals(1, api.exploreCalls.get())
        p.validateToken("new")
        p.listFiles(1, 50)
        assertEquals(2, api.exploreCalls.get())
    }

    // task 11 (d)
    @Test fun explore401IsNotRetried() {
        val api = FakeApi()
        api.historyData = mutableListOf(folder("a", "2026-01-01T00:00:00Z"))
        api.explore = { _, _ ->
            throw retrofit2.HttpException(retrofit2.Response.error<Any>(401, "".toResponseBody()))
        }
        assertThrows(retrofit2.HttpException::class.java) { runBlocking { provider(api).listFiles(1, 50) } }
        assertEquals(1, api.exploreCalls.get())
    }

    @Test fun exploreIoExceptionStillRetriedOnce() {
        val api = FakeApi()
        api.historyData = mutableListOf(folder("a", "2026-01-01T00:00:00Z"))
        api.explore = { _, _ -> throw IOException("down") }
        assertThrows(IOException::class.java) { runBlocking { provider(api).listFiles(1, 50) } }
        assertEquals(2, api.exploreCalls.get())
    }
}
