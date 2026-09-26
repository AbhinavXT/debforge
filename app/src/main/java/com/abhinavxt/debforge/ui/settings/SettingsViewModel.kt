package com.abhinavxt.debforge.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.abhinavxt.debforge.data.prefs.SettingsStore
import com.abhinavxt.debforge.data.provider.ProviderInfo
import com.abhinavxt.debforge.data.repository.AuthRepository
import com.abhinavxt.debforge.domain.ProviderId
import com.abhinavxt.debforge.domain.SortOrder
import com.abhinavxt.debforge.domain.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One row in the Settings "Service" section. */
data class ProviderRow(
    val info: ProviderInfo,
    val active: Boolean,
    val signedIn: Boolean
)

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

    val providers: StateFlow<List<ProviderRow>> = combine(
        auth.activeProvider,
        auth.signedInProviders
    ) { active, signedIn ->
        auth.providers.map { ProviderRow(it, active = it.id == active, signedIn = it.id in signedIn) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Switches the active service. Browse reloads automatically; if the target
     * has no token the app drops to Setup with it preselected.
     */
    fun selectProvider(id: ProviderId) = viewModelScope.launch { auth.setActiveProvider(id) }

    fun setDownloadDir(path: String) = viewModelScope.launch { settingsStore.setDownloadDir(path) }
    fun setSortDefault(order: SortOrder) = viewModelScope.launch { settingsStore.setSortOrder(order) }
    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch { settingsStore.setThemeMode(mode) }

    /**
     * Clears the ACTIVE provider's token (others are kept); the auth gate flips
     * and the root composable swaps to Setup.
     */
    fun signOut() = viewModelScope.launch { auth.signOut() }
}
