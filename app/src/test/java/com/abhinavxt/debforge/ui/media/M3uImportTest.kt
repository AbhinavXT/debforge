package com.abhinavxt.debforge.ui.media

import org.junit.Assert.assertEquals
import org.junit.Test

class M3uImportTest {
    private val tracks = listOf(
        "1" to "/storage/emulated/0/Music/Rock/Song A.mp3",
        "2" to "/storage/emulated/0/Music/Pop/song b.flac",
        "3" to "/storage/emulated/0/Music/Other/Song B.flac"
    )

    @Test fun parseDropsBomCommentsBlanksAndUnwraps() {
        val t = "\uFEFF#EXTM3U\r\n#EXTINF:1,x\r\n\r\nRock\\Song A.mp3\r\nfile:///a/b%20c.mp3\r\nhttp://x/y.mp3\n"
        assertEquals(listOf("Rock/Song A.mp3", "/a/b c.mp3", "http://x/y.mp3"), parseM3u(t))
    }

    @Test fun matchesPathEndingBeforeNameAndKeepsOrder() {
        val (ids, missing) = matchM3u(listOf("../Other/SONG B.flac", "Rock/song a.MP3", "Pop/song b.flac"), tracks)
        assertEquals(listOf("3", "1", "2"), ids)
        assertEquals(0, missing)
    }

    @Test fun nameFallbackAbsoluteWindowsAndDuplicates() {
        val (ids, missing) = matchM3u(listOf("C:/Users/x/Song A.mp3", "/storage/emulated/0/Music/Rock/Song A.mp3", "nope.mp3", "http://h/song a.mp3"), tracks)
        assertEquals(listOf("1"), ids)
        assertEquals(2, missing)
    }

    @Test fun nameFallbackSkippedWhenAmbiguous() {
        val dup = tracks + ("4" to "/storage/emulated/0/Music/X/Song A.mp3")
        val (ids, missing) = matchM3u(listOf("Other/Song A.mp3", "Rock/Song A.mp3"), dup)
        assertEquals(listOf("1"), ids)
        assertEquals(1, missing)
    }

    @Test fun decodesPercentEscapesButKeepsPlus() {
        assertEquals(listOf("Rock/Song A+B.mp3", "/a/\u00e9.mp3", "100%.mp3", "http://x/a%20b.mp3"),
            parseM3u("Rock/Song%20A+B.mp3\nfile:///a/%C3%A9.mp3\n100%.mp3\nhttp://x/a%20b.mp3"))
    }
}
