package com.abhinavxt.debforge.domain

import com.abhinavxt.debforge.data.podcast.PodcastFetchException
import com.abhinavxt.debforge.data.subscriptions.AutoDownload
import com.abhinavxt.debforge.data.subscriptions.FeedItemEntity
import com.abhinavxt.debforge.data.subscriptions.SubscriptionEntity
import com.abhinavxt.debforge.data.subscriptions.SubscriptionKind
import com.abhinavxt.debforge.download.youtube.EnqueueResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class PodStore : FeedStore {
    val items = mutableMapOf<String, FeedItemEntity>()
    val checked = mutableListOf<Long>()
    override suspend fun itemKeys(subId: Long) = items.keys.toSet()
    override suspend fun upsertItems(items: List<FeedItemEntity>) { items.forEach { this.items.putIfAbsent(it.itemKey, it) } }
    override suspend fun pendingItems(subId: Long) = items.values.filter { it.isNew && !it.queued && it.publishedAt > 0 }
    override suspend fun markQueued(subId: Long, itemKey: String) { items[itemKey] = items[itemKey]!!.copy(queued = true) }
    override suspend fun markChecked(sub: SubscriptionEntity, checkedAt: Long) { checked += sub.id }
    override suspend fun trim(subId: Long, keep: Int) {}
}

class PodcastRunTest {
    private val sub = SubscriptionEntity(
        id = 1, kind = SubscriptionKind.PODCAST, url = "https://x.test/feed", title = "Show",
        autoDownload = AutoDownload.AUDIO, since = 1000,
    )

    private fun ep(n: Int, at: Long?) = FeedEntry("g$n", "Episode $n", "https://x.test/$n.mp3", at)

    @Test fun followingRecordsBackCatalogueAsNotNew() {
        val plan = FeedPlanner.plan(sub, emptySet(), (1..50).map { ep(it, 2000L + it) }, now = 9000, initial = true)
        assertEquals(50, plan.items.size)
        assertTrue(plan.items.none { it.isNew })
        assertTrue(plan.toDownload.isEmpty())
    }

    @Test fun capsAutoDownloadAtThreeNewestFirst() = runBlocking {
        val store = PodStore()
        val queued = mutableListOf<String>()
        val run = SubscriptionRun(
            store, fetch = { (1..6).map { ep(it, 2000L + it) } },
            enqueue = { _, i -> queued += i.itemKey; EnqueueResult.QUEUED },
        )
        val r = run.run(listOf(sub))
        assertEquals(listOf("g6", "g5", "g4"), queued)
        assertEquals(6, r.newLabels.size)
        assertEquals(3, r.queued)
    }

    @Test fun undatedAndOldEpisodesAreNotNew() = runBlocking {
        val store = PodStore()
        val run = SubscriptionRun(store, fetch = { listOf(ep(1, null), ep(2, 500), ep(3, 3000)) }, enqueue = { _, _ -> EnqueueResult.QUEUED })
        val r = run.run(listOf(sub))
        assertEquals(1, r.newLabels.size)
        assertEquals(setOf("g3"), store.items.values.filter { it.isNew }.map { it.itemKey }.toSet())
    }

    @Test fun unchangedFeedStillRetriesFailedEnqueues() = runBlocking {
        val store = PodStore()
        var fail = true
        val run = SubscriptionRun(
            store, fetch = { if (store.items.isEmpty()) listOf(ep(1, 3000)) else emptyList() },
            enqueue = { _, _ -> if (fail) EnqueueResult.FAILED else EnqueueResult.QUEUED },
        )
        run.run(listOf(sub))
        assertEquals(false, store.items["g1"]!!.queued)
        fail = false
        val r = run.run(listOf(sub))
        assertEquals(true, store.items["g1"]!!.queued)
        assertEquals(1, r.queued)
    }

    @Test fun permanentEnqueueFailureIsMarkedHandled() = runBlocking {
        val store = PodStore()
        val run = SubscriptionRun(
            store, fetch = { listOf(ep(1, 3000)) },
            enqueue = { _, _ -> throw PodcastFetchException(PodcastFetchException.Kind.HTTP, 404) },
            isPermanent = { it is PodcastFetchException && it.permanent },
        )
        val r = run.run(listOf(sub))
        assertEquals(true, store.items["g1"]!!.queued)
        assertEquals(0, r.queued)
    }

    @Test fun transientEnqueueFailureStaysPending() = runBlocking {
        val store = PodStore()
        val run = SubscriptionRun(
            store, fetch = { listOf(ep(1, 3000)) },
            enqueue = { _, _ -> throw PodcastFetchException(PodcastFetchException.Kind.NETWORK) },
            isPermanent = { it is PodcastFetchException && it.permanent },
        )
        run.run(listOf(sub))
        assertEquals(false, store.items["g1"]!!.queued)
    }

    @Test fun failedFeedFetchIsReportedAndNotMarkedChecked() = runBlocking {
        val store = PodStore()
        val errors = mutableListOf<Throwable>()
        val run = SubscriptionRun(
            store, fetch = { throw PodcastFetchException(PodcastFetchException.Kind.NETWORK) },
            enqueue = { _, _ -> EnqueueResult.QUEUED }, onError = { _, e -> errors += e },
        )
        run.run(listOf(sub))
        assertEquals(1, errors.size)
        assertTrue(store.checked.isEmpty())
    }
}
