package com.abhinavxt.debforge.ui.settings

import com.abhinavxt.debforge.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsIndexTest {
    private val strings = mapOf(
        R.string.set_wifi_only to "Wi-Fi only",
        R.string.set_wifi_only_detail to "Only download on Wi-Fi",
        R.string.set_pip to "Picture in picture",
        R.string.set_pip_detail to "Keep playing in a small window",
        R.string.sources_title to "Search sources",
    )
    private val index = listOf(
        SettingEntry(R.string.set_wifi_only, R.string.set_wifi_only_detail, SettingsCategory.DOWNLOADS),
        SettingEntry(R.string.set_pip, R.string.set_pip_detail, SettingsCategory.VIDEO),
        SettingEntry(R.string.sources_title, null, SettingsCategory.CONNECTIONS),
    )
    private fun search(q: String) = searchSettings(q, index) { strings.getValue(it) }.map { it.category }

    @Test fun blankQueryFindsNothing() {
        assertTrue(search("").isEmpty())
        assertTrue(search("   ").isEmpty())
    }

    @Test fun matchesTitleIgnoringCase() = assertEquals(listOf(SettingsCategory.DOWNLOADS), search("WI-FI"))

    @Test fun matchesDetailText() = assertEquals(listOf(SettingsCategory.VIDEO), search("small window"))

    @Test fun everyWordMustMatch() {
        assertEquals(listOf(SettingsCategory.CONNECTIONS), search("sources search"))
        assertTrue(search("wi-fi window").isEmpty())
    }

    @Test fun targetMapsToItsCategory() {
        assertEquals(SettingsCategory.CONNECTIONS, SettingsCategory.forTarget(SettingsTarget.SEARCH_SOURCES))
        assertNull(SettingsCategory.forTarget(null))
        assertNull(SettingsCategory.forTarget("nope"))
    }

    @Test fun keysAreUniqueAndRoundTrip() {
        assertEquals(SettingsCategory.entries.size, SettingsCategory.entries.map { it.key }.toSet().size)
        SettingsCategory.entries.forEach { assertEquals(it, SettingsCategory.fromKey(it.key)) }
        assertNull(SettingsCategory.fromKey("gone"))
    }

    @Test fun everyCategoryHasSettings() {
        val used = SETTINGS_INDEX.map { it.category }.toSet()
        assertEquals(SettingsCategory.entries.toSet(), used)
    }
}
