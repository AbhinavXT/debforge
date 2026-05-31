package com.abhinavxt.debforge.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.abhinavxt.debforge.data.prefs.SettingsStore
import com.abhinavxt.debforge.data.repository.AuthRepository
import com.abhinavxt.debforge.domain.SortOrder
import com.abhinavxt.debforge.domain.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val downloadDir: String = SettingsStore.DEFAULT_DOWNLOAD_DIR,
    val sortDefault: SortOrder = SortOrder.DATE_DESC,
    val themeMode: ThemeMode = ThemeMode.SYSTEM
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsStore: SettingsStore,
    private val auth: AuthRepository
) : ViewModel() {

    val downloadDir: StateFlow<String> = settingsStore.downloadDirFlow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsStore.DEFAULT_DOWNLOAD_DIR
    )
    val sortDefault: StateFlow<SortOrder> = settingsStore.sortOrderFlow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), SortOrder.DATE_DESC
    )
    val themeMode: StateFlow<ThemeMode> = settingsStore.themeModeFlow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), ThemeMode.SYSTEM
    )

    fun setDownloadDir(path: String) = viewModelScope.launch { settingsStore.setDownloadDir(path) }
    fun setSortDefault(order: SortOrder) = viewModelScope.launch { settingsStore.setSortOrder(order) }
    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch { settingsStore.setThemeMode(mode) }

    /** Clears the token; the auth gate will flip and the root composable will swap to Setup. */
    fun signOut() = viewModelScope.launch { auth.signOut() }
}
