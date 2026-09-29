package com.abhinavxt.debforge.player.tracks

import com.abhinavxt.debforge.player.tracks.TrackPicker.Candidate
import com.abhinavxt.debforge.player.tracks.TrackPicker.Choice
import com.abhinavxt.debforge.player.tracks.TrackPicker.Remembered
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackPickerTest {

    private val jpn = Candidate("ja", "Japanese", isDefault = true)
    private val eng = Candidate("en", "English")
    private val engCommentary = Candidate("en", "Director's Commentary")

    @Test fun preferredAudioLanguage() {
        assertEquals(Choice.Pick(1), TrackPicker.pickAudio(listOf(jpn, eng), "en", null))
        assertEquals(Choice.Pick(0), TrackPicker.pickAudio(listOf(jpn, eng), "ja", null))
    }

    @Test fun commentaryIsNeverPickedForTheLanguage() {
        assertEquals(Choice.Pick(2), TrackPicker.pickAudio(listOf(jpn, engCommentary, eng), "en", null))
        val flagged = Candidate("en", "English", commentary = true)
        assertEquals(Choice.Leave, TrackPicker.pickAudio(listOf(jpn, flagged), "en", null))
    }

    @Test fun noPreferenceKeepsTheDefaultUnlessItsCommentary() {
        assertEquals(Choice.Leave, TrackPicker.pickAudio(listOf(jpn, eng), "", null))
        val defaultCommentary = engCommentary.copy(isDefault = true)
        assertEquals(Choice.Pick(1), TrackPicker.pickAudio(listOf(defaultCommentary, eng), "", null))
    }

    @Test fun rememberedChoiceWins() {
        val r = Remembered(audioLanguage = "ja", audioLabel = "Japanese")
        assertEquals(Choice.Pick(0), TrackPicker.pickAudio(listOf(jpn, eng), "en", r))
        // Same language, different label next episode: still that language.
        val next = listOf(eng, Candidate("ja", "Japanese 5.1"))
        assertEquals(Choice.Pick(1), TrackPicker.pickAudio(next, "en", r))
    }

    @Test fun singleAudioTrackIsLeftAlone() =
        assertEquals(Choice.Leave, TrackPicker.pickAudio(listOf(eng), "ja", null))

    private val signs = Candidate("en", "Signs & Songs")
    private val full = Candidate("en", "Full Subtitles")
    private val forced = Candidate("en", "English", isForced = true)
    private val spa = Candidate("es", "Español")

    @Test fun subtitlesAvoidSignsAndSongs() {
        assertEquals(Choice.Pick(1), TrackPicker.pickText(listOf(signs, full, spa), "en", null))
        assertEquals(Choice.Pick(2), TrackPicker.pickText(listOf(forced, signs, Candidate("en", null)), "en", null))
        // Only signs available: better than nothing.
        assertEquals(Choice.Pick(0), TrackPicker.pickText(listOf(signs, spa), "en", null))
    }

    @Test fun subtitleSettings() {
        assertEquals(Choice.Leave, TrackPicker.pickText(listOf(full), TrackPicker.AUTO, null))
        assertEquals(Choice.Off, TrackPicker.pickText(listOf(full), TrackPicker.OFF, null))
        assertEquals(Choice.Leave, TrackPicker.pickText(listOf(spa), "en", null))
    }

    @Test fun rememberedSubtitles() {
        assertEquals(Choice.Off, TrackPicker.pickText(listOf(full), "en", Remembered(textOff = true)))
        assertEquals(Choice.Pick(1), TrackPicker.pickText(listOf(full, spa), "en", Remembered(textLanguage = "spa")))
    }

    @Test fun languageCodes() {
        assertTrue(TrackPicker.sameLanguage("en", "eng"))
        assertTrue(TrackPicker.sameLanguage("en-US", "en"))
        assertTrue(TrackPicker.sameLanguage("jpn", "ja"))
        assertTrue(TrackPicker.sameLanguage("ger", "de"))
        assertTrue(TrackPicker.sameLanguage("hin", "hi"))
        assertFalse(TrackPicker.sameLanguage("und", "und"))
        assertFalse(TrackPicker.sameLanguage(null, "en"))
        assertFalse(TrackPicker.sameLanguage("en", "es"))
    }
}
