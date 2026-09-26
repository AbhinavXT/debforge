package com.abhinavxt.debforge.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReleaseNameParserTest {

    private fun p(name: String) = ReleaseNameParser.parse(name)

    @Test fun sceneEpisode() {
        val r = p("The.Bear.S02E05.1080p.WEB.h264-ETHEL.mkv")
        assertEquals(MediaKind.SHOW, r.kind)
        assertEquals("The Bear", r.title)
        assertEquals("S02E05", r.episodeLabel)
        assertEquals("1080p · WEB", r.qualityLabel)
    }

    @Test fun movieWithYearAndTags() {
        val r = p("Oppenheimer.2023.2160p.UHD.BluRay.REMUX.DV.HDR.HEVC-FGT.mkv")
        assertEquals(MediaKind.MOVIE, r.kind)
        assertEquals("Oppenheimer (2023)", r.displayTitle)
        assertEquals("2160p", r.resolution)
        assertEquals("REMUX", r.source)
    }

    @Test fun numericTitleMovie() {
        val r = p("1917.2019.1080p.BluRay.x264.mkv")
        assertEquals("1917", r.title)
        assertEquals(2019, r.year)
    }

    @Test fun yearInsideTitle() {
        val r = p("Blade Runner 2049 (2017) 2160p WEB-DL HDR.mkv")
        assertEquals("Blade Runner 2049", r.title)
        assertEquals(2017, r.year)
    }

    @Test fun animeWithGroupTag() {
        val r = p("[SubsPlease] Frieren - 12 (1080p) [ABCD1234].mkv")
        assertEquals(MediaKind.SHOW, r.kind)
        assertEquals("Frieren", r.title)
        assertNull(r.season)
        assertEquals(12, r.episode)
    }

    @Test fun showWithYearGroupsWithoutIt() {
        val r = p("Doctor.Who.2005.S01E01.Rose.720p.mkv")
        assertEquals("Doctor Who", r.title)
        assertEquals("show:doctor who", r.groupKey)
    }

    @Test fun dottedAcronymTitle() {
        assertEquals("S.W.A.T", p("S.W.A.T.2017.S07E01.1080p.WEB.mkv").title)
    }

    @Test fun seasonEpisodeWords() {
        assertEquals("S03E07", p("Breaking Bad Season 3 Episode 7.mkv").episodeLabel)
    }

    @Test fun nonVideoKeepsExactName() {
        val r = p("ubuntu-24.04-desktop-amd64.iso")
        assertEquals(MediaKind.OTHER, r.kind)
        assertEquals("ubuntu-24.04-desktop-amd64", r.title)
    }
}
