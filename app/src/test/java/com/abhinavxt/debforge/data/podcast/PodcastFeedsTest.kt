package com.abhinavxt.debforge.data.podcast

import com.abhinavxt.debforge.download.podcast.PodcastFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PodcastFeedsTest {
    @Test fun normalizeTrimsLowercasesHostAndDropsFragment() {
        assertEquals("https://example.com/Feed.xml?x=1", PodcastFeeds.normalizeUrl("  https://EXAMPLE.com/Feed.xml?x=1#top \n"))
    }

    @Test fun normalizeKeepsGivenScheme() {
        assertEquals("http://example.com/rss", PodcastFeeds.normalizeUrl("http://Example.com/rss"))
    }

    @Test fun normalizeHandlesFeedSchemesAndBareHosts() {
        assertEquals("https://example.com/rss", PodcastFeeds.normalizeUrl("feed://example.com/rss"))
        assertEquals("https://example.com/rss", PodcastFeeds.normalizeUrl("feed:https://example.com/rss"))
        assertEquals("https://example.com/rss", PodcastFeeds.normalizeUrl("example.com/rss"))
    }

    @Test fun normalizeRejectsGarbage() {
        assertNull(PodcastFeeds.normalizeUrl(""))
        assertNull(PodcastFeeds.normalizeUrl("   "))
        assertNull(PodcastFeeds.normalizeUrl("two words"))
        assertNull(PodcastFeeds.normalizeUrl("ftp://example.com/rss"))
    }

    @Test fun entriesMapEpisodes() {
        val feed = PodcastFeed(
            "Show", "", "https://img/feed.jpg", null,
            listOf(
                PodcastEpisode("g1", "One", "", "https://x/1.mp3", "audio/mpeg", null, 5000, 90, "https://img/1.jpg"),
                PodcastEpisode("g2", "Two", "", "https://x/2.mp3", null, null, 0, null, null),
            ),
        )
        val e = PodcastFeeds.entries(feed)
        assertEquals("g1", e[0].key)
        assertEquals("https://x/1.mp3", e[0].url)
        assertEquals(5000L, e[0].publishedAt)
        assertEquals(90L, e[0].durationSec)
        assertEquals("https://img/1.jpg", e[0].thumbUrl)
        assertNull(e[1].publishedAt)
        assertEquals("https://img/feed.jpg", e[1].thumbUrl)
    }

    @Test fun trackIdIsStableSha1() {
        assertEquals("pod:da39a3ee5e6b4b0d3255bfef95601890afd80709", podcastTrackId(""))
        assertEquals(podcastTrackId("abc"), podcastTrackId("abc"))
        assertNotEquals(podcastTrackId("abc"), podcastTrackId("abd"))
    }

    @Test fun fileNameUsesUrlExtension() {
        assertEquals("Ep 1 Hello.m4a", PodcastFiles.fileName("Ep 1: Hello?", "https://cdn.test/a/b.M4A?token=1"))
    }

    @Test fun fileNameFallsBackToTypeThenMp3() {
        assertEquals("T.ogg", PodcastFiles.fileName("T", "https://cdn.test/stream/123", "audio/ogg; codecs=vorbis"))
        assertEquals("T.mp3", PodcastFiles.fileName("T", "https://cdn.test/stream/123", null))
        assertEquals("episode.mp3", PodcastFiles.fileName("  ///  ", "https://cdn.test/x"))
    }

    @Test fun fileNameCapsLongTitles() {
        val n = PodcastFiles.fileName("a".repeat(300), "https://cdn.test/x.mp3")
        assertEquals(124, n.length)
    }
}
