package com.abhinavxt.debforge.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClipboardLinksTest {

    private val magnet = "magnet:?xt=urn:btih:DD8255ECDC7CA55FB0BBF81323D87062DB1F6D1C&dn=Big+Buck+Bunny"

    @Test fun plainMagnet() = assertEquals(magnet, ClipboardLinks.pick(magnet))

    @Test fun magnetInsideSentence() =
        assertEquals(magnet, ClipboardLinks.pick("here you go: $magnet."))

    @Test fun torrentUrl() {
        val url = "https://example.org/files/ubuntu-24.04.torrent"
        assertEquals(url, ClipboardLinks.pick(url))
        assertEquals("$url?dl=1", ClipboardLinks.pick("$url?dl=1"))
    }

    @Test fun ordinaryLinksAreIgnored() {
        assertNull(ClipboardLinks.pick("https://news.example.com/article/123"))
        assertNull(ClipboardLinks.pick("https://example.com/torrent-guide"))
    }

    @Test fun plainTextIsIgnored() {
        assertNull(ClipboardLinks.pick("hunter2"))
        assertNull(ClipboardLinks.pick(""))
        assertNull(ClipboardLinks.pick(null))
        assertNull(ClipboardLinks.pick("magnet:?dn=no-hash-here"))
    }

    @Test fun severalLinksDeduplicated() {
        val url = "https://example.org/a.torrent"
        assertEquals("$magnet\n$url", ClipboardLinks.pick("$magnet\n$url\n$magnet"))
    }

    @Test fun hugeTextIsIgnored() =
        assertNull(ClipboardLinks.pick(magnet + " ".repeat(ClipboardLinks.MAX_TEXT)))
}
