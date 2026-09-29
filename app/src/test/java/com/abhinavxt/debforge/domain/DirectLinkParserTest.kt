package com.abhinavxt.debforge.domain

import com.abhinavxt.debforge.domain.DirectLinkParser.Target
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectLinkParserTest {

    @Test fun pixeldrain() {
        assertEquals(Target.PixeldrainFile("abc123"), DirectLinkParser.classify("https://pixeldrain.com/u/abc123"))
        assertEquals(Target.PixeldrainFile("abc123"), DirectLinkParser.classify("https://pixeldrain.com/api/file/abc123?download"))
        assertEquals(Target.PixeldrainList("Xy9"), DirectLinkParser.classify("https://pixeldrain.com/l/Xy9#item=2"))
        assertEquals(Target.PixeldrainFile("q1"), DirectLinkParser.classify("https://pixeldra.in/u/q1"))
    }

    @Test fun googleDrive() {
        val id = "1AbC_dEf-GhIjKlMnOpQ"
        assertEquals(Target.DriveFile(id), DirectLinkParser.classify("https://drive.google.com/file/d/$id/view?usp=sharing"))
        assertEquals(Target.DriveFile(id), DirectLinkParser.classify("https://drive.google.com/open?id=$id"))
        assertEquals(Target.DriveFile(id), DirectLinkParser.classify("https://drive.google.com/uc?export=download&id=$id"))
        assertEquals(Target.DriveFile(id), DirectLinkParser.classify("https://drive.usercontent.google.com/download?id=$id&export=download"))
        assertEquals(Target.DriveFolder, DirectLinkParser.classify("https://drive.google.com/drive/folders/$id"))
    }

    @Test fun plainAndNotLinks() {
        val u = "https://video-downloads.googleusercontent.com/ADGPM2kOUq_x-y"
        assertEquals(Target.Plain(u), DirectLinkParser.classify(u))
        assertNull(DirectLinkParser.classify("magnet:?xt=urn:btih:abc"))
        assertNull(DirectLinkParser.classify("ftp://host/file.mkv"))
    }

    @Test fun whichLinksPreferDirect() {
        assertTrue(DirectLinkParser.prefersDirect("https://pixeldrain.com/u/abc"))
        assertTrue(DirectLinkParser.prefersDirect("https://drive.google.com/file/d/abc/view"))
        assertTrue(DirectLinkParser.prefersDirect("https://video-downloads.googleusercontent.com/ADGPM2k"))
        assertTrue(DirectLinkParser.prefersDirect("https://x.download.real-debrid.com/d/ABC/Movie%20(2026).mkv"))
        // Hoster pages go to the debrid service as before.
        assertFalse(DirectLinkParser.prefersDirect("https://1fichier.com/?abcdef"))
        assertFalse(DirectLinkParser.prefersDirect("https://rapidgator.net/file/abc/movie.mkv.html"))
        assertFalse(DirectLinkParser.prefersDirect("https://mega.nz/file/abc#key"))
    }

    @Test fun contentDisposition() {
        assertEquals("The Movie (2026).mkv",
            DirectLinkParser.filenameFromContentDisposition("attachment; filename=\"x.mkv\"; filename*=UTF-8''The%20Movie%20(2026).mkv"))
        assertEquals("a b.mp4", DirectLinkParser.filenameFromContentDisposition("attachment; filename=\"a b.mp4\""))
        assertEquals("plain.zip", DirectLinkParser.filenameFromContentDisposition("inline; filename=plain.zip"))
        assertEquals("evil.mkv", DirectLinkParser.filenameFromContentDisposition("attachment; filename=\"../../evil.mkv\""))
        assertNull(DirectLinkParser.filenameFromContentDisposition("attachment"))
        assertNull(DirectLinkParser.filenameFromContentDisposition(null))
    }

    @Test fun namesFromUrlAndType() {
        assertEquals("Movie (2026).mkv", DirectLinkParser.filenameFromUrl("https://h/d/X/Movie%20(2026).mkv?t=1"))
        assertNull(DirectLinkParser.filenameFromUrl("https://video-downloads.googleusercontent.com/ADGPM2k"))
        assertEquals("download-ab12.mp4", DirectLinkParser.fallbackName("video/mp4; codecs=avc1", "ab12"))
        assertEquals("download-ab12.bin", DirectLinkParser.fallbackName(null, "ab12"))
    }

    @Test fun webPagesAndRanges() {
        assertTrue(DirectLinkParser.isWebPage("text/html; charset=utf-8"))
        assertFalse(DirectLinkParser.isWebPage("video/mp4"))
        assertFalse(DirectLinkParser.isWebPage(null))
        assertEquals(12345L, DirectLinkParser.contentRangeTotal("bytes 0-0/12345"))
        assertNull(DirectLinkParser.contentRangeTotal("bytes 0-0/*"))
    }

    @Test fun driveVirusScanPage() {
        val html = """
            <html><body><p>Google Drive can't scan this file for viruses.</p>
            <form id="download-form" action="https://drive.usercontent.google.com/download" method="get">
              <input type="submit" id="uc-download-link" value="Download anyway"/>
              <input type="hidden" name="id" value="1AbC">
              <input type="hidden" name="export" value="download">
              <input type="hidden" name="confirm" value="t">
              <input type="hidden" name="uuid" value="5f0e-aa&amp;b">
            </form></body></html>
        """.trimIndent()
        assertEquals(
            "https://drive.usercontent.google.com/download?id=1AbC&export=download&confirm=t&uuid=5f0e-aa%26b",
            DirectLinkParser.driveConfirmUrl(html)
        )
        assertNull(DirectLinkParser.driveConfirmUrl("<html>Sorry, you can't view or download this file at this time.</html>"))
    }

    @Test fun stableIds() {
        val a = DirectLinkParser.itemId("pixeldrain:abc")
        assertEquals(a, DirectLinkParser.itemId("pixeldrain:abc"))
        assertFalse(a == DirectLinkParser.itemId("pixeldrain:abd"))
        assertTrue(a.startsWith("direct:") && a.length == "direct:".length + 20)
    }
}
