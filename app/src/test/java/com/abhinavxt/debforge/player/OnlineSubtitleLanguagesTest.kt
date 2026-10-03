package com.abhinavxt.debforge.player

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class OnlineSubtitleLanguagesTest {

    @Test fun settingWins() {
        assertEquals(listOf("ja"), OnlineSubtitleLanguages.forSetting("ja", Locale.ENGLISH))
    }

    @Test fun defaultOrOffFallsBackToThePhone() {
        assertEquals(listOf("de"), OnlineSubtitleLanguages.forSetting("", Locale.GERMANY))
        assertEquals(listOf("fr"), OnlineSubtitleLanguages.forSetting("off", Locale.FRANCE))
    }

    @Test fun regionsOpenSubtitlesSplits() {
        assertEquals(listOf("pt-br", "pt-pt"), OnlineSubtitleLanguages.forSetting("", Locale.forLanguageTag("pt-BR")))
        assertEquals(listOf("zh-cn", "zh-tw"), OnlineSubtitleLanguages.forSetting("zh", Locale.ENGLISH))
    }

    @Test fun oldJavaCodes() {
        assertEquals(listOf("id"), OnlineSubtitleLanguages.forSetting("in", Locale.ENGLISH))
        assertEquals(listOf("he"), OnlineSubtitleLanguages.forSetting("iw", Locale.ENGLISH))
    }
}
