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
}
