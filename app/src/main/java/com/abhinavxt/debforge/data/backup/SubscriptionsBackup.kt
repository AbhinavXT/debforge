package com.abhinavxt.debforge.data.backup

import com.abhinavxt.debforge.data.subscriptions.AutoDownload
import com.abhinavxt.debforge.data.subscriptions.SubscriptionEntity
import com.abhinavxt.debforge.data.subscriptions.SubscriptionKind
import com.abhinavxt.debforge.domain.YouTubeChannelLinks

/**
 * Followed YouTube channels and podcasts as the "subscriptions" section of a backup:
 * `{"v":1,"items":[{"k":"YT_CHANNEL"|"PODCAST","u":url,"t":title,"a":artworkUrl,"ad":"OFF"|"AUDIO"|"VIDEO"}]}`.
 * Feed items and sponsor segments are not included. Pure; the decoder never throws.
 */
object SubscriptionsBackup {
    /** Older versions reject backups over 256 KB; this section stays well below that. */
    const val MAX_EXPORT = 300
    private const val MAX_IMPORT = 1000

    data class Item(
        val kind: SubscriptionKind,
        val url: String,
        val title: String,
        val artworkUrl: String? = null,
        val autoDownload: AutoDownload = AutoDownload.OFF,
    )

    fun toJson(subs: List<SubscriptionEntity>): Map<String, Any?> = linkedMapOf(
        "v" to 1,
        "items" to subs.sortedBy { it.title.lowercase() }.take(MAX_EXPORT).map {
            linkedMapOf<String, Any?>(
                "k" to it.kind.name, "u" to it.url, "t" to it.title, "a" to it.artworkUrl, "ad" to it.autoDownload.name
            )
        }
    )

    fun fromJson(value: Any?): List<Item> {
        val m = value as? Map<*, *> ?: return emptyList()
        return (m["items"] as? List<*>).orEmpty().take(MAX_IMPORT).mapNotNull { e ->
            val o = e as? Map<*, *> ?: return@mapNotNull null
            val kind = (o["k"] as? String)?.let { n -> SubscriptionKind.entries.firstOrNull { it.name == n } } ?: return@mapNotNull null
            val raw = (o["u"] as? String)?.trim()?.takeIf { it.length in 1..2048 } ?: return@mapNotNull null
            val url = if (kind == SubscriptionKind.YT_CHANNEL) YouTubeChannelLinks.canonicalUrl(raw) ?: return@mapNotNull null
            else raw.takeIf { it.startsWith("https://") || it.startsWith("http://") } ?: return@mapNotNull null
            val title = (o["t"] as? String)?.trim()?.take(300)?.takeIf { it.isNotEmpty() } ?: url
            val art = (o["a"] as? String)?.takeIf { it.length <= 2048 && (it.startsWith("https://") || it.startsWith("http://")) }
            val auto = (o["ad"] as? String)?.let { n -> AutoDownload.entries.firstOrNull { it.name == n } } ?: AutoDownload.OFF
            Item(kind, url, title, art, auto)
        }.distinctBy { it.url }
    }

    /**
     * Entities to insert for [incoming], skipping urls in [existingUrls]. `since` = [now] and lastChecked = 0:
     * the planner only calls an entry new when it was published after `since`, so everything already on the
     * feed at restore time is recorded by the next check as not new, and nothing is auto-downloaded for it.
     */
    fun toInsert(incoming: List<Item>, existingUrls: Set<String>, now: Long): List<SubscriptionEntity> =
        incoming.filter { it.url !in existingUrls }.map {
            SubscriptionEntity(
                kind = it.kind, url = it.url, title = it.title, artworkUrl = it.artworkUrl,
                autoDownload = it.autoDownload, since = now, lastChecked = 0
            )
        }
}
