package com.abhinavxt.debforge.domain

import com.abhinavxt.debforge.data.provider.torbox.tbCleanDetail
import com.abhinavxt.debforge.data.provider.torbox.tbError
import com.abhinavxt.debforge.data.provider.torbox.tbRefusalDetail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NzbTest {

    private fun link(url: String) = AddInputParser.parse(url).requests.single()

    @Test fun nzbUrlLooksAtPathOnly() {
        assertTrue(Nzb.isNzbUrl("https://x.test/a/b.NZB"))
        assertTrue(Nzb.isNzbUrl("https://x.test/a/b.nzb?apikey=1&t=get"))
        assertFalse(Nzb.isNzbUrl("https://x.test/get?file=b.nzb"))
        assertFalse(Nzb.isNzbUrl("https://x.test/b.nzb.html"))
    }

    @Test fun nzbLinkGoesToUsenetOnlyOnTorBox() {
        val l = link("https://x.test/b.nzb")
        assertTrue(Nzb.route(l, ProviderId.TORBOX) is AddRequest.NzbLink)
        assertTrue(Nzb.route(l, ProviderId.REAL_DEBRID) is AddRequest.Link)
        val other = link("https://x.test/b.zip")
        assertTrue(Nzb.route(other, ProviderId.TORBOX) is AddRequest.Link)
        assertEquals(AddKind.NZB_LINK, Nzb.route(l, ProviderId.TORBOX).kind)
    }

    @Test fun sniffsNzbXmlWithoutParsing() {
        val ok = """<?xml version="1.0"?><!DOCTYPE nzb PUBLIC "x" "y"><nzb xmlns="z"></nzb>"""
        assertTrue(Nzb.looksLikeNzb(ok.toByteArray()))
        assertFalse(Nzb.looksLikeNzb("<html>nope</html>".toByteArray()))
        assertFalse(Nzb.looksLikeNzb(ByteArray(0)))
        assertFalse(Nzb.looksLikeNzb("d8:announce".toByteArray()))
    }

    @Test fun nameDetection() {
        assertTrue(Nzb.isNzbName("My.Show.nzb"))
        assertFalse(Nzb.isNzbName("a.torrent"))
        assertFalse(Nzb.isNzbName(null))
    }

    private val plan = """{"success":false,"error":"PLAN_RESTRICTED_FEATURE","detail":"You need the \"Pro\" plan.","data":null}"""

    @Test fun refusalUsesTorBoxTextForNonAuthErrors() {
        assertEquals("You need the \"Pro\" plan.", tbRefusalDetail(400, plan))
        // A 403 carrying a TorBox plan error is not a bad key.
        assertEquals("You need the \"Pro\" plan.", tbRefusalDetail(403, plan))
        assertNull(tbRefusalDetail(403, "<html>forbidden</html>"))
        assertNull(tbRefusalDetail(403, null))
    }

    @Test fun refusalKeepsFriendlyMessagesForAuthLimitsAndServerErrors() {
        val bad = """{"success":false,"error":"BAD_TOKEN","detail":"Invalid token"}"""
        assertNull(tbRefusalDetail(401, plan))
        assertNull(tbRefusalDetail(403, bad))
        assertNull(tbRefusalDetail(429, plan))
        assertNull(tbRefusalDetail(500, plan))
        assertNull(tbRefusalDetail(503, plan))
    }

    @Test fun detailIsCleaned() {
        assertEquals("a b c", tbCleanDetail("a\n\n  b\t\u0000c  "))
        assertEquals(200, tbCleanDetail("x".repeat(500))!!.length)
        assertNull(tbCleanDetail("  \n "))
        // JSON escapes are decoded by the parser.
        val e = tbError("""{"error":"X","detail":"line one\nline \"two\" \u00e9"}""")!!
        assertEquals("line one line \"two\" \u00e9", e.detail)
        assertNull(tbError("<html>502</html>"))
        assertNull(tbError(null))
    }

    @Test fun recognisesNotCachedAnswer() {
        val body = """{"success":false,"error":"DOWNLOAD_NOT_CACHED","detail":"Torrent not found in cache.","data":null}"""
        assertEquals("DOWNLOAD_NOT_CACHED", tbError(body)?.code)
        assertEquals("ACTIVE_LIMIT", tbError("""{"success":false,"error":"ACTIVE_LIMIT"}""")?.code)
    }

    @Test fun sniffAcceptsBomUtf16AndLongPreamble() {
        val xml = """<?xml version="1.0"?><nzb></nzb>"""
        assertTrue(Nzb.looksLikeNzb(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + xml.toByteArray()))
        assertTrue(Nzb.looksLikeNzb(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + xml.toByteArray(Charsets.UTF_16LE)))
        assertTrue(Nzb.looksLikeNzb(byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + xml.toByteArray(Charsets.UTF_16BE)))
        val longComment = "<!-- " + "x".repeat(40_000) + " -->"
        assertTrue(Nzb.looksLikeNzb((longComment + xml).toByteArray()))
        val tooFar = "<!-- " + "x".repeat(70_000) + " -->"
        assertFalse(Nzb.looksLikeNzb((tooFar + xml).toByteArray()))
    }

    @Test fun gzipIsRecognised() {
        assertTrue(Nzb.isGzip(byteArrayOf(0x1F, 0x8B.toByte(), 8)))
        assertFalse(Nzb.isGzip("<nzb".toByteArray()))
        assertFalse(Nzb.isGzip(ByteArray(1)))
    }

    @Test fun nzbCapIsFiftyMegabytes() {
        assertEquals(50L * 1024 * 1024, Nzb.MAX_BYTES)
    }

    @Test fun retryKeepsOnlyWhatWasNotAdded() {
        val text = "magnet:?xt=urn:btih:aa\nhttps://x.test/a.nzb <https://x.test/b> note\nmagnet:?xt=urn:btih:cc"
        val kept = AddInputParser.withoutAdded(text, setOf("magnet:?xt=urn:btih:aa", "https://x.test/b"))
        assertEquals("https://x.test/a.nzb\nnote\nmagnet:?xt=urn:btih:cc", kept)
        assertEquals("", AddInputParser.withoutAdded("magnet:?xt=urn:btih:aa", setOf("magnet:?xt=urn:btih:aa")))
        assertEquals(text.split(Regex("\\s+")).joinToString("\n"), AddInputParser.withoutAdded(text, emptySet()))
    }
}
