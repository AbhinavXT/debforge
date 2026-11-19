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

    /** Longest file name, in UTF-8 bytes, extension included. Android file systems allow 255; this leaves room for suffixes. */
    const val MAX_NAME_BYTES = 200

    /**
     * "Title.ext": characters a file name cannot hold (and control characters) become spaces, and the whole name is
     * capped at [MAX_NAME_BYTES] UTF-8 bytes without splitting a code point.
     */
    fun fileName(title: String, url: String, mimeType: String? = null): String {
        val ext = "." + extension(url, mimeType)
        val clean = title.replace(Regex("[/\\\\:*?\"<>|\\p{Cntrl}]"), " ").replace(Regex("\\s+"), " ").trim()
        val capped = capBytes(clean, MAX_NAME_BYTES - ext.length).trim().trimEnd('.').trim()
        return "${capped.ifEmpty { "episode" }}$ext"
    }

    /** The longest prefix of [s] that is at most [max] UTF-8 bytes, cut on a code point boundary. */
    internal fun capBytes(s: String, max: Int): String {
        var bytes = 0
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val n = when { cp < 0x80 -> 1; cp < 0x800 -> 2; cp < 0x10000 -> 3; else -> 4 }
            if (bytes + n > max) break
            bytes += n
            i += Character.charCount(cp)
        }
        return s.substring(0, i)
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
    suspend fun enqueue(sub: SubscriptionEntity, item: FeedItemEntity, mimeType: String? = null): EnqueueResult {
        val url = item.mediaUrl?.takeIf { it.toHttpUrlOrNull() != null } ?: return EnqueueResult.NO_FORMAT
        val dlItem = itemFor(sub, item, url, mimeType)
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

    internal fun itemFor(sub: SubscriptionEntity, item: FeedItemEntity, url: String, mimeType: String? = null) = DownloadItem(
        id = DirectLinkParser.itemId(url), provider = ProviderId.DIRECT,
        filename = PodcastFiles.fileName(item.title, url, mimeType), sourceRef = url, downloadUrl = url,
        host = url.toHttpUrlOrNull()?.host ?: "Podcast", filesize = 0, maxConnections = 4,
        addedAt = java.time.Instant.now().toString(), title = item.title, artist = sub.title,
    )
}
