package com.abhinavxt.debforge.domain

/**
 * The episodes that come after (or before) the one playing, for "Next
 * episode" and "Previous episode" in the player. Pure: unit-tested in
 * NextEpisodeTest.
 *
 * Candidates are the same show (same [ReleaseInfo.groupKey]) on the same
 * service, video files with an episode number. Each later episode appears
 * once; when several files carry it, the pick is:
 *  1. one from the same torrent as the current episode (same release),
 *  2. else the same resolution,
 *  3. else the largest file.
 */
object NextEpisode {

    data class Candidate(val item: DownloadItem, val info: ReleaseInfo)

    /** Sort key: episodes without a season (anime "E12") count as season 0. */
    private fun keyOf(info: ReleaseInfo): Pair<Int, Int>? =
        info.episode?.let { (info.season ?: 0) to it }

    private val order = compareBy<Pair<Int, Int>>({ it.first }, { it.second })

    fun upcoming(
        current: DownloadItem,
        currentInfo: ReleaseInfo,
        candidates: List<Candidate>,
        limit: Int = 10
    ): List<Candidate> = neighbours(current, currentInfo, candidates, limit, after = true)

    /** Episodes before the current one, nearest first. */
    fun previous(
        current: DownloadItem,
        currentInfo: ReleaseInfo,
        candidates: List<Candidate>,
        limit: Int = 10
    ): List<Candidate> = neighbours(current, currentInfo, candidates, limit, after = false)

    private fun neighbours(
        current: DownloadItem,
        currentInfo: ReleaseInfo,
        candidates: List<Candidate>,
        limit: Int,
        after: Boolean
    ): List<Candidate> {
        if (currentInfo.kind != MediaKind.SHOW) return emptyList()
        val here = keyOf(currentInfo) ?: return emptyList()
        return candidates
            .asSequence()
            .filter { c ->
                c.item.id != current.id &&
                    c.item.provider == current.provider &&
                    c.info.kind == MediaKind.SHOW &&
                    c.info.isVideo &&
                    c.info.groupKey == currentInfo.groupKey
            }
            .mapNotNull { c -> keyOf(c.info)?.let { it to c } }
            .filter { (k, _) -> if (after) order.compare(k, here) > 0 else order.compare(k, here) < 0 }
            .groupBy({ it.first }, { it.second })
            .toSortedMap(if (after) order else order.reversed())
            .values
            .map { copies ->
                copies.maxWithOrNull(
                    compareBy<Candidate>(
                        { current.parentRef != null && it.item.parentRef == current.parentRef },
                        { currentInfo.resolution != null && it.info.resolution.equals(currentInfo.resolution, ignoreCase = true) },
                        { it.item.filesize }
                    )
                )!!
            }
            .take(limit)
    }
}
