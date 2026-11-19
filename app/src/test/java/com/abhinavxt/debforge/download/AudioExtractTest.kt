package com.abhinavxt.debforge.download

import com.abhinavxt.debforge.domain.DirectLinkParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioExtractTest {
    @Test fun onlyAacIsSupported() {
        assertTrue(AudioExtract.supports("audio/mp4a-latm"))
        assertFalse(AudioExtract.supports("audio/ac3"))
        assertFalse(AudioExtract.supports("audio/opus"))
        assertFalse(AudioExtract.supports("audio/mpeg"))
        assertFalse(AudioExtract.supports(null))
    }

    @Test fun canExtractByExtensionOrMime() {
        assertTrue(AudioExtract.canExtract("https://x.com/a.mp4"))
        assertTrue(AudioExtract.canExtract("https://x.com/a.MP4?x=1"))
        assertTrue(AudioExtract.canExtract("https://x.com/a.m4v#t=3"))
        assertFalse(AudioExtract.canExtract("https://x.com/a.webm"))
        assertFalse(AudioExtract.canExtract("https://x.com/a.mkv"))
        assertFalse(AudioExtract.canExtract("https://x.com/watch"))
        assertFalse(AudioExtract.canExtract("hls:https://x.com/a.mp4"))
        assertTrue(AudioExtract.canExtract("https://x.com/watch", "video/mp4; codecs=avc1"))
        assertFalse(AudioExtract.canExtract("https://x.com/watch", "video/webm"))
    }

    @Test fun renamesToM4a() {
        assertEquals("Clip.m4a", AudioExtract.m4aName("Clip.mp4"))
        assertEquals("Clip.m4a", AudioExtract.m4aName("Clip"))
        assertEquals("a.b.m4a", AudioExtract.m4aName("a.b.mp4"))
        assertEquals("Clip.m4a", AudioExtract.m4aName("Clip.MP4"))
        assertEquals("Clip.m4a", AudioExtract.m4aName("Clip.mov"))
        assertEquals(".hidden.m4a", AudioExtract.m4aName(".hidden"))
        assertEquals("Clip.m4a", AudioExtract.m4aName("Clip."))
    }

    @Test fun audioPrefixRoundTrips() {
        val url = "https://example.com/v.mp4"
        val ref = DirectLinkParser.AUDIO_PREFIX + url
        assertTrue(DirectLinkParser.isAudioRef(ref))
        assertFalse(DirectLinkParser.isAudioRef(url))
        assertEquals(url, DirectLinkParser.withoutAudioPrefix(ref))
        assertEquals(url, DirectLinkParser.withoutAudioPrefix(url))
        assertNotEquals(DirectLinkParser.itemId(url), DirectLinkParser.itemId(ref))
    }
}
