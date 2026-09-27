package com.abhinavxt.debforge.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RedactorTest {

    private fun r(s: String) = Redactor.redact(s)

    @Test fun queryTokensAndUrlPaths() {
        val out = r("java.io.IOException: GET https://api.torbox.app/v1/api/torrents/requestdl?token=abc123&torrent_id=5 failed")
        assertFalse(out.contains("abc123"))
        assertFalse(out.contains("requestdl"))
        assertTrue(out.contains("https://api.torbox.app/…"))
    }

    @Test fun bareTokenParam() = assertEquals("apikey=*** x", r("apikey=SECRETKEY x"))

    @Test fun bearerHeader() {
        assertFalse(r("Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.abc").contains("eyJ"))
        assertFalse(r("sent bearer 0123456789abcdef").contains("0123456789abcdef"))
    }

    @Test fun magnetAndHash() {
        val out = r("magnet:?xt=urn:btih:0123456789ABCDEF0123456789abcdef01234567&dn=Show")
        assertFalse(out.contains("0123456789ABCDEF"))
        assertEquals("id <hash> gone", r("id 0123456789abcdef0123456789abcdef01234567 gone"))
    }

    @Test fun storagePaths() {
        val out = r("FileNotFoundException: /storage/emulated/0/Movies/DebForge/Secret.Show.S01E01.mkv: open failed")
        assertFalse(out.contains("Secret"))
        assertTrue(out.contains("/storage/emulated/0/…: open failed"))
    }

    @Test fun stackFramesUntouched() {
        val frame = "\tat com.abhinavxt.debforge.download.ChunkedDownloader.run(ChunkedDownloader.kt:120)"
        assertEquals(frame, r(frame))
    }
}
