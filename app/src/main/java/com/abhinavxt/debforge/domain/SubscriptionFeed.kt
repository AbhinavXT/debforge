package com.abhinavxt.debforge.domain

import com.abhinavxt.debforge.data.subscriptions.AutoDownload
import com.abhinavxt.debforge.data.subscriptions.FeedItemEntity
import com.abhinavxt.debforge.data.subscriptions.SubscriptionEntity
import com.abhinavxt.debforge.download.youtube.EnqueueResult
import kotlinx.coroutines.CancellationException

/** One entry of a channel feed. [publishedAt] is null when the source gave no date. */
data class FeedEntry(
    val key: String,
    val title: String,
    val url: String?,
    val publishedAt: Long?,
    val durationSec: Long? = null,
    val thumbUrl: String? = null,
)

/** Decides which feed entries are new and which of them to download. Pure. */
object FeedPlanner {
    /** Auto-downloads per subscription per check; the rest stay in the New row. */
    const val AUTO_CAP = 3

    /** Stored as publishedAt when the source gave no date; never overwrites a real date. */
    const val UNKNOWN_DATE = 0L

    class Plan(val items: List<FeedItemEntity>, val toDownload: List<FeedItemEntity>)

    /**
     * An entry is new when its key is not in [known] and it was published after [SubscriptionEntity.since]
     * (an entry with no date is never new: it is stored with [UNKNOWN_DATE], because a feed that lost its dates
     * would otherwise flood the New row and auto-download the back catalogue). [initial] (the first fetch right
     * after following) records everything as not new. At most [cap] new entries, newest first, are chosen for download.
     */
    fun plan(sub: SubscriptionEntity, known: Set<String>, entries: List<FeedEntry>, now: Long, initial: Boolean = false, cap: Int = AUTO_CAP): Plan {
        val items = entries.distinctBy { it.key }.map {
            val published = it.publishedAt ?: UNKNOWN_DATE
            FeedItemEntity(
                subId = sub.id, itemKey = it.key, title = it.title, mediaUrl = it.url, publishedAt = published,
                durationSec = it.durationSec, thumbUrl = it.thumbUrl,
                isNew = !initial && it.key !in known && published != UNKNOWN_DATE && published > sub.since, queued = false,
            )
        }
        val download = if (sub.autoDownload == AutoDownload.OFF) emptyList()
        else items.filter { it.isNew }.sortedByDescending { it.publishedAt }.take(cap)
        return Plan(items, download)
    }
}

/** What the storage side of a check needs. */
interface FeedStore {
    suspend fun itemKeys(subId: Long): Set<String>
    suspend fun upsertItems(items: List<FeedItemEntity>)
    /** Items still marked new that were never queued, e.g. because an earlier enqueue failed. */
    suspend fun pendingItems(subId: Long): List<FeedItemEntity>
    suspend fun markQueued(subId: Long, itemKey: String)
    suspend fun markChecked(sub: SubscriptionEntity, checkedAt: Long)
    suspend fun trim(subId: Long, keep: Int)
}

/**
 * Checks subscriptions one by one. A failing subscription is reported to [onError] and skipped; it never
 * stops the others. [fetch] returns null for a kind that is not handled yet (podcasts), which is skipped
 * without marking it checked.
 */
class SubscriptionRun(
    private val store: FeedStore,
    private val fetch: suspend (SubscriptionEntity) -> List<FeedEntry>?,
    private val enqueue: suspend (SubscriptionEntity, FeedItemEntity) -> EnqueueResult,
    private val now: () -> Long = System::currentTimeMillis,
    private val onError: (SubscriptionEntity, Throwable) -> Unit = { _, _ -> },
    private val keep: Int = KEEP_ITEMS,
) {
    class Result(val newLabels: List<String>, val queued: Int)

    suspend fun run(subs: List<SubscriptionEntity>): Result {
        val labels = mutableListOf<String>()
        var queued = 0
        for (sub in subs) {
            try {
                val entries = fetch(sub) ?: continue
                val t = now()
                val plan = FeedPlanner.plan(sub, store.itemKeys(sub.id), entries, t)
                store.upsertItems(plan.items)
                labels += plan.items.filter { it.isNew }.sortedByDescending { it.publishedAt }.map { "${sub.title} · ${it.title}" }
                // Newly found items plus earlier ones whose enqueue failed, within the cap, newest first.
                val toDownload = if (sub.autoDownload == AutoDownload.OFF) emptyList()
                else store.pendingItems(sub.id).filter { it.publishedAt > sub.since }
                    .sortedByDescending { it.publishedAt }.take(FeedPlanner.AUTO_CAP)
                for (item in toDownload) {
                    val r = try {
                        enqueue(sub, item)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        onError(sub, e)
                        EnqueueResult.FAILED
                    }
                    if (r != EnqueueResult.FAILED) {
                        store.markQueued(sub.id, item.itemKey)
                        if (r == EnqueueResult.QUEUED) queued++
                    }
                }
                store.markChecked(sub, t)
                store.trim(sub.id, keep)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(sub, e)
            }
        }
        return Result(labels, queued)
    }

    companion object {
        const val KEEP_ITEMS = 100
    }
}
