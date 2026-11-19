package com.abhinavxt.debforge.data.podcast

import com.abhinavxt.debforge.download.podcast.PodcastFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test fun trackIdIsStableSha1OfFeedAndGuid() {
        assertEquals("pod:" + java.security.MessageDigest.getInstance("SHA-1").digest("f\ng".toByteArray()).joinToString("") { "%02x".format(it) }, podcastTrackId("f", "g"))
        assertEquals(podcastTrackId("f", "abc"), podcastTrackId("f", "abc"))
        assertNotEquals(podcastTrackId("f", "abc"), podcastTrackId("f", "abd"))
        assertNotEquals(podcastTrackId("https://a.test/feed", "1"), podcastTrackId("https://b.test/feed", "1"))
    }

    @Test fun fileNameIsCappedInUtf8BytesWithoutSplittingCodePoints() {
        for (title in listOf("日本語のタイトル".repeat(40), "😀".repeat(200), "é".repeat(300), "a".repeat(400))) {
            val name = PodcastFiles.fileName(title, "https://cdn.test/a.mp3")
            assertTrue(name.toByteArray(Charsets.UTF_8).size <= PodcastFiles.MAX_NAME_BYTES)
            assertTrue(name.endsWith(".mp3"))
            assertFalse(name.contains('\uFFFD'))
            assertEquals(name, String(name.toByteArray(Charsets.UTF_8), Charsets.UTF_8))
        }
        assertEquals("😀".repeat(49) + ".mp3", PodcastFiles.fileName("😀".repeat(200), "https://cdn.test/a.mp3"))
    }

    @Test fun fileNameStripsControlCharacters() {
        assertEquals("a b.mp3", PodcastFiles.fileName("a\u0000\u0007\nb", "https://cdn.test/a.mp3"))
    }

    @Test fun fileNameUsesMimeTypeWhenUrlHasNoExtension() {
        assertEquals("T.m4a", PodcastFiles.fileName("T", "https://cdn.test/s/1", "audio/x-m4a"))
        assertEquals("T.mp4", PodcastFiles.fileName("T", "https://cdn.test/s/1", "video/mp4"))
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
        assertEquals(200, n.length)
    }
}
