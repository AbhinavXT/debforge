package com.abhinavxt.debforge.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.abhinavxt.debforge.data.prefs.SettingsStore
import com.abhinavxt.debforge.domain.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * App-level state that the activity needs BEFORE the rest of the UI tree
 * (currently just the theme mode, so [DebforgeTheme] can honor user choice
 * instead of always following the system).
 */
@HiltViewModel
class AppViewModel @Inject constructor(
    settingsStore: SettingsStore
) : ViewModel() {
    val themeMode: StateFlow<ThemeMode> = settingsStore.themeModeFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ThemeMode.SYSTEM
    )
}
