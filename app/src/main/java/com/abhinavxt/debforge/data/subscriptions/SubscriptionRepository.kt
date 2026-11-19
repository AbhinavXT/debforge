package com.abhinavxt.debforge.data.subscriptions

import android.content.Context
import com.abhinavxt.debforge.data.follow.FollowStore
import com.abhinavxt.debforge.data.youtube.YouTubeResolver
import com.abhinavxt.debforge.domain.FeedPlanner
import com.abhinavxt.debforge.domain.YouTubeChannelLinks
import com.abhinavxt.debforge.download.follow.FollowJobService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/** Following and unfollowing YouTube channels. Podcasts are added by a later task. */
@Singleton
class SubscriptionRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: SubscriptionDao,
    private val resolver: YouTubeResolver,
    private val followStore: FollowStore,
) {
    sealed class FollowResult {
        class Followed(val sub: SubscriptionEntity) : FollowResult()
        class AlreadyFollowing(val sub: SubscriptionEntity) : FollowResult()
    }

    val subscriptions: Flow<List<SubscriptionEntity>> get() = dao.observeAll()
    fun newItems(limit: Int = 30): Flow<List<NewFeedItem>> = dao.observeNew(limit)
    val unseenCount: Flow<Int> get() = dao.observeUnseenCount()

    suspend fun isFollowing(channelUrl: String): Boolean {
        val url = YouTubeChannelLinks.canonicalUrl(channelUrl) ?: return false
        return dao.getByUrl(url) != null
    }

    /**
     * Follows the channel at [channelInput] (a channel link, @handle, /c/ or /user/ link, or a bare UC… id),
     * stored under its canonical url so the same channel is never followed twice. Records the existing uploads as
     * already seen, so only later uploads are new or auto-downloaded. Throws if the channel or its feed cannot be read.
     */
    suspend fun follow(channelInput: String, autoDownload: AutoDownload = AutoDownload.OFF, now: Long = System.currentTimeMillis()): FollowResult {
        // Skip the network when a plain channel link already names a followed channel.
        YouTubeChannelLinks.canonicalUrl(channelInput)?.let { url -> dao.getByUrl(url)?.let { return FollowResult.AlreadyFollowing(it) } }
        val target = YouTubeChannelLinks.resolvable(channelInput)
            ?: throw IllegalArgumentException("Not a YouTube channel link")
        val ref = resolver.channelRef(target)
        dao.getByUrl(ref.url)?.let { return FollowResult.AlreadyFollowing(it) }
        val entries = resolver.channelFeed(ref.url)
        val id = dao.insert(
            SubscriptionEntity(
                kind = SubscriptionKind.YT_CHANNEL, url = ref.url, title = ref.title, artworkUrl = ref.artworkUrl,
                autoDownload = autoDownload, since = now, lastChecked = now,
            )
        )
        if (id < 0) return FollowResult.AlreadyFollowing(checkNotNull(dao.getByUrl(ref.url)))
        val sub = checkNotNull(dao.get(id))
        dao.upsertItems(FeedPlanner.plan(sub, emptySet(), entries, now, initial = true).items)
        syncJob()
        return FollowResult.Followed(sub)
    }

    suspend fun unfollow(id: Long) {
        dao.delete(id)
        syncJob()
    }

    suspend fun setAutoDownload(id: Long, mode: AutoDownload) = dao.setAutoDownload(id, mode)

    /** Schedules or cancels the periodic check for the current set of follows. */
    suspend fun syncJob() {
        FollowJobService.subscriptionsActive = dao.all().isNotEmpty()
        FollowJobService.sync(context, followStore.all().isNotEmpty())
    }
}
