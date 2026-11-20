package com.abhinavxt.debforge.domain

import com.abhinavxt.debforge.data.subtitles.OnlineSubtitle
import com.abhinavxt.debforge.domain.SubtitleAutoPick.Context.DOWNLOAD_COMPLETE
import com.abhinavxt.debforge.domain.SubtitleAutoPick.Context.PLAYER_START
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleAutoPickTest {
    private fun sub(id: Long, lang: String? = "en", downloads: Int = 0, hi: Boolean = false, hash: Boolean = false, release: String = "r$id") =
        OnlineSubtitle(id, lang, release, downloads, hi, hash)

    private val en = listOf("en")

    @Test fun playerOffIsNothing() {
        assertEquals(AutoPickResult.Nothing, SubtitleAutoPick.pick(listOf(sub(1, hash = true)), en, OnlineSubsWhenNone.OFF, PLAYER_START))
    }

    @Test fun playerNoResultsIsNothing() {
        assertEquals(AutoPickResult.Nothing, SubtitleAutoPick.pick(emptyList(), en, OnlineSubsWhenNone.ASK, PLAYER_START))
        assertEquals(AutoPickResult.Nothing, SubtitleAutoPick.pick(listOf(sub(1, "fr")), en, OnlineSubsWhenNone.ASK, PLAYER_START))
    }

    @Test fun playerAskOffersBestEvenOnHashMatch() {
        val r = SubtitleAutoPick.pick(listOf(sub(1, downloads = 9), sub(2, hash = true)), en, OnlineSubsWhenNone.ASK, PLAYER_START)
        assertEquals(AutoPickResult.Offer(sub(2, hash = true)), r)
    }

    @Test fun playerExactAddsOnlyOnHashMatch() {
        val hashed = SubtitleAutoPick.pick(listOf(sub(1, downloads = 9), sub(2, hash = true)), en, OnlineSubsWhenNone.EXACT, PLAYER_START)
        assertTrue(hashed is AutoPickResult.Add)
        val plain = SubtitleAutoPick.pick(listOf(sub(1, downloads = 9)), en, OnlineSubsWhenNone.EXACT, PLAYER_START)
        assertTrue(plain is AutoPickResult.Offer)
    }

    @Test fun languageOrderBeatsHashMatch() {
        val r = SubtitleAutoPick.pick(listOf(sub(1, "fr", hash = true), sub(2, "en")), listOf("en", "fr"), OnlineSubsWhenNone.ASK, PLAYER_START)
        assertEquals(2L, (r as AutoPickResult.Offer).subtitle.fileId)
    }

    @Test fun downloadsBreakTiesAndStableOrderKept() {
        val r = SubtitleAutoPick.pick(listOf(sub(1, downloads = 5), sub(2, downloads = 50), sub(3, downloads = 50)), en, OnlineSubsWhenNone.ASK, PLAYER_START)
        assertEquals(2L, (r as AutoPickResult.Offer).subtitle.fileId)
    }

    @Test fun skipsHearingImpairedUnlessOnlyOption() {
        val r = SubtitleAutoPick.pick(listOf(sub(1, hi = true, downloads = 99), sub(2, downloads = 1)), en, OnlineSubsWhenNone.ASK, PLAYER_START)
        assertEquals(2L, (r as AutoPickResult.Offer).subtitle.fileId)
        val only = SubtitleAutoPick.pick(listOf(sub(1, hi = true)), en, OnlineSubsWhenNone.ASK, PLAYER_START)
        assertEquals(1L, (only as AutoPickResult.Offer).subtitle.fileId)
    }

    @Test fun prefersNonForced() {
        val r = SubtitleAutoPick.pick(listOf(sub(1, downloads = 99, release = "Movie.FORCED"), sub(2, downloads = 1)), en, OnlineSubsWhenNone.ASK, PLAYER_START)
        assertEquals(2L, (r as AutoPickResult.Offer).subtitle.fileId)
    }

    @Test fun regionalCodeMatchesBaseLanguage() {
        val r = SubtitleAutoPick.pick(listOf(sub(1, "pt-BR")), listOf("pt-br", "pt-pt"), OnlineSubsWhenNone.ASK, PLAYER_START)
        assertTrue(r is AutoPickResult.Offer)
    }

    @Test fun downloadPrefersHashMatchThenTop() {
        val hashed = SubtitleAutoPick.pick(listOf(sub(1, downloads = 99), sub(2, hash = true, downloads = 1)), en, OnlineSubsWhenNone.OFF, DOWNLOAD_COMPLETE)
        assertEquals(AutoPickResult.Add(sub(2, hash = true, downloads = 1)), hashed)
        val top = SubtitleAutoPick.pick(listOf(sub(1, downloads = 3), sub(2, downloads = 9)), en, OnlineSubsWhenNone.OFF, DOWNLOAD_COMPLETE)
        assertEquals(2L, (top as AutoPickResult.Add).subtitle.fileId)
    }

    @Test fun downloadWithNothingInLanguageIsNothing() {
        assertEquals(AutoPickResult.Nothing, SubtitleAutoPick.pick(listOf(sub(1, "de")), en, OnlineSubsWhenNone.ASK, DOWNLOAD_COMPLETE))
        assertEquals(AutoPickResult.Nothing, SubtitleAutoPick.pick(emptyList(), en, OnlineSubsWhenNone.ASK, DOWNLOAD_COMPLETE))
    }

    @Test fun needsSubtitles() {
        assertTrue(SubtitleAutoPick.needsSubtitles(emptyList(), en))
        assertTrue(SubtitleAutoPick.needsSubtitles(listOf(TextTrackInfo("fr"), TextTrackInfo(null)), en))
        assertFalse(SubtitleAutoPick.needsSubtitles(listOf(TextTrackInfo("fr"), TextTrackInfo("EN")), en))
        assertFalse(SubtitleAutoPick.needsSubtitles(listOf(TextTrackInfo("pt")), listOf("pt-br", "pt-pt")))
        assertFalse(SubtitleAutoPick.needsSubtitles(emptyList(), emptyList()))
    }

    @Test fun firstLanguageHearingImpairedOnlyBeatsCleanSecondLanguage() {
        val r = SubtitleAutoPick.pick(listOf(sub(1, "en", hi = true), sub(2, "fr")), listOf("en", "fr"), OnlineSubsWhenNone.ASK, PLAYER_START)
        assertEquals(1L, (r as AutoPickResult.Offer).subtitle.fileId)
    }

    @Test fun cleanBeatsHearingImpairedWithinALanguage() {
        val r = SubtitleAutoPick.pick(listOf(sub(1, "en", hi = true, downloads = 99), sub(2, "en")), en, OnlineSubsWhenNone.ASK, PLAYER_START)
        assertEquals(2L, (r as AutoPickResult.Offer).subtitle.fileId)
    }

    @Test fun forcedOnlyTrackDoesNotSatisfyTheLanguage() {
        assertTrue(SubtitleAutoPick.needsSubtitles(listOf(TextTrackInfo("en", forced = true)), en))
        assertFalse(SubtitleAutoPick.needsSubtitles(listOf(TextTrackInfo("en", forced = true), TextTrackInfo("en")), en))
    }
}
