package com.abhinavxt.debforge.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class YouTubeSubtitleTest {
    private fun dl(name: String) = DownloadItem(
        id = name, provider = ProviderId.DIRECT, filename = name, sourceRef = name, downloadUrl = null,
        host = "", filesize = 1, maxConnections = 1, addedAt = null, parentRef = "local:/dl",
    )

    private fun sub(tag: String, auto: Boolean = false) = YouTubeSubtitle(tag, tag, "u-$tag-$auto", auto)

    @Test fun playerPairsTheGeneratedNameWithTheVideoAndReadsTheLanguage() {
        val video = dl("Big Buck Bunny [1080p].mp4")
        for ((tag, lang) in listOf("en" to "en", "pt-BR" to "pt", "fr" to "fr")) {
            val name = subtitleFileName(video.filename, sub(tag))
            val other = dl("Another clip [720p].mp4")
            val found = Subtitles.forVideo(video, listOf(video, other, dl(name)))
            assertEquals(name, listOf(lang), found.map { it.language })
        }
        assertEquals("Big Buck Bunny [1080p].en.vtt", subtitleFileName(video.filename, sub("en")))
    }

    @Test fun oneTrackPerLanguagePrefersManual() {
        val picked = listOf(sub("en", auto = true), sub("en"), sub("de", auto = true)).onePerLanguage()
        assertEquals(listOf("de" to true, "en" to false), picked.map { it.languageCode to it.auto })
    }

    @Test fun onlyYouTubeSubtitleRowsAreFiledWithTheirVideo() {
        assertEquals("My.Dog.srt", filingNameFor("My.Dog.srt", false))
        assertEquals("Show.S01E02.1080p.en.srt", filingNameFor("Show.S01E02.1080p.en.srt", false))
        assertEquals("Clip [720p].mp4", filingNameFor("Clip [720p].en.vtt", true))
        assertEquals("Clip.mp4", filingNameFor("Clip.vtt", true))
        assertEquals("Clip [720p].mp4", filingNameFor("Clip [720p].mp4", true))
    }

    @Test fun subtitleRowIdsAreRecognisedByTheirOwnPrefix() {
        val id = youTubeSubtitleId(YouTubeLinks.sourceRef("abc", 137, 140), "en")
        assertEquals(true, isYouTubeSubtitleId(id))
        assertEquals(false, isYouTubeSubtitleId(YouTubeLinks.sourceRef("abc", 137, 140)))
        assertEquals(false, isYouTubeSubtitleId("torbox:12:sub:en"))
    }

    @Test fun subtitleUrlGetsTheVttFormat() {
        assertEquals("https://y/t?v=1&fmt=vtt&lang=en", withVttFormat("https://y/t?v=1&fmt=ttml&lang=en"))
        assertEquals("https://y/t?v=1&fmt=vtt", withVttFormat("https://y/t?v=1&fmt=srv3"))
        assertEquals("https://y/t?v=1&fmt=vtt", withVttFormat("https://y/t?v=1"))
        assertEquals("https://y/t?fmt=vtt", withVttFormat("https://y/t"))
        assertEquals("https://y/t?v=1&fmt=vtt&x=2", withVttFormat("https://y/t?v=1&fmt=vtt&x=2"))
        assertEquals("https://y/t?v=1&fmt=vtt#f", withVttFormat("https://y/t?v=1#f"))
    }
}
