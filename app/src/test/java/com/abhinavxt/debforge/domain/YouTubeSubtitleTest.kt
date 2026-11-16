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
}
