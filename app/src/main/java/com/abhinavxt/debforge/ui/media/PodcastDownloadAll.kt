package com.abhinavxt.debforge.ui.media

import com.abhinavxt.debforge.data.subscriptions.FeedItemEntity

/** More episodes than this to queue and "Download all" asks first. */
internal const val DOWNLOAD_ALL_CONFIRM_ABOVE = 20

/** Episodes "Download all" would queue: playable, and neither downloaded nor already queued. Feed order is kept. */
internal fun episodesToQueue(episodes: List<EpisodeRow>): List<FeedItemEntity> =
    episodes.filter { it.download == EpisodeDownload.NONE && !it.item.mediaUrl.isNullOrBlank() }.map { it.item }

internal fun needsDownloadAllConfirm(count: Int): Boolean = count > DOWNLOAD_ALL_CONFIRM_ABOVE
