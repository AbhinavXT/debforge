package com.abhinavxt.debforge.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.abhinavxt.debforge.data.metadata.MetadataRepository
import com.abhinavxt.debforge.download.DownloadScheduler
import com.abhinavxt.debforge.data.prefs.SettingsStore
import com.abhinavxt.debforge.data.prefs.DownloadRules
import com.abhinavxt.debforge.data.provider.AccountInfo
import com.abhinavxt.debforge.domain.DataResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.distinctUntilChanged
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
    private val auth: AuthRepository,
    private val metadata: MetadataRepository,
    private val downloadScheduler: DownloadScheduler
) : ViewModel() {

    val dynamicColor: StateFlow<Boolean> = settingsStore.dynamicColorFlow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), true
    )
    val tmdbKey: StateFlow<String> = settingsStore.tmdbKeyFlow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), ""
    )

    /** Account card for the active service; null while loading / on error. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val account: StateFlow<AccountInfo?> = auth.signedInProvider
        .distinctUntilChanged()
        .flatMapLatest { id ->
            flow {
                emit(null)
                if (id != null) {
                    val r = auth.account(id)
                    if (r is DataResult.Success) emit(r.data)
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val organizeLibrary: StateFlow<Boolean> = settingsStore.organizeLibraryFlow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), false
    )
    val rules: StateFlow<DownloadRules> = settingsStore.downloadRulesFlow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), DownloadRules()
    )

    fun setOrganizeLibrary(v: Boolean) = viewModelScope.launch { settingsStore.setOrganizeLibrary(v) }
    // After a rule changes, make sure queued work runs (or keeps waiting)
    // under the new rule. The app is visible here, which Android requires to
    // (re)start the download job.
    fun setWifiOnly(v: Boolean) = viewModelScope.launch {
        settingsStore.setWifiOnly(v)
        downloadScheduler.ensureRunning()
    }
    fun setChargingOnly(v: Boolean) = viewModelScope.launch {
        settingsStore.setChargingOnly(v)
        downloadScheduler.ensureRunning()
    }
    // Turning the schedule off, or moving the window over "now", starts
    // queued downloads at once; otherwise the wake-up alarm moves to the new
    // start time.
    fun setSchedule(enabled: Boolean, start: Int, end: Int) = viewModelScope.launch {
        settingsStore.setSchedule(enabled, start, end)
        downloadScheduler.ensureRunning()
    }
    fun setSpeedLimit(kbps: Int) = viewModelScope.launch { settingsStore.setSpeedLimitKbps(kbps) }

    fun setDynamicColor(enabled: Boolean) = viewModelScope.launch { settingsStore.setDynamicColor(enabled) }

    /** New key -> drop cached misses so posters are looked up again. */
    fun setTmdbKey(key: String) = viewModelScope.launch {
        settingsStore.setTmdbKey(key)
        metadata.clearCache()
    }

    fun clearPosterCache() = viewModelScope.launch { metadata.clearCache() }


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
