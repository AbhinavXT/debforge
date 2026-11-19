package com.abhinavxt.debforge.download.podcast

import com.abhinavxt.debforge.data.local.DownloadDao
import com.abhinavxt.debforge.data.subscriptions.FeedItemEntity
import com.abhinavxt.debforge.data.subscriptions.SubscriptionEntity
import com.abhinavxt.debforge.domain.DirectLinkParser
import com.abhinavxt.debforge.domain.DownloadItem
import com.abhinavxt.debforge.domain.ProviderId
import com.abhinavxt.debforge.download.DownloadController
import com.abhinavxt.debforge.download.youtube.EnqueueResult
import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import javax.inject.Inject
import javax.inject.Singleton

/** Download file names for podcast episodes. Pure. */
object PodcastFiles {
    private val KNOWN = setOf("mp3", "m4a", "aac", "ogg", "oga", "opus", "wav", "flac", "mp4", "m4v", "webm", "mov")
    private val BY_TYPE = mapOf(
        "audio/mpeg" to "mp3", "audio/mp3" to "mp3", "audio/mp4" to "m4a", "audio/x-m4a" to "m4a", "audio/m4a" to "m4a",
        "audio/aac" to "aac", "audio/ogg" to "ogg", "audio/opus" to "opus", "audio/wav" to "wav", "audio/x-wav" to "wav",
        "audio/flac" to "flac", "video/mp4" to "mp4", "video/x-m4v" to "m4v", "video/webm" to "webm",
    )

    /** The extension from the url's path when it is a known media one, else from [mimeType], else mp3. */
    fun extension(url: String, mimeType: String?): String {
        val last = url.toHttpUrlOrNull()?.pathSegments?.lastOrNull().orEmpty()
        val fromUrl = last.substringAfterLast('.', "").lowercase()
        if (fromUrl in KNOWN) return fromUrl
        return BY_TYPE[mimeType?.substringBefore(';')?.trim()?.lowercase()] ?: "mp3"
    }

    /** "Title.ext": characters a file name cannot hold become spaces, the title is capped at 120 chars. */
    fun fileName(title: String, url: String, mimeType: String? = null): String {
        val clean = title.replace(Regex("[/\\\\:*?\"<>|\\p{Cntrl}]"), " ").replace(Regex("\\s+"), " ").trim().take(120).trim().trimEnd('.')
        return "${clean.ifEmpty { "episode" }}.${extension(url, mimeType)}"
    }
}

/**
 * Queues a podcast episode's enclosure as a plain URL download through [DownloadController], which applies the
 * Wi-Fi, charging, window and storage rules. Needs no UI, so the background checker uses it.
 */
@Singleton
class PodcastEnqueuer @Inject constructor(
    private val downloads: DownloadController,
    private val dao: DownloadDao,
) {
    /** QUEUED, ALREADY_QUEUED, FAILED (retry later) or NO_FORMAT (no usable enclosure url; retrying cannot help). */
    suspend fun enqueue(sub: SubscriptionEntity, item: FeedItemEntity): EnqueueResult {
        val url = item.mediaUrl?.takeIf { it.toHttpUrlOrNull() != null } ?: return EnqueueResult.NO_FORMAT
        val dlItem = itemFor(sub, item, url)
        return try {
            if (dao.getById(dlItem.id) != null) return EnqueueResult.ALREADY_QUEUED
            downloads.enqueue(dlItem, foreground = false)
            EnqueueResult.QUEUED
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            EnqueueResult.FAILED
        }
    }

    internal fun itemFor(sub: SubscriptionEntity, item: FeedItemEntity, url: String) = DownloadItem(
        id = DirectLinkParser.itemId(url), provider = ProviderId.DIRECT,
        filename = PodcastFiles.fileName(item.title, url), sourceRef = url, downloadUrl = url,
        host = url.toHttpUrlOrNull()?.host ?: "Podcast", filesize = 0, maxConnections = 4,
        addedAt = java.time.Instant.now().toString(), title = item.title, artist = sub.title,
    )
}
