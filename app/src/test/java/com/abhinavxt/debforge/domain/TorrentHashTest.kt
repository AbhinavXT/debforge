package com.abhinavxt.debforge.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.security.MessageDigest

class TorrentHashTest {

    @Test fun hexMagnet() = assertEquals(
        "dd8255ecdc7ca55fb0bbf81323d87062db1f6d1c",
        TorrentHash.fromMagnet("magnet:?xt=urn:btih:DD8255ECDC7CA55FB0BBF81323D87062DB1F6D1C&dn=Big+Buck+Bunny")
    )

    // Same hash in base32 (RFC 4648) form.
    @Test fun base32Magnet() = assertEquals(
        "dd8255ecdc7ca55fb0bbf81323d87062db1f6d1c",
        TorrentHash.fromMagnet("magnet:?dn=x&xt=urn:btih:3WBFL3G4PSSV7MF37AJSHWDQMLNR63I4")
    )

    @Test fun notAMagnet() = assertNull(TorrentHash.fromMagnet("https://example.com/file.zip"))

    @Test fun torrentFileHashesInfoDict() {
        val info = "d6:lengthi12345e4:name8:file.mkv12:piece lengthi16384e6:pieces20:AAAAAAAAAAAAAAAAAAAAe"
        val torrent = "d8:announce23:http://tracker/announce13:creation datei1700000000e4:info${info}e"
        val expected = MessageDigest.getInstance("SHA-1").digest(info.toByteArray(Charsets.ISO_8859_1))
            .joinToString("") { "%02x".format(it) }
        assertEquals(expected, TorrentHash.fromTorrentFile(torrent.toByteArray(Charsets.ISO_8859_1)))
        assertEquals("4df2c51932697a3d64f6b9d47d236e3b9be1ceaf", expected)
    }

    @Test fun garbageTorrent() {
        assertNull(TorrentHash.fromTorrentFile("not bencode".toByteArray()))
        assertNull(TorrentHash.fromTorrentFile("d4:infoi1".toByteArray()))
    }
}
