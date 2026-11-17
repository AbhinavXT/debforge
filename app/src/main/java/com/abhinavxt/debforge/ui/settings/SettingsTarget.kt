package com.abhinavxt.debforge.ui.settings

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * A section of the Settings screen another screen wants shown (e.g. Search's
 * "Open Settings" asks for [SEARCH_SOURCES]). Set before opening Settings;
 * Settings scrolls there and clears it.
 */
object SettingsTarget {
    const val SEARCH_SOURCES = "search_sources"
    val section = MutableStateFlow<String?>(null)
}
