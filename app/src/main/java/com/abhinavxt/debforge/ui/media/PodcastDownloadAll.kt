package com.abhinavxt.debforge.ui.media

import com.abhinavxt.debforge.data.subscriptions.FeedItemEntity

/** More episodes than this to queue and "Download all" asks first. */
internal const val DOWNLOAD_ALL_CONFIRM_ABOVE = 20

/** Episodes "Download all" would queue: playable, and neither downloaded nor in progress. Failed ones are retried. Feed order is kept. */
internal fun episodesToQueue(episodes: List<EpisodeRow>): List<FeedItemEntity> =
    episodes.filter {
        (it.download == EpisodeDownload.NONE || it.download == EpisodeDownload.FAILED) && !it.item.mediaUrl.isNullOrBlank()
    }.map { it.item }

/** The episodes of [episodes] whose keys are in [keys], in feed order: what a confirm dialog opened earlier will queue. */
internal fun episodesByKeys(episodes: List<EpisodeRow>, keys: Collection<String>): List<FeedItemEntity> {
    val wanted = keys.toSet()
    return episodes.filter { it.item.itemKey in wanted }.map { it.item }
}

internal fun needsDownloadAllConfirm(count: Int): Boolean = count > DOWNLOAD_ALL_CONFIRM_ABOVE
