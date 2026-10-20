package com.abhinavxt.debforge.domain

/**
 * Picks which search results to send to the service for a show followed from
 * the Search tab. Pure: unit-tested in FollowSearchTest.
 */
object FollowSearch {

    data class Pick(val episodeKey: String, val result: SearchResult)

    /**
     * New episodes of [follow] among [results]:
     *  - a single episode of the followed show (season packs would pull in
     *    episodes the user already has);
     *  - listed by the tracker after the follow started (unknown date: skipped,
     *    "following" means from now on);
     *  - not handled yet ([Follow.seen]) nor already sent ([Follow.requested]).
     * One per episode: cached first, then the followed quality, then the most
     * seeders.
     */
    fun pick(follow: Follow, results: List<SearchResult>): List<Pick> =
        results
            .mapNotNull { r ->
                val info = r.info
                if (info.kind != MediaKind.SHOW || info.groupKey != follow.showKey) return@mapNotNull null
                val key = info.episodeLabel ?: return@mapNotNull null
                if (key in follow.seen || key in follow.requested) return@mapNotNull null
                val published = r.published ?: return@mapNotNull null
                if (published < follow.since) return@mapNotNull null
                key to r
            }
            .groupBy({ it.first }, { it.second })
            .map { (key, options) ->
                val best = options.sortedWith(
                    compareByDescending<SearchResult> { it.cached == true }
                        .thenByDescending { follow.resolution != null && it.info.resolution.equals(follow.resolution, ignoreCase = true) }
                        .thenByDescending { it.seeders ?: -1 }
                ).first()
                Pick(key, best)
            }
            .sortedBy { it.episodeKey }
}
