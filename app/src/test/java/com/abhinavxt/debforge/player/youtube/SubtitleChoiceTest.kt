package com.abhinavxt.debforge.player.youtube

import com.abhinavxt.debforge.domain.YouTubeSubtitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubtitleChoiceTest {
    private val en = YouTubeSubtitle("en", "English", "u-en", auto = false)
    private val enAuto = YouTubeSubtitle("en", "English (auto-generated)", "u-en-auto", auto = true)
    private val de = YouTubeSubtitle("de", "Deutsch", "u-de", auto = false)
    private val all = listOf(enAuto, de, en)

    @Test fun nothingChosenPicksNothing() = assertNull(pickSubtitle(all, null))

    @Test fun languageNotOfferedPicksNothing() = assertNull(pickSubtitle(listOf(de), SubtitleChoice("en", "English")))

    @Test fun exactLanguageAndLabelWins() =
        assertEquals(enAuto, pickSubtitle(all, SubtitleChoice("en", "English (auto-generated)")))

    @Test fun sameLanguageDifferentLabelPrefersManual() =
        assertEquals(en, pickSubtitle(all, SubtitleChoice("en", "Some other label")))

    @Test fun regionAndCaseAreIgnoredForTheLanguage() =
        assertEquals(de, pickSubtitle(all, SubtitleChoice("DE-at", "")))

    @Test fun blankLanguagePicksNothing() = assertNull(pickSubtitle(all, SubtitleChoice("", "English")))

    @Test fun reattachKeepsOrderAndDropsDuplicatesAndMissing() {
        val got = reattachSubtitles(all, listOf(SubtitleChoice("de", "Deutsch"), SubtitleChoice("de", "Deutsch"), SubtitleChoice("fr", "Francais"), SubtitleChoice("en", "English")))
        assertEquals(listOf(de, en), got)
    }

    @Test fun reattachWithNothingAttachedAttachesNothing() = assertEquals(emptyList<YouTubeSubtitle>(), reattachSubtitles(all, emptyList()))
}
