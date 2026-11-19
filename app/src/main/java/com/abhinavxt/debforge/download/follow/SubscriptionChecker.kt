package com.abhinavxt.debforge.download.follow

import android.content.Context
import android.util.Log
import com.abhinavxt.debforge.data.podcast.PodcastFeeds
import com.abhinavxt.debforge.data.podcast.PodcastFetchException
import com.abhinavxt.debforge.data.podcast.PodcastFetchResult
import com.abhinavxt.debforge.data.podcast.PodcastFetcher
import com.abhinavxt.debforge.data.subscriptions.AutoDownload
import com.abhinavxt.debforge.data.subscriptions.FeedItemEntity
import com.abhinavxt.debforge.data.subscriptions.SubscriptionDao
import com.abhinavxt.debforge.data.subscriptions.SubscriptionEntity
import com.abhinavxt.debforge.data.subscriptions.SubscriptionKind
import com.abhinavxt.debforge.data.youtube.YouTubeException
import com.abhinavxt.debforge.data.youtube.YouTubeResolver
import com.abhinavxt.debforge.domain.FeedStore
import com.abhinavxt.debforge.domain.SubscriptionRun
import com.abhinavxt.debforge.download.DownloadNotifications
import com.abhinavxt.debforge.download.podcast.PodcastEnqueuer
import com.abhinavxt.debforge.download.youtube.EnqueueMode
import com.abhinavxt.debforge.download.youtube.YouTubeEnqueuer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Background check of followed YouTube channels and podcasts (run by [FollowJobService]): records new
 * uploads as feed items and queues up to [com.abhinavxt.debforge.domain.FeedPlanner.AUTO_CAP] per channel
 * for download when the subscription's auto-download is on. Rules live in [SubscriptionRun].
 */
@Singleton
class SubscriptionChecker @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: SubscriptionDao,
    private val resolver: YouTubeResolver,
    private val enqueuer: YouTubeEnqueuer,
    private val podcastFetcher: PodcastFetcher,
    private val podcastEnqueuer: PodcastEnqueuer,
) {
    /** Serialises whole checks, so two never enqueue the same item. Held across network calls. */
    private val checkLock = Mutex()

    /** Guards the short database sections only; [SubscriptionRepository.follow] takes it too, never for a network call. */
    private val dbLock = Mutex()

    /** Returns how many new uploads were found. */
    suspend fun check(fromBackground: Boolean): Int = checkLock.withLock {
        val subs = exclusive { dao.all() }
        if (subs.isEmpty()) return 0
        // Validators of the feeds fetched in this check, stored with markChecked.
        val validators = HashMap<Long, Pair<String?, String?>>()
        val run = SubscriptionRun(
            store = DaoFeedStore(dao, validators),
            fetch = { sub ->
                when (sub.kind) {
                    SubscriptionKind.YT_CHANNEL -> resolver.channelFeed(sub.url)
                    SubscriptionKind.PODCAST -> when (val r = podcastFetcher.fetch(sub.url, sub.etag, sub.lastModified)) {
                        // Unchanged: nothing new, but earlier failed enqueues are still retried.
                        PodcastFetchResult.NotModified -> emptyList()
                        is PodcastFetchResult.Fresh -> {
                            validators[sub.id] = r.etag to r.lastModified
                            PodcastFeeds.entries(r.feed)
                        }
                    }
                }
            },
            enqueue = { sub, item ->
                if (sub.kind == SubscriptionKind.PODCAST) podcastEnqueuer.enqueue(sub, item)
                else enqueuer.enqueue(item.itemKey, modeFor(sub), foreground = false)
            },
            onError = { sub, e -> Log.w("SubscriptionCheck", "Check of ${sub.url} failed", e) },
            guard = { block -> exclusive { block() } },
            isPermanent = { it is YouTubeException.Unavailable || (it is PodcastFetchException && it.permanent) },
        )
        val result = run.run(subs)
        if (fromBackground && result.newLabels.isNotEmpty() && !isAppInForeground()) DownloadNotifications.notifyNewUploads(context, result.newLabels)
        result.newLabels.size
    }

    private fun isAppInForeground(): Boolean {
        val info = android.app.ActivityManager.RunningAppProcessInfo()
        android.app.ActivityManager.getMyMemoryState(info)
        return info.importance <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    /** Run a short database [block] with the lock held, so a check never sees a half-written follow. No network inside. */
    suspend fun <T> exclusive(block: suspend () -> T): T = dbLock.withLock { block() }

    private fun modeFor(sub: SubscriptionEntity) = if (sub.autoDownload == AutoDownload.VIDEO) EnqueueMode.VIDEO else EnqueueMode.AUDIO
}

internal class DaoFeedStore(
    private val dao: SubscriptionDao,
    /** Fresh etag / last-modified by subscription id; a subscription without an entry keeps its stored ones. */
    private val validators: Map<Long, Pair<String?, String?>> = emptyMap(),
) : FeedStore {
    override suspend fun itemKeys(subId: Long) = dao.itemKeys(subId).toHashSet()
    override suspend fun upsertItems(items: List<FeedItemEntity>) = dao.upsertItems(items)
    override suspend fun pendingItems(subId: Long) = dao.pendingItems(subId)
    override suspend fun markQueued(subId: Long, itemKey: String) = dao.markQueued(subId, itemKey)
    override suspend fun markChecked(sub: SubscriptionEntity, checkedAt: Long) = dao.markChecked(sub.id, validators[sub.id]?.first ?: sub.etag, validators[sub.id]?.second ?: sub.lastModified, checkedAt)
    override suspend fun trim(subId: Long, keep: Int) = dao.trimItems(subId, keep)
}
