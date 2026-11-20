package com.abhinavxt.debforge.domain

import com.abhinavxt.debforge.data.subtitles.OnlineSubtitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoSubsTest {
    private val sub = OnlineSubtitle(fileId = 7, language = "en", release = "Movie.2020", downloads = 10, hearingImpaired = false, hashMatch = true)
    private val en = listOf("en")

    private fun search(
        mode: OnlineSubsWhenNone = OnlineSubsWhenNone.ASK,
        hasKey: Boolean = true,
        isTv: Boolean = false,
        off: Boolean = false,
        tracks: List<TextTrackInfo> = emptyList()
    ) = AutoSubsRules.shouldSearch(mode, hasKey, isTv, off, tracks, en)

    @Test fun searchesWhenNoTrackInTheLanguage() {
        assertTrue(search())
        assertTrue(search(tracks = listOf(TextTrackInfo("fr"))))
        assertTrue(search(mode = OnlineSubsWhenNone.EXACT))
    }

    @Test fun forcedOnlyTrackDoesNotCount() = assertTrue(search(tracks = listOf(TextTrackInfo("en", forced = true))))

    @Test fun doesNotSearchWhenCovered() = assertFalse(search(tracks = listOf(TextTrackInfo("en"))))

    @Test fun doesNotSearchWhenOffNoKeyTvOrUserTurnedSubtitlesOff() {
        assertFalse(search(mode = OnlineSubsWhenNone.OFF))
        assertFalse(search(hasKey = false))
        assertFalse(search(isTv = true))
        assertFalse(search(off = true))
    }

    @Test fun unknownLanguageTrackMayBeTheUsersSoNoSearch() {
        assertFalse(search(tracks = listOf(TextTrackInfo(null))))
        assertTrue(search(tracks = listOf(TextTrackInfo(null, forced = true))))
    }

    private fun step(chip: AutoSubsChip, e: AutoSubsEvent) = AutoSubsMachine.reduce(chip, e)

    @Test fun exactMatchStartsAddingAndDownloadsUntapped() {
        val s = step(AutoSubsChip.Hidden, AutoSubsEvent.Searched(AutoPickResult.Add(sub), "English"))
        assertEquals(AutoSubsChip.Adding(sub, "English", tapped = false), s.chip)
        assertEquals(AutoSubsEffect.Download(sub, false), s.effect)
    }

    @Test fun offerShowsChipWithoutDownloading() {
        val s = step(AutoSubsChip.Hidden, AutoSubsEvent.Searched(AutoPickResult.Offer(sub), "English"))
        assertEquals(AutoSubsChip.Offer(sub, "English"), s.chip)
        assertNull(s.effect)
    }

    @Test fun nothingStaysHidden() {
        val s = step(AutoSubsChip.Hidden, AutoSubsEvent.Searched(AutoPickResult.Nothing, ""))
        assertEquals(AutoSubsChip.Hidden, s.chip)
        assertNull(s.effect)
    }

    @Test fun lateSearchResultDoesNotReplaceAVisibleChip() {
        val shown = AutoSubsChip.Added("English")
        assertEquals(shown, step(shown, AutoSubsEvent.Searched(AutoPickResult.Offer(sub), "English")).chip)
    }

    @Test fun tapOnOfferDownloadsAsTapped() {
        val s = step(AutoSubsChip.Offer(sub, "English"), AutoSubsEvent.Add)
        assertEquals(AutoSubsChip.Adding(sub, "English", tapped = true), s.chip)
        assertEquals(AutoSubsEffect.Download(sub, true), s.effect)
    }

    @Test fun addIsIgnoredWithoutAnOffer() {
        assertEquals(AutoSubsChip.Hidden, step(AutoSubsChip.Hidden, AutoSubsEvent.Add).chip)
        assertNull(step(AutoSubsChip.Added("English"), AutoSubsEvent.Add).effect)
    }

    @Test fun offerAndAddedExpireButAddingDoesNot() {
        assertEquals(AutoSubsChip.Hidden, step(AutoSubsChip.Offer(sub, "English"), AutoSubsEvent.Expired).chip)
        assertEquals(AutoSubsChip.Hidden, step(AutoSubsChip.Added("English"), AutoSubsEvent.Expired).chip)
        val adding = AutoSubsChip.Adding(sub, "English", false)
        assertEquals(adding, step(adding, AutoSubsEvent.Expired).chip)
    }

    @Test fun attachedShowsAddedWithUndo() {
        assertEquals(AutoSubsChip.Added("English"), step(AutoSubsChip.Adding(sub, "English", false), AutoSubsEvent.Attached).chip)
    }

    @Test fun failureIsToldOnlyWhenTheUserTapped() {
        val quiet = step(AutoSubsChip.Adding(sub, "English", false), AutoSubsEvent.Failed(quota = true))
        assertEquals(AutoSubsChip.Hidden, quiet.chip)
        assertNull(quiet.effect)
        val tapped = step(AutoSubsChip.Adding(sub, "English", true), AutoSubsEvent.Failed(quota = true))
        assertEquals(AutoSubsChip.Hidden, tapped.chip)
        assertEquals(AutoSubsEffect.Tell(quota = true), tapped.effect)
        assertEquals(AutoSubsEffect.Tell(quota = false), step(AutoSubsChip.Adding(sub, "English", true), AutoSubsEvent.Failed(false)).effect)
    }

    @Test fun undoRemovesOnlyAnAddedSubtitle() {
        val s = step(AutoSubsChip.Added("English"), AutoSubsEvent.Undo)
        assertEquals(AutoSubsChip.Hidden, s.chip)
        assertEquals(AutoSubsEffect.RemoveAdded, s.effect)
        assertNull(step(AutoSubsChip.Offer(sub, "English"), AutoSubsEvent.Undo).effect)
    }

    @Test fun resetHidesAnything() {
        assertEquals(AutoSubsChip.Hidden, step(AutoSubsChip.Added("English"), AutoSubsEvent.Reset).chip)
    }

    @Test fun resetWhileAddedHidesTheChipWithoutUndoing() {
        val r = step(AutoSubsChip.Added("English"), AutoSubsEvent.Reset)
        assertEquals(AutoSubsChip.Hidden, r.chip)
        assertEquals(null, r.effect)
        // And Undo after that does nothing.
        assertEquals(null, step(r.chip, AutoSubsEvent.Undo).effect)
    }
}
