package com.abhinavxt.debforge.domain

import com.abhinavxt.debforge.data.subscriptions.AutoDownload
import com.abhinavxt.debforge.data.subscriptions.FeedItemEntity
import com.abhinavxt.debforge.data.subscriptions.SubscriptionEntity
import com.abhinavxt.debforge.data.subscriptions.SubscriptionKind
import com.abhinavxt.debforge.download.youtube.EnqueueResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.abhinavxt.debforge.data.youtube.YouTubeException

private class FakeStore : FeedStore {
    val items = mutableMapOf<Long, MutableMap<String, FeedItemEntity>>()
    val checked = mutableMapOf<Long, Long>()
    val trimmed = mutableListOf<Long>()
    /** Subscriptions known to the store; pendingItems filters like the real DAO's join (OFF, since). */
    val subs = mutableMapOf<Long, SubscriptionEntity>()
    override suspend fun itemKeys(subId: Long) = items[subId].orEmpty().keys.toSet()
    override suspend fun upsertItems(items: List<FeedItemEntity>) {
        items.forEach { this.items.getOrPut(it.subId) { mutableMapOf() }.putIfAbsent(it.itemKey, it) }
    }
    override suspend fun pendingItems(subId: Long) =
        items[subId].orEmpty().values.filter {
            val s = subs[subId]
            it.isNew && !it.queued && it.publishedAt > 0 && (s == null || (s.autoDownload != AutoDownload.OFF && it.publishedAt > s.since))
        }.sortedByDescending { it.publishedAt }
    override suspend fun markQueued(subId: Long, itemKey: String) {
        val m = items[subId]!!; m[itemKey] = m[itemKey]!!.copy(queued = true)
    }
    override suspend fun markChecked(sub: SubscriptionEntity, checkedAt: Long) { checked[sub.id] = checkedAt }
    override suspend fun trim(subId: Long, keep: Int) { trimmed += subId }
}

class SubscriptionFeedTest {
    private fun sub(id: Long = 1, auto: AutoDownload = AutoDownload.AUDIO, since: Long = 1000) =
        SubscriptionEntity(id = id, kind = SubscriptionKind.YT_CHANNEL, url = "https://www.youtube.com/channel/UC$id", title = "Chan$id", autoDownload = auto, since = since)

    private fun e(key: String, at: Long?) = FeedEntry(key, "T$key", "u$key", at)

    @Test fun onlyEntriesAfterSinceAreNew() {
        val p = FeedPlanner.plan(sub(since = 1000), emptySet(), listOf(e("old", 900), e("same", 1000), e("fresh", 1001)), now = 5000)
        assertEquals(listOf("fresh"), p.items.filter { it.isNew }.map { it.itemKey })
    }

    @Test fun knownEntriesAreNotNew() {
        val p = FeedPlanner.plan(sub(), setOf("a"), listOf(e("a", 2000), e("b", 2000)), now = 5000)
        assertEquals(listOf("b"), p.items.filter { it.isNew }.map { it.itemKey })
    }

    @Test fun initialFetchMarksNothingNewNorDownloads() {
        val p = FeedPlanner.plan(sub(), emptySet(), listOf(e("a", 2000)), now = 5000, initial = true)
        assertTrue(p.items.none { it.isNew })
        assertTrue(p.toDownload.isEmpty())
    }

    @Test fun undatedEntriesAreNeverNewNorQueued() {
        val p = FeedPlanner.plan(sub(since = 1000), emptySet(), listOf(e("a", null)), now = 5000)
        assertEquals(FeedPlanner.UNKNOWN_DATE, p.items.single().publishedAt)
        assertTrue(p.items.none { it.isNew })
        assertTrue(p.toDownload.isEmpty())
    }

    @Test fun undatedFallbackAfterDatedFollowDoesNotFlood() = runBlocking {
        val store = FakeStore()
        val s = sub(since = 1000)
        store.upsertItems(FeedPlanner.plan(s, emptySet(), listOf(e("v1", 2000)), now = 3000, initial = true).items)
        var enq = 0
        val run = SubscriptionRun(store, { (1..30).map { e("old$it", null) } + e("v1", null) }, { _, _ -> enq++; EnqueueResult.QUEUED }, now = { 9000 })
        val r = run.run(listOf(s))
        assertEquals(0, r.newLabels.size)
        assertEquals(0, enq)
        assertTrue(store.items[1]!!.values.none { it.isNew })
    }

    @Test fun failedEnqueueIsRetriedOnNextCheck() = runBlocking {
        val store = FakeStore()
        var fail = true
        var enq = 0
        val run = SubscriptionRun(store, { listOf(e("a", 3000)) }, { _, _ -> enq++; if (fail) throw java.io.IOException("x") else EnqueueResult.QUEUED }, now = { 9000 })
        run.run(listOf(sub(1)))
        assertTrue(!store.items[1]!!["a"]!!.queued)
        fail = false
        val r = run.run(listOf(sub(1)))
        assertTrue(store.items[1]!!["a"]!!.queued)
        assertEquals(1, r.queued)
        assertEquals(2, enq)
    }

    @Test fun downloadsCappedAtThreeNewestFirst() {
        val entries = (1..5).map { e("v$it", 1000L + it) }
        val p = FeedPlanner.plan(sub(), emptySet(), entries, now = 9000)
        assertEquals(listOf("v5", "v4", "v3"), p.toDownload.map { it.itemKey })
        assertEquals(5, p.items.count { it.isNew })
    }

    @Test fun autoOffDownloadsNothing() {
        val p = FeedPlanner.plan(sub(auto = AutoDownload.OFF), emptySet(), listOf(e("a", 2000)), now = 5000)
        assertTrue(p.toDownload.isEmpty())
        assertTrue(p.items.single().isNew)
    }

    @Test fun duplicateKeysCollapse() {
        val p = FeedPlanner.plan(sub(), emptySet(), listOf(e("a", 2000), e("a", 2000)), now = 5000)
        assertEquals(1, p.items.size)
    }

    @Test fun runQueuesMarksAndIsolatesErrors() = runBlocking {
        val store = FakeStore()
        val enqueued = mutableListOf<String>()
        val errors = mutableListOf<Long>()
        val run = SubscriptionRun(
            store = store,
            fetch = { s -> if (s.id == 1L) error("boom") else if (s.id == 3L) null else listOf(e("x", 2000), e("y", 2001)) },
            enqueue = { s, i -> enqueued += "${s.id}:${i.itemKey}"; EnqueueResult.QUEUED },
            now = { 7000 },
            onError = { s, _ -> errors += s.id },
        )
        val r = run.run(listOf(sub(1), sub(2), sub(3)))
        assertEquals(listOf(1L), errors)
        assertEquals(listOf("2:y", "2:x"), enqueued)
        assertEquals(2, r.queued)
        assertEquals(2, r.newLabels.size)
        assertTrue(store.items[2]!!.values.all { it.queued })
        assertEquals(7000L, store.checked[2])
        assertNull(store.checked[1])
        assertNull(store.checked[3])
        assertEquals(listOf(2L), store.trimmed)
    }

    @Test fun failedEnqueueIsNotMarkedQueuedAndDoesNotStopOthers() = runBlocking {
        val store = FakeStore()
        val run = SubscriptionRun(
            store = store,
            fetch = { listOf(e("a", 3000), e("b", 3001)) },
            enqueue = { _, i -> if (i.itemKey == "b") throw java.io.IOException("x") else EnqueueResult.QUEUED },
            now = { 8000 },
        )
        run.run(listOf(sub(1)))
        assertTrue(store.items[1]!!["a"]!!.queued)
        assertTrue(!store.items[1]!!["b"]!!.queued)
        assertEquals(8000L, store.checked[1])
    }

    @Test fun secondRunDoesNotRepeatKnownItems() = runBlocking {
        val store = FakeStore()
        var enq = 0
        val run = SubscriptionRun(store, { listOf(e("a", 3000)) }, { _, _ -> enq++; EnqueueResult.QUEUED }, now = { 9000 })
        run.run(listOf(sub(1))); run.run(listOf(sub(1)))
        assertEquals(1, enq)
    }

    @Test fun retriesAtMostThreeStuckItemsPerCheck() = runBlocking {
        val store = FakeStore()
        val s = sub(1); store.subs[1] = s
        val tried = mutableListOf<String>()
        val run = SubscriptionRun(store, { (1..5).map { e("v$it", 2000L + it) } },
            { _, i -> tried += i.itemKey; throw java.io.IOException("x") }, now = { 9000 })
        run.run(listOf(s))
        assertEquals(listOf("v5", "v4", "v3"), tried)
        tried.clear()
        run.run(listOf(s))
        assertEquals(listOf("v5", "v4", "v3"), tried)
    }

    @Test fun itemsNotAfterSinceAreNeverDownloaded() = runBlocking {
        val store = FakeStore()
        val s = sub(1, since = 1000); store.subs[1] = s
        store.items[1] = mutableMapOf("old" to FeedItemEntity(1, "old", "t", null, 900, null, null, isNew = true, queued = false))
        val tried = mutableListOf<String>()
        SubscriptionRun(store, { listOf(e("old", 900)) }, { _, i -> tried += i.itemKey; EnqueueResult.QUEUED }, now = { 9000 }).run(listOf(s))
        assertTrue(tried.isEmpty())
    }

    @Test fun autoOffNeverEnqueuesButItemsStayNew() = runBlocking {
        val store = FakeStore()
        val s = sub(1, auto = AutoDownload.OFF); store.subs[1] = s
        var enq = 0
        val r = SubscriptionRun(store, { listOf(e("a", 3000)) }, { _, _ -> enq++; EnqueueResult.QUEUED }, now = { 9000 }).run(listOf(s))
        assertEquals(0, enq)
        assertEquals(1, r.newLabels.size)
        assertTrue(store.items[1]!!["a"]!!.isNew && !store.items[1]!!["a"]!!.queued)
    }

    @Test fun permanentFailuresAreHandledAndStopCrowdingOlderItems() = runBlocking {
        val store = FakeStore()
        val s = sub(1); store.subs[1] = s
        val entries = (1..4).map { e("v$it", 2000L + it) }
        val tried = mutableListOf<String>()
        val run = SubscriptionRun(store, { entries }, { _, i ->
            tried += i.itemKey
            when (i.itemKey) {
                "v4" -> throw YouTubeException.Unavailable("members only")
                "v3" -> EnqueueResult.NO_FORMAT
                else -> throw java.io.IOException("offline")
            }
        }, now = { 9000 }, isPermanent = { it is YouTubeException.Unavailable })
        run.run(listOf(s))
        val v4 = store.items[1]!!["v4"]!!
        assertTrue(v4.queued && v4.isNew) // handled, still in the New row
        assertTrue(store.items[1]!!["v3"]!!.queued)
        assertTrue(!store.items[1]!!["v2"]!!.queued) // transient: retried
        tried.clear()
        run.run(listOf(s))
        assertEquals(listOf("v2", "v1"), tried) // older items now get their turn
    }

    @Test fun networkWorkRunsOutsideTheGuard() = runBlocking {
        val store = FakeStore()
        val s = sub(1); store.subs[1] = s
        val lock = Mutex()
        var fetchLocked = true; var enqueueLocked = true; var guarded = 0
        val run = SubscriptionRun(store, { fetchLocked = lock.isLocked; listOf(e("a", 3000)) },
            { _, _ -> enqueueLocked = lock.isLocked; EnqueueResult.QUEUED }, now = { 9000 },
            guard = { b -> lock.withLock { guarded++; b() } })
        run.run(listOf(s))
        assertTrue(!fetchLocked && !enqueueLocked)
        assertEquals(2, guarded)
        assertTrue(store.items[1]!!["a"]!!.queued)
    }

    @Test fun lockIsFreeWhileASlowFetchIsInFlight() = runBlocking {
        val store = FakeStore()
        val s = sub(1)
        val lock = Mutex()
        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val run = SubscriptionRun(store, { started.complete(Unit); gate.await(); listOf(e("a", 3000)) },
            { _, _ -> EnqueueResult.QUEUED }, now = { 9000 }, guard = { b -> lock.withLock { b() } })
        val job = async { run.run(listOf(s)) }
        started.await()
        // a follow would take this lock; it must be immediately available mid-fetch
        assertTrue(lock.tryLock()); lock.unlock()
        gate.complete(Unit)
        job.await()
        Unit
    }

    @Test fun channelUrlNormalisation() {
        val id = "UCabcdefghijklmnopqrstuv"
        val canon = "https://www.youtube.com/channel/$id"
        assertEquals(canon, YouTubeChannelLinks.canonicalUrl(canon))
        assertEquals(canon, YouTubeChannelLinks.canonicalUrl("http://m.youtube.com/channel/$id/videos?x=1"))
        assertEquals(canon, YouTubeChannelLinks.canonicalUrl("youtube.com/channel/$id"))
        assertEquals(canon, YouTubeChannelLinks.canonicalUrl("  $id "))
        assertNull(YouTubeChannelLinks.canonicalUrl("https://www.youtube.com/@name"))
        assertTrue(YouTubeChannelLinks.needsResolving("https://www.youtube.com/@name/videos"))
        assertTrue(YouTubeChannelLinks.needsResolving("https://www.youtube.com/c/Name"))
        assertTrue(YouTubeChannelLinks.needsResolving("youtube.com/user/Name"))
        assertEquals("https://www.youtube.com/@name", YouTubeChannelLinks.resolvable("@name"))
        assertEquals("https://youtube.com/c/Name", YouTubeChannelLinks.resolvable("youtube.com/c/Name"))
        assertNull(YouTubeChannelLinks.resolvable("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertNull(YouTubeChannelLinks.resolvable("hello"))
    }
}
