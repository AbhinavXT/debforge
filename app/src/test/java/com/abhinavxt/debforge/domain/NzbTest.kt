package com.abhinavxt.debforge.domain

import com.abhinavxt.debforge.data.provider.torbox.tbIsNotCached
import com.abhinavxt.debforge.data.provider.torbox.tbRejectionDetail
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

    @Test fun readsTorBoxErrorDetail() {
        val body = """{"success":false,"error":"PLAN_RESTRICTED_FEATURE","detail":"You need the \"Pro\" plan.","data":null}"""
        assertEquals("You need the \"Pro\" plan.", tbRejectionDetail(body))
        assertNull(tbRejectionDetail("<html>502</html>"))
        assertNull(tbRejectionDetail(null))
    }

    @Test fun recognisesNotCachedAnswer() {
        val body = """{"success":false,"error":"DOWNLOAD_NOT_CACHED","detail":"Torrent not found in cache.","data":null}"""
        assertTrue(tbIsNotCached(body))
        assertFalse(tbIsNotCached("""{"success":false,"error":"ACTIVE_LIMIT"}"""))
        assertFalse(tbIsNotCached(null))
    }
}
