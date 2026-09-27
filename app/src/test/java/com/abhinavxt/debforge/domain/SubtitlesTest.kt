package com.abhinavxt.debforge.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class SubtitlesTest {

    private val GB = 1024L * 1024 * 1024
    private var n = 0
    private fun f(name: String, size: Long = 50_000, path: String? = null, parent: String? = "t:1") = DownloadItem(
        id = "i${n++}", provider = ProviderId.TORBOX, filename = name, sourceRef = name, downloadUrl = null,
        host = "", filesize = size, maxConnections = 8, addedAt = null, parentRef = parent, path = path
    )

    private fun names(found: List<Subtitles.Found>) = found.map { it.item.filename }

    @Test fun singleVideoGetsAllSubs() {
        val movie = f("Movie.2023.1080p.mkv", 8 * GB)
        val subs = listOf(f("Movie.2023.en.srt"), f("Movie.2023.fr.srt"), f("notes.txt"))
        val found = Subtitles.forVideo(movie, subs + movie)
        assertEquals(setOf("Movie.2023.en.srt", "Movie.2023.fr.srt"), names(found).toSet())
    }

    @Test fun sampleDoesNotMakeItAPack() {
        val movie = f("Movie.2023.1080p.mkv", 8 * GB)
        val sample = f("Movie.2023.sample.mkv", 40L * 1024 * 1024)
        val sub = f("English.srt")
        assertEquals(listOf("English.srt"), names(Subtitles.forVideo(movie, listOf(movie, sample, sub))))
    }

    @Test fun packMatchesByEpisode() {
        val e1 = f("Show.S01E01.1080p.mkv", 2 * GB)
        val e2 = f("Show.S01E02.1080p.mkv", 2 * GB)
        val s1 = f("Show.S01E01.en.srt")
        val s2 = f("Show.S01E02.en.srt")
        assertEquals(listOf("Show.S01E02.en.srt"), names(Subtitles.forVideo(e2, listOf(e1, e2, s1, s2))))
    }

    @Test fun packMatchesByFolderPath() {
        // RARBG-style: Subs/<video name>/2_English.srt
        val e1 = f("Show.S01E01.1080p.WEB.mkv", 2 * GB)
        val e2 = f("Show.S01E02.1080p.WEB.mkv", 2 * GB)
        val s1 = f("2_English.srt", path = "Show/Subs/Show.S01E01.1080p.WEB/2_English.srt")
        val s2 = f("2_English.srt", path = "Show/Subs/Show.S01E02.1080p.WEB/2_English.srt")
        val found = Subtitles.forVideo(e1, listOf(e1, e2, s1, s2))
        assertEquals(listOf(s1.id), found.map { it.item.id })
        assertEquals("en", found.single().language)
    }

    @Test fun otherTorrentsIgnored() {
        val movie = f("Movie.mkv", 8 * GB)
        assertTrue(Subtitles.forVideo(movie, listOf(movie, f("Movie.en.srt", parent = "t:2"))).isEmpty())
    }

    @Test fun languageDetection() {
        assertEquals("en", Subtitles.describe("Movie.en.srt").first)
        assertEquals("en", Subtitles.describe("2_English.srt").first)
        assertEquals("es", Subtitles.describe("Movie.2023.spa.srt").first)
        assertEquals("pt", Subtitles.describe("Movie.pt-br.srt").first)
        assertEquals("hi", Subtitles.describe("Movie.hi.srt").first)
        // A title isn't a language.
        assertEquals(null, Subtitles.describe("It.2017.srt").first)
        assertEquals(null, Subtitles.describe("subtitle.srt").first)
    }

    @Test fun forcedAndSdh() {
        val (lang, forced, sdh) = Subtitles.describe("Movie.eng.forced.srt")
        assertEquals("en", lang); assertTrue(forced); assertEquals(false, sdh)
        val d = Subtitles.describe("Movie.English.SDH.srt")
        assertEquals("en", d.first); assertTrue(d.third)
    }

    @Test fun labels() {
        val movie = f("Movie.mkv", 8 * GB)
        val found = Subtitles.forVideo(movie, listOf(movie, f("Movie.fr.forced.srt"), f("Movie.en.sdh.srt"), f("weird.srt")))
        val labels = found.map { Subtitles.label(it, Locale.ENGLISH, "Forced") }
        assertEquals(listOf("English (SDH)", "French · Forced", "weird"), labels)
    }

    @Test fun mimeTypes() {
        val movie = f("Movie.mkv", 8 * GB)
        val found = Subtitles.forVideo(movie, listOf(movie, f("a.en.ass"), f("b.fr.vtt")))
        assertEquals(setOf("text/x-ssa", "text/vtt"), found.map { it.mimeType }.toSet())
    }

    @Test fun episodeParsing() {
        assertEquals("S01E02", Subtitles.episodeOf("show.s1e2.srt"))
        assertEquals("S10E120", Subtitles.episodeOf("Show S10 E120"))
        assertEquals(null, Subtitles.episodeOf("Movie.2023.srt"))
    }
}
