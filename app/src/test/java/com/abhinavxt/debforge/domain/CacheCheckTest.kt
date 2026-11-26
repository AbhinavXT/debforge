package com.abhinavxt.debforge.domain

import com.abhinavxt.debforge.data.provider.ProviderCalls
import com.abhinavxt.debforge.data.provider.ProviderInfo
import com.abhinavxt.debforge.ui.add.CacheSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CacheCheckTest {
    private val hashes = listOf("a", "b", "c")
    private val err = DataResult.Error("Network error")

    @Test fun allAnsweredSplitsCachedFromNotCached() {
        val o = SearchResults.combineCache(hashes, listOf("TorBox" to DataResult.Success(setOf("a")), "Premiumize" to DataResult.Success(setOf("b"))))
        assertEquals(setOf("a", "b"), o.cached)
        assertTrue(o.unknown.isEmpty())
        assertTrue(o.failedServices.isEmpty())
    }

    @Test fun failedServiceLeavesUncachedHashesUnknown() {
        val o = SearchResults.combineCache(hashes, listOf("TorBox" to err, "Premiumize" to DataResult.Success(setOf("b"))))
        assertEquals(setOf("b"), o.cached)
        assertEquals(setOf("a", "c"), o.unknown)
        assertEquals(listOf("TorBox"), o.failedServices)
    }

    @Test fun everyServiceFailingMakesEverythingUnknown() {
        val o = SearchResults.combineCache(hashes, listOf("TorBox" to err))
        assertTrue(o.cached.isEmpty())
        assertEquals(hashes.toSet(), o.unknown)
    }

    @Test fun addSummaryShowsFailureWithReasonInsteadOfNotCached() {
        val s = CacheSummary.of(2, checking = false, failure = "Network error", known = listOf(null, null))!!
        assertEquals("Network error", s.failure)
        assertEquals(false, s.noneCached)
        assertEquals(false, s.allCached)
    }

    @Test fun addSummaryStatesWithoutFailure() {
        assertNull(CacheSummary.of(0, false, null, emptyList()))
        assertNull(CacheSummary.of(2, false, null, listOf(true, null)))
        assertTrue(CacheSummary.of(2, true, null, listOf(null, null))!!.checking)
        assertTrue(CacheSummary.of(2, false, null, listOf(false, false))!!.noneCached)
        assertTrue(CacheSummary.of(2, false, null, listOf(true, true))!!.allCached)
    }

    private val info = ProviderInfo(ProviderId.TORBOX, "TorBox", "", "", "API key", "", "")

    @Test fun slowCheckIsReportedAsFailure() = runBlocking {
        val r = ProviderCalls.run(info, "checking the cache", timeoutMs = 50) { delay(5_000); setOf("a") }
        assertTrue(r is DataResult.Error)
    }

    @Test fun callerCancellationIsStillRethrown() = runBlocking {
        val job = async { ProviderCalls.run(info, "x", timeoutMs = 60_000) { awaitCancellation() } }
        delay(50)
        job.cancel()
        try {
            job.await()
            fail("expected cancellation")
        } catch (e: CancellationException) {
            // ok
        }
    }
}
