package com.abhinavxt.debforge.ui.media

import com.abhinavxt.debforge.data.subscriptions.FeedItemEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PodcastDownloadAllTest {
    private fun ep(key: String, state: EpisodeDownload = EpisodeDownload.NONE, url: String? = "https://x/$key.mp3") =
        EpisodeRow(
            FeedItemEntity(subId = 1, itemKey = key, title = key, mediaUrl = url, publishedAt = 0, isNew = false, queued = false),
            state
        )

    @Test fun skipsDoneAndActive() {
        val rows = listOf(ep("a"), ep("b", EpisodeDownload.DONE), ep("c", EpisodeDownload.ACTIVE), ep("d"))
        assertEquals(listOf("a", "d"), episodesToQueue(rows).map { it.itemKey })
    }

    @Test fun keepsFeedOrder() {
        val rows = listOf(ep("z"), ep("m"), ep("a"))
        assertEquals(listOf("z", "m", "a"), episodesToQueue(rows).map { it.itemKey })
    }

    @Test fun skipsEpisodesWithoutAudio() {
        assertEquals(listOf("a"), episodesToQueue(listOf(ep("a"), ep("b", url = null), ep("c", url = " "))).map { it.itemKey })
    }

    @Test fun emptyWhenNothingLeft() {
        assertTrue(episodesToQueue(listOf(ep("a", EpisodeDownload.DONE))).isEmpty())
        assertTrue(episodesToQueue(emptyList()).isEmpty())
    }

    @Test fun confirmsOnlyAboveTwenty() {
        assertFalse(needsDownloadAllConfirm(0))
        assertFalse(needsDownloadAllConfirm(20))
        assertTrue(needsDownloadAllConfirm(21))
    }

    @Test fun retriesFailedEpisodes() {
        val rows = listOf(ep("a", EpisodeDownload.FAILED), ep("b", EpisodeDownload.DONE), ep("c", EpisodeDownload.FAILED, url = null))
        assertEquals(listOf("a"), episodesToQueue(rows).map { it.itemKey })
    }

    @Test fun failedOnlyKeepsButtonEnabled() {
        assertEquals(2, episodesToQueue(listOf(ep("a", EpisodeDownload.FAILED), ep("b", EpisodeDownload.FAILED))).size)
    }

    @Test fun snapshotByKeysIgnoresLaterChanges() {
        val shown = listOf(ep("a"), ep("b"))
        val keys = episodesToQueue(shown).map { it.itemKey }
        val later = listOf(ep("c"), ep("b", EpisodeDownload.ACTIVE), ep("a"))
        assertEquals(listOf("b", "a"), episodesByKeys(later, keys).map { it.itemKey })
        assertTrue(episodesByKeys(later, emptyList()).isEmpty())
    }
}
