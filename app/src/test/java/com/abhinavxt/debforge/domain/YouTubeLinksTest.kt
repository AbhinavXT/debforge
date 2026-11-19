package com.abhinavxt.debforge.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeLinksTest {
    private val id = "dQw4w9WgXcQ"

    @Test fun urlShapes() {
        listOf(
            "https://www.youtube.com/watch?v=$id",
            "https://youtube.com/watch?v=$id&t=42s",
            "https://www.youtube.com/watch?list=PL123&v=$id",
            "https://m.youtube.com/watch?v=$id",
            "https://music.youtube.com/watch?v=$id&si=x",
            "https://youtu.be/$id?si=abc",
            "https://www.youtube.com/shorts/$id",
            "https://www.youtube.com/live/$id?feature=share",
            "https://www.youtube.com/embed/$id",
            "youtube.com/watch?v=$id",
        ).forEach { assertEquals(it, id, YouTubeLinks.videoId(it)) }
    }

    @Test fun sharedText() {
        assertEquals(id, YouTubeLinks.videoId("Some Title https://youtu.be/$id?si=x\nmore text"))
    }

    @Test fun notYouTube() {
        assertNull(YouTubeLinks.videoId("https://example.com/watch?v=$id"))
        assertNull(YouTubeLinks.videoId("https://notyoutube.com/watch?v=$id"))
        assertNull(YouTubeLinks.videoId("hello"))
    }

    @Test fun playlistOnly() {
        assertTrue(YouTubeLinks.isPlaylistOnly("https://www.youtube.com/playlist?list=PL123"))
        assertFalse(YouTubeLinks.isPlaylistOnly("https://www.youtube.com/watch?v=$id&list=PL123"))
        assertFalse(YouTubeLinks.isPlaylistOnly("https://example.com/?list=1"))
    }

    @Test fun playlistUrl() {
        assertEquals("https://www.youtube.com/playlist?list=PL-1_a", YouTubeLinks.playlistUrl("https://www.youtube.com/playlist?list=PL-1_a&si=x"))
        assertEquals("https://www.youtube.com/playlist?list=PL1", YouTubeLinks.playlistUrl("see https://m.youtube.com/watch?index=2&list=PL1"))
        assertNull(YouTubeLinks.playlistUrl("https://youtu.be/$id"))
    }

    @Test fun sourceRefRoundTrip() {
        val a = YouTubeLinks.sourceRef("a-b_c1234Zz", 299, 140)
        assertEquals("yt:a-b_c1234Zz:299:140", a)
        assertEquals(YouTubeLinks.Ref("a-b_c1234Zz", 299, 140), YouTubeLinks.parseSourceRef(a))
        val b = YouTubeLinks.sourceRef(id, null, 251)
        assertEquals("yt:$id:-:251", b)
        assertEquals(YouTubeLinks.Ref(id, null, 251), YouTubeLinks.parseSourceRef(b))
        assertNull(YouTubeLinks.parseSourceRef("magnet:?xt=1"))
        assertNull(YouTubeLinks.parseSourceRef("yt:$id:x:140"))
    }

    @Test fun searchQueryVsLink() {
        assertTrue(YouTubeLinks.isSearchQuery("big buck bunny"))
        assertTrue(YouTubeLinks.isSearchQuery("  lofi beats  "))
        assertFalse(YouTubeLinks.isSearchQuery("   "))
        assertFalse(YouTubeLinks.isSearchQuery("https://youtu.be/$id"))
        assertFalse(YouTubeLinks.isSearchQuery("watch this https://www.youtube.com/watch?v=$id"))
        assertFalse(YouTubeLinks.isSearchQuery("https://www.youtube.com/playlist?list=PL123"))
        assertFalse(YouTubeLinks.isSearchQuery("https://example.com/page"))
    }

    @Test fun historyOnlyForWordSearches() {
        assertEquals("lofi beats", YouTubeLinks.historyQuery("  lofi beats  "))
        assertNull(YouTubeLinks.historyQuery("   "))
        assertNull(YouTubeLinks.historyQuery("https://youtu.be/$id"))
        assertNull(YouTubeLinks.historyQuery("https://www.youtube.com/playlist?list=PL123"))
        assertNull(YouTubeLinks.historyQuery("https://www.youtube.com/@somechannel"))
        assertNull(YouTubeLinks.historyQuery("https://example.com/page"))
    }

    @Test fun watchUrlIsAlwaysSomethingTheResolverAccepts() {
        // A bare id (what the feed stores as the item key) is not a link; the watch URL built from it is.
        assertNull(YouTubeLinks.videoId(id))
        assertEquals(id, YouTubeLinks.videoId(YouTubeLinks.watchUrl(id)))
        assertEquals(id, YouTubeLinks.videoId(YouTubeLinks.watchUrl(id, null)))
        assertEquals(id, YouTubeLinks.videoId(YouTubeLinks.watchUrl(id, "")))
        assertEquals(id, YouTubeLinks.videoId(YouTubeLinks.watchUrl(id, "https://example.com/not-a-video")))
        assertEquals("https://youtu.be/$id", YouTubeLinks.watchUrl(id, "https://youtu.be/$id"))
    }

    @Test fun notALinkErrorIsNotAGoneVideo() {
        val e = com.abhinavxt.debforge.data.youtube.YouTubeException.NotAVideoLink()
        assertFalse(e is com.abhinavxt.debforge.data.youtube.YouTubeException.Unavailable)
    }

    @Test fun videoPages() {
        listOf(
            "https://www.youtube.com/watch?v=$id",
            "https://m.youtube.com/watch?v=$id&list=PL123",
            "https://music.youtube.com/watch?v=$id",
            "https://youtu.be/$id?si=x",
            "https://www.youtube.com/shorts/$id",
            "https://www.youtube.com/live/$id?feature=share",
            "https://www.youtube.com/watch?v=$id&pp=a|b",
            "https://www.youtube.com/watch?v=$id&q=two words",
        ).forEach { assertTrue(it, YouTubeLinks.isVideoPage(it)) }
    }

    @Test fun notVideoPages() {
        listOf(
            "https://www.youtube.com/",
            "https://www.youtube.com/@somechannel",
            "https://www.youtube.com/channel/UCabcdefghijklmnopqrstuv",
            "https://www.youtube.com/playlist?list=PL123",
            "https://www.youtube.com/watch?list=PL123",
            "https://www.youtube.com/watch?list=X&u=youtu.be/AAAAAAAAAAA",
            "https://www.youtube.com/results?search_query=youtu.be/AAAAAAAAAAA",
            "https://www.youtube.com/embed/$id",
            "https://youtu.be/",
            "https://www.youtube.com/results?search_query=$id",
            "https://example.com/watch?v=$id",
            "https://example.com/?u=youtube.com/watch?v=$id",
            "https://duckduckgo.com/",
            "not a url",
        ).forEach { assertFalse(it, YouTubeLinks.isVideoPage(it)) }
    }

    @Test fun pageVideoIdComesFromTheRightPlace() {
        assertEquals(id, YouTubeLinks.pageVideoId("https://www.youtube.com/watch?list=PL1&v=$id&u=youtu.be/AAAAAAAAAAA"))
        assertEquals(id, YouTubeLinks.pageVideoId("https://youtu.be/$id?si=x"))
        assertEquals(id, YouTubeLinks.pageVideoId("https://www.youtube.com/live/$id"))
        assertEquals(id, YouTubeLinks.pageVideoId("https://www.youtube.com/watch?pp=a|b&v=$id#t=1"))
        assertEquals(id, YouTubeLinks.pageVideoId("  https://m.youtube.com/shorts/$id/ "))
        assertNull(YouTubeLinks.pageVideoId("https://www.youtube.com/watch?x=v=$id"))
        assertNull(YouTubeLinks.pageVideoId("https://www.youtube.com/watch?v=short"))
    }

    @Test fun pageVideoIdResolvesToTheSameVideoThroughWatchUrl() {
        listOf(
            "https://www.youtube.com/watch?list=PL1&v=$id",
            "https://youtu.be/$id?si=x",
            "https://www.youtube.com/live/$id?feature=share",
            "https://www.youtube.com/watch?v=$id&pp=a|b",
        ).forEach { assertEquals(it, id, YouTubeLinks.videoId(YouTubeLinks.watchUrl(YouTubeLinks.pageVideoId(it)!!))) }
    }

    // A playlist's video entry stores a bare id; the resolver only accepts a link, so the id must go through watchUrl.
    @Test fun bareIdIsNotALinkButItsWatchUrlIs() {
        assertNull(YouTubeLinks.videoId("aqz-KE-bpKQ"))
        assertEquals("aqz-KE-bpKQ", YouTubeLinks.videoId(YouTubeLinks.watchUrl("aqz-KE-bpKQ")))
    }
}
