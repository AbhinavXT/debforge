package com.abhinavxt.debforge.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleasePrefsTest {

    private val prefs = ReleasePrefs(
        avoid = ReleasePrefs.words(ReleasePrefs.DEFAULT_AVOID),
        prefer = ReleasePrefs.words(" x265, ,WEB-DL ")
    )

    @Test fun wordsSplitOnCommas() = assertEquals(listOf("x265", "WEB-DL"), prefs.prefer)

    @Test fun avoidsWholeNamePartsOnly() {
        assertTrue(prefs.avoids("Movie.2026.HDCAM.x264"))
        assertTrue(prefs.avoids("Movie 2026 TS XviD"))
        assertFalse(prefs.avoids("Shorts.2026.1080p.WEB"))     // "ts" inside a word
        assertFalse(prefs.avoids("Camera.Obscura.2026.1080p")) // "cam" inside a word
    }

    @Test fun scoresEachPreferredWord() {
        assertEquals(2, prefs.score("Show.S01E01.1080p.WEB.DL.x265"))
        assertEquals(1, prefs.score("Show.S01E01.1080p.WEB-DL.x264"))
        assertEquals(0, prefs.score("Show.S01E01.1080p.WEBRip.x264"))
    }

    @Test fun rankPutsPreferredAfterCachedBeforeSeeders() {
        fun r(title: String, seeders: Int, preferred: Int, cached: Boolean? = null) =
            SearchResult(title, title, "magnet:", 1, seeders, null, null, cached, null, preferred = preferred)
        val ranked = SearchResults.rank(
            listOf(r("a", 500, 0), r("b", 5, 1), r("c", 1, 0, cached = true))
        )
        assertEquals(listOf("c", "b", "a"), ranked.map { it.title })
    }
}
