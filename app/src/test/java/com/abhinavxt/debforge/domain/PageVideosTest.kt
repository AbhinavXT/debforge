package com.abhinavxt.debforge.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageVideosTest {

    private val page = "https://example.com/watch/episode-1"

    @Test fun videoAndSourceTags() {
        val html = """
            <video src="/media/main.mp4" controls></video>
            <video><source src='clip.webm' type="video/webm"><source src="clip.mp4"></video>
            <picture><source srcset="poster.webp" type="image/webp"></picture>
        """
        assertEquals(
            listOf("https://example.com/media/main.mp4", "https://example.com/watch/clip.webm", "https://example.com/watch/clip.mp4"),
            PageVideos.find(html, page).videos
        )
    }

    @Test fun metaTagsNameAVideoWithoutExtension() {
        val html = """<meta property="og:video:secure_url" content="https://cdn.example.com/v/abc123?sig=x&amp;e=1">"""
        assertEquals(listOf("https://cdn.example.com/v/abc123?sig=x&e=1"), PageVideos.find(html, page).videos)
    }

    @Test fun linksOnlyWhenTheyPointAtAVideoFile() {
        val html = """<a href="ep2">Next</a><a href="files/Show.S01E01.mkv">Download</a><a href="cover.jpg">Cover</a>"""
        assertEquals(listOf("https://example.com/watch/files/Show.S01E01.mkv"), PageVideos.find(html, page).videos)
    }

    @Test fun urlsInScriptsIncludingEscapedJson() {
        val html = """<script>var cfg = {"file":"https:\/\/cdn.example.com\/hd\/movie.mp4","poster":"https:\/\/cdn.example.com\/p.jpg"};</script>"""
        assertEquals(listOf("https://cdn.example.com/hd/movie.mp4"), PageVideos.find(html, page).videos)
    }

    @Test fun streamsAreCountedNotOffered() {
        val html = """<video src="https://cdn.example.com/hls/master.m3u8"></video><script>x="https://cdn.example.com/d/manifest.mpd"</script>"""
        val found = PageVideos.find(html, page)
        assertTrue(found.videos.isEmpty())
        assertEquals(2, found.streams)
    }

    @Test fun duplicatesFragmentsAndJunkDropped() {
        val html = """
            <video src="a.mp4#t=10"></video><a href="a.mp4">same</a>
            <video src="blob:https://example.com/123"></video><a href="javascript:void(0)">x</a><a href="#top">top</a>
        """
        assertEquals(listOf("https://example.com/watch/a.mp4"), PageVideos.find(html, page).videos)
    }

    @Test fun baseTagChangesResolving() {
        val html = """<base href="https://static.example.org/v/"><a href="one.mp4">1</a>"""
        assertEquals(listOf("https://static.example.org/v/one.mp4"), PageVideos.find(html, page).videos)
    }

    @Test fun title() {
        assertEquals("Episode 1 & more", PageVideos.find("<title>\n Episode 1 &amp; more </title>", page).title)
        assertEquals("OG", PageVideos.find("""<title>T</title><meta property="og:title" content="OG">""", page).title)
        assertNull(PageVideos.find("<p>nothing</p>", page).title)
    }

    @Test fun isVideo() {
        assertTrue(PageVideos.isVideo("x.mkv", null))
        assertTrue(PageVideos.isVideo(null, "video/mp4; codecs=avc1"))
        assertFalse(PageVideos.isVideo("x.jpg", "image/jpeg"))
        assertFalse(PageVideos.isVideo(null, null))
    }

    @Test fun sniffable() {
        assertTrue(PageVideos.isSniffable("https://cdn.example.com/v/movie.mp4?token=1"))
        assertFalse(PageVideos.isSniffable("https://cdn.example.com/hls/seg-12.ts"))
        assertFalse(PageVideos.isSniffable("https://cdn.example.com/hls/index.m3u8"))
        assertFalse(PageVideos.isSniffable("https://example.com/watch?v=a.mp4"))
    }
}
