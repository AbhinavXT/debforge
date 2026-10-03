package com.abhinavxt.debforge.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TraktMatchTest {

    private fun keyOf(filename: String) = TraktMatch.keyOf(ReleaseNameParser.parse(filename))

    @Test fun episodeFileMatchesTraktEpisode() {
        assertEquals(
            TraktMatch.episodeKey("The Bear", 2, 5),
            keyOf("The.Bear.S02E05.1080p.WEB-DL.x264-GRP.mkv")
        )
    }

    @Test fun traktShowYearIsIgnored() {
        assertEquals(
            TraktMatch.episodeKey("Doctor Who (2005)", 1, 1),
            TraktMatch.episodeKey("Doctor Who", 1, 1)
        )
    }

    @Test fun punctuationAndCaseDoNotMatter() {
        assertEquals(TraktMatch.episodeKey("Grey's Anatomy", 3, 4), TraktMatch.episodeKey("greys anatomy", 3, 4))
    }

    @Test fun movieFileMatchesTraktMovie() {
        val key = keyOf("Oppenheimer.2023.2160p.UHD.BluRay.x265-GRP.mkv")
        assertTrue(key in TraktMatch.movieKeys("Oppenheimer", 2023))
    }

    @Test fun differentYearIsADifferentMovie() {
        val key = keyOf("Dune.1984.1080p.BluRay.x264-GRP.mkv")
        assertTrue(key !in TraktMatch.movieKeys("Dune", 2021))
    }

    @Test fun episodeWithoutSeasonHasNoKey() {
        assertNull(TraktMatch.keyOf(ReleaseInfo(MediaKind.SHOW, "One Piece", null, null, 1071, null, null, null, "mkv")))
    }

    @Test fun otherFilesHaveNoKey() {
        assertNull(keyOf("Downloaded From Example.txt"))
    }

    @Test fun progressIsAPercentage() {
        assertEquals(50.0, TraktMatch.progress(30_000, 60_000)!!, 0.0)
        assertEquals(33.33, TraktMatch.progress(1, 3)!!, 0.0)
        assertEquals(100.0, TraktMatch.progress(90_000, 60_000)!!, 0.0)
        assertNull(TraktMatch.progress(1_000, 0))
    }
}
