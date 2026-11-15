package com.abhinavxt.debforge.ui.browser

import com.abhinavxt.debforge.ui.browse.withoutOwnFilename
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AddressPartsTest {
    @Test fun userinfoNeverShown() {
        val p = addressParts("https://good.com@evil.com/")
        assertEquals("evil.com", p.host); assertEquals("", p.tail); assertFalse(p.warn)
    }

    @Test fun longHostKeptWhole() {
        val p = addressParts("https://accounts.google.com.evil.example/login")
        assertEquals("accounts.google.com.evil.example", p.host); assertEquals("/login", p.tail)
    }

    @Test fun percentEncodedHost() {
        val p = addressParts("https://ex%61mple.com/a")
        assertEquals("example.com", p.host); assertEquals("/a", p.tail)
    }

    @Test fun ipAndPort() {
        assertEquals("192.168.1.1", addressParts("http://192.168.1.1/x").host)
        assertEquals("[::1]:8080", addressParts("https://[::1]:8080/x?q=1").host)
        assertEquals("/x?q=1", addressParts("https://[::1]:8080/x?q=1").tail)
        assertEquals("site.org:8443", addressParts("https://site.org:8443").host)
    }

    @Test fun httpWarns() {
        assertTrue(addressParts("http://x.org").warn)
        assertFalse(addressParts("https://x.org").warn)
    }

    @Test fun punycodeShownAsGiven() {
        assertEquals("xn--e1afmkfd.xn--p1ai", addressParts("https://xn--e1afmkfd.xn--p1ai/").host)
    }

    @Test fun noHostCases() {
        for (u in listOf("about:blank", "data:text/html,hi", "file:///a", "", "https://", "http:///x", "ht tp://a", "https://a b.com/")) {
            val p = addressParts(u)
            assertNull(u, p.host); assertEquals(u, p.tail); assertFalse(u, p.warn)
        }
    }

    @Test fun filenameSegmentOnly() {
        assertEquals("Torrent · Show", withoutOwnFilename("Torrent · Show · a.mkv", "a.mkv"))
        assertEquals("zip of 3 files", withoutOwnFilename("zip of 3 files", "a.mkv"))
        assertEquals("Cloud · Folder", withoutOwnFilename("Cloud · Folder", "a.mkv"))
        assertEquals("", withoutOwnFilename("a.mkv", "a.mkv"))
    }
}
