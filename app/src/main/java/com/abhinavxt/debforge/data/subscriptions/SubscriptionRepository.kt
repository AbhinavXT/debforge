package com.abhinavxt.debforge.data.subscriptions

import android.content.Context
import com.abhinavxt.debforge.data.follow.FollowStore
import com.abhinavxt.debforge.data.podcast.PodcastFeeds
import com.abhinavxt.debforge.data.podcast.PodcastFetchException
import com.abhinavxt.debforge.data.podcast.PodcastFetchResult
import com.abhinavxt.debforge.data.podcast.PodcastFetcher
import com.abhinavxt.debforge.data.youtube.YouTubeResolver
import com.abhinavxt.debforge.domain.FeedPlanner
import com.abhinavxt.debforge.domain.YouTubeChannelLinks
import com.abhinavxt.debforge.download.follow.FollowJobService
import com.abhinavxt.debforge.download.follow.SubscriptionChecker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/** Following and unfollowing YouTube channels and podcast feeds. */
@Singleton
class SubscriptionRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: SubscriptionDao,
    private val resolver: YouTubeResolver,
    private val followStore: FollowStore,
    private val checker: SubscriptionChecker,
    private val podcastFetcher: PodcastFetcher,
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
        // Under the checker's short database lock (never held across its network calls) so a running check never sees the subscription without its initial items.
        val sub = checker.exclusive {
            val id = dao.insert(
                SubscriptionEntity(
                    kind = SubscriptionKind.YT_CHANNEL, url = ref.url, title = ref.title, artworkUrl = ref.artworkUrl,
                    autoDownload = autoDownload, since = now, lastChecked = now,
                )
            )
            if (id < 0) return@exclusive null
            val created = checkNotNull(dao.get(id))
            dao.upsertItems(FeedPlanner.plan(created, emptySet(), entries, now, initial = true).items)
            created
        } ?: return FollowResult.AlreadyFollowing(checkNotNull(dao.getByUrl(ref.url)))
        syncJob()
        return FollowResult.Followed(sub)
    }

    /** The canonical url of the channel at [channelInput] (any channel link form): read from the link, or looked up for @handle, /c/ and /user/ links. Null when it cannot be resolved. */
    suspend fun canonicalChannelUrl(channelInput: String): String? {
        YouTubeChannelLinks.canonicalUrl(channelInput)?.let { return it }
        val target = YouTubeChannelLinks.resolvable(channelInput) ?: return null
        return try {
            resolver.channelRef(target).url
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /** The followed channel stored under the canonical [url], if any. */
    suspend fun followedChannel(url: String): SubscriptionEntity? = dao.getByUrl(url)

    suspend fun markSeen(subId: Long, itemKey: String) = dao.markSeen(subId, itemKey)

    suspend fun markAllSeen() = dao.markEverythingSeen()

    /**
     * Follows the podcast feed at [feedUrl] (normalised by [PodcastFeeds.normalizeUrl]); the episodes already in the
     * feed are recorded as seen, so only later ones are new or auto-downloaded. Throws [PodcastFetchException]
     * (with a user message) when the feed cannot be read.
     */
    suspend fun followPodcast(feedUrl: String, autoDownload: AutoDownload = AutoDownload.OFF, now: Long = System.currentTimeMillis()): FollowResult {
        val url = PodcastFeeds.normalizeUrl(feedUrl) ?: throw PodcastFetchException(PodcastFetchException.Kind.NOT_A_FEED)
        dao.getByUrl(url)?.let { return FollowResult.AlreadyFollowing(it) }
        val fetched = podcastFetcher.fetch(url) as? PodcastFetchResult.Fresh
            ?: throw PodcastFetchException(PodcastFetchException.Kind.NOT_A_FEED)
        val feed = fetched.feed
        val sub = checker.exclusive {
            val id = dao.insert(
                SubscriptionEntity(
                    kind = SubscriptionKind.PODCAST, url = url, title = feed.title.ifBlank { url.substringAfter("://").substringBefore('/') },
                    artworkUrl = feed.imageUrl, autoDownload = autoDownload, since = now,
                    etag = fetched.etag, lastModified = fetched.lastModified, lastChecked = now,
                )
            )
            if (id < 0) return@exclusive null
            val created = checkNotNull(dao.get(id))
            dao.upsertItems(FeedPlanner.plan(created, emptySet(), PodcastFeeds.entries(feed), now, initial = true).items)
            created
        } ?: return FollowResult.AlreadyFollowing(checkNotNull(dao.getByUrl(url)))
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
        FollowJobService.sync(context, followStore.all().isNotEmpty())
    }
}
