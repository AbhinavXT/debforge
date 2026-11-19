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

private class FakeStore : FeedStore {
    val items = mutableMapOf<Long, MutableMap<String, FeedItemEntity>>()
    val checked = mutableMapOf<Long, Long>()
    val trimmed = mutableListOf<Long>()
    override suspend fun itemKeys(subId: Long) = items[subId].orEmpty().keys.toSet()
    override suspend fun upsertItems(items: List<FeedItemEntity>) {
        items.forEach { this.items.getOrPut(it.subId) { mutableMapOf() }.putIfAbsent(it.itemKey, it) }
    }
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

    @Test fun undatedEntryCountsAsPublishedNow() {
        val p = FeedPlanner.plan(sub(since = 1000), emptySet(), listOf(e("a", null)), now = 5000)
        assertEquals(5000L, p.items.single().publishedAt)
        assertTrue(p.items.single().isNew)
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
