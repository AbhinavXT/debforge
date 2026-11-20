package com.abhinavxt.debforge.download

import com.abhinavxt.debforge.domain.TextTrackInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleFetchRulesTest {
    @Test fun wantedNeedsSettingKeyAndVideo() {
        assertTrue(SubtitleFetchRules.wanted(true, true, "Movie.2020.mkv"))
        assertFalse(SubtitleFetchRules.wanted(false, true, "Movie.mkv"))
        assertFalse(SubtitleFetchRules.wanted(true, false, "Movie.mkv"))
        assertFalse(SubtitleFetchRules.wanted(true, true, "Song.mp3"))
        assertFalse(SubtitleFetchRules.wanted(true, true, "notes.txt"))
    }

    @Test fun sidecarOfThisVideoCountsOthersDont() {
        val t = SubtitleFetchRules.sidecarTracks("Movie.mkv", listOf("Movie.en.srt", "Other.fr.srt"))
        assertEquals(listOf("en"), t.map { it.language })
    }

    @Test fun existingSidecarInLanguageSkips() {
        val sidecars = SubtitleFetchRules.sidecarTracks("Movie.mkv", listOf("Movie.English.srt"))
        assertFalse(SubtitleFetchRules.needsFetch(sidecars, emptyList(), listOf("en")))
    }

    @Test fun sidecarInOtherLanguageStillFetches() {
        val sidecars = SubtitleFetchRules.sidecarTracks("Movie.mkv", listOf("Movie.fr.srt"))
        assertTrue(SubtitleFetchRules.needsFetch(sidecars, emptyList(), listOf("en")))
    }

    @Test fun embeddedTrackInLanguageSkips() {
        val emb = listOf(TextTrackInfo(SubtitleFetchRules.normalizeLanguage("eng")))
        assertFalse(SubtitleFetchRules.needsFetch(emptyList(), emb, listOf("en")))
    }

    @Test fun forcedOnlyEmbeddedStillFetches() {
        assertTrue(SubtitleFetchRules.needsFetch(emptyList(), listOf(TextTrackInfo("en", forced = true)), listOf("en")))
    }

    @Test fun noTracksFetches() {
        assertTrue(SubtitleFetchRules.needsFetch(emptyList(), emptyList(), listOf("en")))
    }

    @Test fun normalizeLanguage() {
        assertEquals("en", SubtitleFetchRules.normalizeLanguage("eng"))
        assertEquals("pt", SubtitleFetchRules.normalizeLanguage("pt-BR"))
        assertEquals(null, SubtitleFetchRules.normalizeLanguage("und"))
    }
}
