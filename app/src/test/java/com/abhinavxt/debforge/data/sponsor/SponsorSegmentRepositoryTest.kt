package com.abhinavxt.debforge.data.sponsor

import com.abhinavxt.debforge.domain.SponsorSegment
import com.abhinavxt.debforge.domain.SponsorSkip
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class SponsorSegmentRepositoryTest {

    private class FakeDao : SponsorSegmentDao {
        val rows = HashMap<String, SponsorSegmentEntity>()
        override suspend fun get(videoId: String) = rows[videoId]
        override suspend fun upsert(entity: SponsorSegmentEntity) { rows[entity.videoId] = entity }
        override suspend fun stale(cutoff: Long) = rows.values.filter { it.fetchedAt < cutoff }
    }

    private class FakeLookup(var result: List<SponsorSegment> = emptyList(), var fail: Boolean = false) : SponsorLookup {
        var calls = 0
        var lastCategories: Collection<String> = emptyList()
        override suspend fun segments(videoId: String, categories: Collection<String>): List<SponsorSegment> {
            calls++
            lastCategories = categories
            if (fail) throw IOException("offline")
            return result
        }
        override fun toJson(segments: List<SponsorSegment>) = segments.joinToString(";") { "${it.category},${it.startMs},${it.endMs}" }
        override fun fromJson(json: String) = json.split(';').filter { it.isNotBlank() }.map {
            val p = it.split(','); SponsorSegment(p[0], p[1].toLong(), p[2].toLong())
        }
    }

    private val sponsor = SponsorSegment("sponsor", 1_000, 9_000)
    private val promo = SponsorSegment("selfpromo", 20_000, 30_000)
    private val intro = SponsorSegment("intro", 0, 5_000)

    private var clock = 1_000_000_000L
    private var enabled = true
    private var cats = setOf("sponsor", "selfpromo")
    private val dao = FakeDao()
    private val lookup = FakeLookup(listOf(sponsor, promo, intro))
    private val repo = SponsorSegmentRepository(lookup, dao, { enabled }, { cats }, { clock })

    @Test fun disabledReturnsEmptyAndNeverFetches() = runBlocking {
        enabled = false
        assertTrue(repo.segmentsFor("v").isEmpty())
        assertEquals(0, lookup.calls)
    }

    @Test fun noCategoriesReturnsEmptyAndNeverFetches() = runBlocking {
        cats = emptySet()
        assertTrue(repo.segmentsFor("v").isEmpty())
        repo.prefetch("v")
        assertEquals(0, lookup.calls)
    }

    @Test fun fetchesOnceThenServesFromCacheFilteredToEnabledCategories() = runBlocking {
        assertEquals(listOf(sponsor, promo), repo.segmentsFor("v"))
        assertEquals(listOf(sponsor, promo), repo.segmentsFor("v"))
        assertEquals(1, lookup.calls)
        assertEquals(3, lookup.fromJson(dao.rows["v"]!!.json.substringAfter("\n")).size)
    }

    @Test fun fetchesDefaultsPlusEnabled() = runBlocking {
        cats = setOf("intro")
        repo.segmentsFor("v")
        assertEquals(setOf("sponsor", "selfpromo", "interaction", "intro"), lookup.lastCategories.toSet())
    }

    @Test fun narrowingCategoriesFiltersWithoutRefetch() = runBlocking {
        repo.segmentsFor("v")
        cats = setOf("sponsor")
        assertEquals(listOf(sponsor), repo.segmentsFor("v"))
        assertEquals(1, lookup.calls)
    }

    @Test fun newlyEnabledCategoryTriggersRefetch() = runBlocking {
        lookup.result = listOf(sponsor, promo)
        assertEquals(listOf(sponsor, promo), repo.segmentsFor("v"))
        cats = setOf("sponsor", "selfpromo", "intro")
        lookup.result = listOf(sponsor, promo, intro)
        assertEquals(listOf(sponsor, promo, intro), repo.segmentsFor("v"))
        assertEquals(2, lookup.calls)
        assertTrue("intro" in lookup.lastCategories)
        repo.segmentsFor("v")
        assertEquals(2, lookup.calls) // now cached
    }

    @Test fun emptyResultsExpireAfterADayButNonEmptyDoNot() = runBlocking {
        lookup.result = emptyList()
        repo.segmentsFor("e")
        clock += SponsorSegmentRepository.EMPTY_MAX_AGE_MS - 1
        repo.segmentsFor("e")
        assertEquals(1, lookup.calls)
        clock += 2
        lookup.result = listOf(sponsor)
        assertEquals(listOf(sponsor), repo.segmentsFor("e"))
        assertEquals(2, lookup.calls)
        clock += SponsorSegmentRepository.EMPTY_MAX_AGE_MS + 1
        repo.segmentsFor("e")
        assertEquals(2, lookup.calls)
    }

    @Test fun oldPayloadCountsAsFetchedWithDefaultCategories() = runBlocking {
        dao.rows["v"] = SponsorSegmentEntity("v", lookup.toJson(listOf(sponsor, promo)), clock)
        assertEquals(listOf(sponsor, promo), repo.segmentsFor("v"))
        assertEquals(0, lookup.calls)
        cats = setOf("sponsor", "intro") // not covered by defaults
        lookup.result = listOf(sponsor, intro)
        assertEquals(listOf(sponsor, intro), repo.segmentsFor("v"))
        assertEquals(1, lookup.calls)
    }

    @Test fun staleCopyWithMissingCategoryStillServesOffline() = runBlocking {
        repo.segmentsFor("v")
        cats = setOf("sponsor", "intro")
        lookup.fail = true
        assertEquals(listOf(sponsor, intro), repo.segmentsFor("v"))
    }

    @Test fun segmentEndingNearVideoEndPlaysNextOnlyWithAQueue() {
        assertTrue(SponsorSkip.endsVideo(59_800, 60_000, 500, true))
        assertTrue(!SponsorSkip.endsVideo(59_800, 60_000, 500, false))
        assertTrue(!SponsorSkip.endsVideo(30_000, 60_000, 500, true))
    }

    @Test fun staleLoadIsDroppedByGeneration() {
        assertTrue(SponsorSkip.isCurrent(3, 3))
        assertTrue(!SponsorSkip.isCurrent(3, 4))
    }

    @Test fun refetchesAfterSevenDaysAndReplacesRow() = runBlocking {
        repo.segmentsFor("v")
        clock += SponsorSegmentRepository.MAX_AGE_MS + 1
        lookup.result = listOf(promo)
        assertEquals(listOf(promo), repo.segmentsFor("v"))
        assertEquals(2, lookup.calls)
        assertEquals(clock, dao.rows["v"]!!.fetchedAt)
    }

    @Test fun networkErrorFallsBackToStaleCacheAndKeepsIt() = runBlocking {
        repo.segmentsFor("v")
        clock += SponsorSegmentRepository.MAX_AGE_MS + 1
        lookup.fail = true
        assertEquals(listOf(sponsor, promo), repo.segmentsFor("v"))
        assertEquals(1, dao.rows.size)
    }

    @Test fun networkErrorWithNoCacheIsEmpty() = runBlocking {
        lookup.fail = true
        assertTrue(repo.segmentsFor("v").isEmpty())
        assertNull(dao.rows["v"])
    }

    @Test fun prefetchFillsCacheAndSwallowsErrors() = runBlocking {
        repo.prefetch("v")
        assertEquals(1, dao.rows.size)
        lookup.fail = true
        repo.prefetch("w")
        assertNull(dao.rows["w"])
        enabled = false
        repo.prefetch("x")
        assertEquals(2, lookup.calls)
    }

    @Test fun autoSkipOncePerSegment() {
        val segs = SponsorSkip.normalise(listOf(sponsor, promo), 60_000)
        val done = HashSet<Long>()
        assertNull(SponsorSkip.autoSkip(segs, 500, done))
        val hit = SponsorSkip.autoSkip(segs, 2_000, done)!!
        assertEquals(9_000, hit.endMs)
        done += hit.startMs
        assertNull(SponsorSkip.autoSkip(segs, 2_000, done)) // seeked back: left alone
        assertEquals(promo.startMs, SponsorSkip.autoSkip(segs, 25_000, done)!!.startMs)
    }
}
