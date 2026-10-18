package com.abhinavxt.debforge.domain

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HlsPlaylistTest {

    private val base = "https://cdn.example.com/v/master.m3u8?t=1"

    @Test fun masterBestFirstWithoutSeparateAudio() {
        val text = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="en",URI="audio/en.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360
            360/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080,CODECS="avc1.640028,mp4a.40.2"
            https://other.example.com/1080/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=9000000,RESOLUTION=3840x2160,AUDIO="aud"
            2160/index.m3u8
        """.trimIndent()
        val m = HlsPlaylist.parse(text, base) as HlsPlaylist.Master
        assertEquals(
            listOf(
                HlsPlaylist.Variant("https://other.example.com/1080/index.m3u8", 5_000_000, 1080),
                HlsPlaylist.Variant("https://cdn.example.com/v/360/index.m3u8", 800_000, 360)
            ),
            m.variants
        )
    }

    @Test fun mediaSegmentsDurationAndEnd() {
        val text = """
            #EXTM3U
            #EXT-X-TARGETDURATION:6
            #EXTINF:6.0,
            seg0.ts
            #EXTINF:4.5,
            /abs/seg1.ts
            #EXT-X-ENDLIST
        """.trimIndent()
        val m = HlsPlaylist.parse(text, base) as HlsPlaylist.Media
        assertEquals(listOf("https://cdn.example.com/v/seg0.ts", "https://cdn.example.com/abs/seg1.ts"), m.segments.map { it.url })
        assertEquals(10.5, m.durationSec, 0.001)
        assertFalse(m.live)
        assertFalse(m.drm)
        assertNull(m.init)
        assertNull(m.segments[0].key)
    }

    @Test fun liveWithoutEndList() {
        assertTrue((HlsPlaylist.parse("#EXTM3U\n#EXTINF:6,\na.ts", base) as HlsPlaylist.Media).live)
    }

    @Test fun aes128KeysAndIvs() {
        val text = """
            #EXTM3U
            #EXT-X-MEDIA-SEQUENCE:7
            #EXT-X-KEY:METHOD=AES-128,URI="key.bin"
            #EXTINF:6,
            a.ts
            #EXT-X-KEY:METHOD=AES-128,URI="https://k.example.com/k2",IV=0x000102030405060708090A0B0C0D0E0F
            #EXTINF:6,
            b.ts
            #EXT-X-KEY:METHOD=NONE
            #EXTINF:6,
            c.ts
            #EXT-X-ENDLIST
        """.trimIndent()
        val s = (HlsPlaylist.parse(text, base) as HlsPlaylist.Media).segments
        assertEquals("https://cdn.example.com/v/key.bin", s[0].key!!.url)
        assertArrayEquals(ByteArray(15) + byteArrayOf(7), s[0].key!!.iv)
        assertArrayEquals(ByteArray(16) { it.toByte() }, s[1].key!!.iv)
        assertNull(s[2].key)
    }

    @Test fun drmIsFlagged() {
        val text = "#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"skd://x\",KEYFORMAT=\"com.apple.streamingkeydelivery\"\n#EXTINF:6,\na.ts\n#EXT-X-ENDLIST"
        assertTrue((HlsPlaylist.parse(text, base) as HlsPlaylist.Media).drm)
    }

    @Test fun fmp4InitAndByteRanges() {
        val text = """
            #EXTM3U
            #EXT-X-MAP:URI="video.mp4",BYTERANGE="700@0"
            #EXTINF:6,
            #EXT-X-BYTERANGE:1000@700
            video.mp4
            #EXTINF:6,
            #EXT-X-BYTERANGE:500
            video.mp4
            #EXT-X-ENDLIST
        """.trimIndent()
        val m = HlsPlaylist.parse(text, base) as HlsPlaylist.Media
        assertEquals(HlsPlaylist.Segment("https://cdn.example.com/v/video.mp4", 0, 700, null), m.init)
        assertEquals(listOf(700L to 1000L, 1700L to 500L), m.segments.map { it.offset!! to it.length!! })
    }

    @Test fun notAPlaylist() {
        assertNull(HlsPlaylist.parse("<html></html>", base))
    }
}
