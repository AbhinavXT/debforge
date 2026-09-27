package com.abhinavxt.debforge.domain

import java.time.Instant
import java.time.OffsetDateTime

/**
 * A show the user follows on one service: new episodes that show up in that
 * account are downloaded automatically.
 *
 * [showKey] is [ReleaseInfo.groupKey] ("show:the bear"), the same key the
 * Library groups by, so following works however release groups spell the
 * name. [resolution] is the quality the user had when they followed
 * ("1080p"), preferred when an episode comes in several. [seen] holds
 * episode keys already handled (queued, or skipped as already downloaded),
 * so nothing is queued twice.
 */
data class Follow(
    val provider: ProviderId,
    val showKey: String,
    val title: String,
    val since: Long,
    val resolution: String? = null,
    val seen: Set<String> = emptySet()
)

/** Picks what to queue for followed shows. Pure: unit-tested in FollowMatcherTest. */
object FollowMatcher {

    data class Match(val follow: Follow, val item: DownloadItem, val episodeKey: String, val label: String)

    /** "S02E05", or "E12" for shows numbered by episode only; null without an episode number. */
    fun episodeKey(info: ReleaseInfo): String? = info.episodeLabel

    /**
     * New episodes in [items] for [follows]:
     *  - same service, parsed as an episode of the followed show, a video;
     *  - added to the account after the follow started (older ones the user
     *    can download by hand; following means "from now on");
     *  - an episode not handled before, and not already tracked by the
     *    download queue ([trackedIds]);
     *  - one file per episode: the followed quality if there is one, else the
     *    largest.
     *
     * [items] should already be without torrent extras (see [ExtraFiles]),
     * so samples never count as the episode.
     */
    fun match(
        follows: List<Follow>,
        items: List<DownloadItem>,
        parse: (DownloadItem) -> ReleaseInfo,
        trackedIds: Set<String>
    ): List<Match> {
        if (follows.isEmpty()) return emptyList()
        val byKey = follows.associateBy { it.provider to it.showKey }
        val candidates = HashMap<Pair<Follow, String>, MutableList<Pair<DownloadItem, ReleaseInfo>>>()
        for (item in items) {
            if (item.id in trackedIds) continue
            val info = parse(item)
            if (info.kind != MediaKind.SHOW || !info.isVideo) continue
            val follow = byKey[item.provider to info.groupKey] ?: continue
            val added = addedMillis(item.addedAt) ?: continue // unknown age: don't guess
            if (added < follow.since) continue
            val key = episodeKey(info) ?: continue
            if (key in follow.seen) continue
            candidates.getOrPut(follow to key) { mutableListOf() } += item to info
        }
        return candidates.map { (fk, options) ->
            val (follow, key) = fk
            val (item, info) = options
                .filter { (_, i) -> follow.resolution != null && i.resolution.equals(follow.resolution, ignoreCase = true) }
                .maxByOrNull { (it, _) -> it.filesize }
                ?: options.maxBy { (it, _) -> it.filesize }
            Match(follow, item, key, info.displayTitle)
        }.sortedWith(compareBy({ it.follow.title }, { it.episodeKey }))
    }

    /** ISO-8601 "addedAt" as epoch millis; null if missing or unreadable. */
    fun addedMillis(addedAt: String?): Long? {
        if (addedAt.isNullOrBlank()) return null
        // "…Z" (most services) or "…+00:00" (offsets, e.g. TorBox).
        return runCatching { Instant.parse(addedAt).toEpochMilli() }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(addedAt).toInstant().toEpochMilli() }.getOrNull()
    }
}
