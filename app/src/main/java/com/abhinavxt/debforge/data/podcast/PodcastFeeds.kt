package com.abhinavxt.debforge.data.podcast

import com.abhinavxt.debforge.domain.FeedEntry
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.security.MessageDigest

/** Pure helpers shared by following, the background check and (later) the UI. */
object PodcastFeeds {
    /**
     * The url a feed is stored and compared under: trimmed, `feed:` / `feed://` rewritten to https, https assumed
     * when no scheme was typed, host lowercased and the fragment dropped. The given http / https scheme is kept.
     * Null when the input is not an http(s) url.
     */
    fun normalizeUrl(input: String): String? {
        var s = input.trim()
        if (s.isEmpty() || s.any { it.isWhitespace() }) return null
        s = when {
            s.startsWith("feed://", ignoreCase = true) -> "https://" + s.substring(7)
            s.startsWith("feed:", ignoreCase = true) -> s.substring(5)
            else -> s
        }
        if (!s.contains("://")) s = "https://$s"
        val url: HttpUrl = s.toHttpUrlOrNull() ?: return null
        return url.newBuilder().fragment(null).build().toString()
    }

    /** The feed's episodes as planner entries: item key = guid, thumbnail = episode image or the feed's. */
    fun entries(feed: PodcastFeed): List<FeedEntry> = feed.episodes.map {
        FeedEntry(
            key = it.guid, title = it.title, url = it.enclosureUrl.ifBlank { null },
            publishedAt = it.publishedAtMillis.takeIf { t -> t > 0 },
            durationSec = it.durationSeconds?.toLong(), thumbUrl = it.imageUrl ?: feed.imageUrl,
        )
    }
}

/** Stable track id of an episode, used by the player and the queue: `pod:` + sha1 of its guid. */
fun podcastTrackId(guid: String): String =
    "pod:" + MessageDigest.getInstance("SHA-1").digest(guid.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
