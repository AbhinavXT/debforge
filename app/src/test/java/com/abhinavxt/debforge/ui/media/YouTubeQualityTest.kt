package com.abhinavxt.debforge.ui.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubeQualityTest {
    @Test fun plainSuffix() = assertEquals("360p", youTubeQuality("Blender 4K 1080p 720p demo [360p].mp4"))
    @Test fun frameRateSuffix() = assertEquals("1080p60", youTubeQuality("Clip [1080p60].webm"))
    @Test fun fourDigit() = assertEquals("1440p", youTubeQuality("Title 4K [1440p].webm"))
    @Test fun noSuffix() = assertNull(youTubeQuality("Blender 4K 720p.m4a"))
    @Test fun middleBracketIgnored() = assertNull(youTubeQuality("A [360p] song.m4a"))
}
