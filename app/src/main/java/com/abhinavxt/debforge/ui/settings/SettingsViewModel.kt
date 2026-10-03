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
import kotlinx.coroutines.flow.map
import com.abhinavxt.debforge.ui.theme.AppTheme
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
    private val downloadScheduler: DownloadScheduler,
    private val downloadDao: com.abhinavxt.debforge.data.local.DownloadDao,
    private val housekeeper: com.abhinavxt.debforge.download.Housekeeper,
    private val followStore: com.abhinavxt.debforge.data.follow.FollowStore,
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context
) : ViewModel() {

    val dynamicColor: StateFlow<Boolean> = settingsStore.dynamicColorFlow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), false
    )
    val appTheme: StateFlow<AppTheme> = settingsStore.appThemeFlow
        .map { AppTheme.fromName(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppTheme.DEFAULT)
    val pureBlack: StateFlow<Boolean> = settingsStore.pureBlackFlow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), false
    )

    /** Picking one of DebForge's palettes switches wallpaper colours off. */
    fun selectTheme(theme: AppTheme) = viewModelScope.launch {
        settingsStore.setAppTheme(theme.name)
        settingsStore.setDynamicColor(false)
    }
    fun setPureBlack(v: Boolean) = viewModelScope.launch { settingsStore.setPureBlack(v) }
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

    val removeAfterDownload: StateFlow<Boolean> = settingsStore.removeAfterDownloadFlow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), false
    )
    fun setRemoveAfterDownload(v: Boolean) = viewModelScope.launch { settingsStore.setRemoveAfterDownload(v) }

    fun setOrganizeLibrary(v: Boolean) = viewModelScope.launch { settingsStore.setOrganizeLibrary(v) }

    /** Free space where downloads go, and what DebForge has downloaded (still listed). */
    data class StorageInfo(val freeBytes: Long?, val downloadedBytes: Long, val downloadedFiles: Int)

    val storageInfo: StateFlow<StorageInfo?> = combine(
        settingsStore.downloadDirFlow,
        downloadDao.observeByState(com.abhinavxt.debforge.domain.DownloadState.COMPLETED)
    ) { dir, done ->
        StorageInfo(
            freeBytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.abhinavxt.debforge.download.StorageSpace.availableBytes(appContext, dir)
            },
            downloadedBytes = done.sumOf { it.filesize.coerceAtLeast(0) },
            downloadedFiles = done.size
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val autoDeleteDays: StateFlow<Int> = settingsStore.autoDeleteDaysFlow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), 0
    )

    /** Picking a period that would delete files right now asks first. */
    data class AutoDeletePreview(val days: Int, val files: Int, val bytes: Long)

    private val _autoDeleteConfirm = kotlinx.coroutines.flow.MutableStateFlow<AutoDeletePreview?>(null)
    val autoDeleteConfirm: StateFlow<AutoDeletePreview?> = _autoDeleteConfirm

    fun pickAutoDeleteDays(days: Int) = viewModelScope.launch {
        if (days <= 0) {
            settingsStore.setAutoDeleteDays(0)
            return@launch
        }
        val done = downloadDao.completed()
        val expiredIds = com.abhinavxt.debforge.domain.Housekeeping.expired(
            done.map { com.abhinavxt.debforge.domain.Housekeeping.Finished(it.id, it.finalFilePath, it.updatedAt) },
            now = System.currentTimeMillis(),
            days = days
        ).mapTo(HashSet()) { it.id }
        if (expiredIds.isEmpty()) {
            settingsStore.setAutoDeleteDays(days)
        } else {
            val bytes = done.filter { it.id in expiredIds }.sumOf { it.filesize.coerceAtLeast(0) }
            _autoDeleteConfirm.value = AutoDeletePreview(days, expiredIds.size, bytes)
        }
    }

    /** "Delete now": save the period and clean up at once. */
    fun confirmAutoDelete() {
        val p = _autoDeleteConfirm.value ?: return
        _autoDeleteConfirm.value = null
        viewModelScope.launch {
            settingsStore.setAutoDeleteDays(p.days)
            housekeeper.run()
        }
    }

    fun dismissAutoDelete() {
        _autoDeleteConfirm.value = null
    }

    val showExtraFiles: StateFlow<Boolean> = settingsStore.showExtraFilesFlow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), false
    )
    fun setShowExtraFiles(v: Boolean) = viewModelScope.launch { settingsStore.setShowExtraFiles(v) }

    /** Followed shows, by title. */
    val follows: StateFlow<List<com.abhinavxt.debforge.domain.Follow>> = followStore.follows
        .map { list -> list.sortedBy { it.title.lowercase() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun unfollow(f: com.abhinavxt.debforge.domain.Follow) = viewModelScope.launch {
        followStore.unfollow(f.provider, f.showKey)
        com.abhinavxt.debforge.download.follow.FollowJobService.sync(appContext, followStore.all().isNotEmpty())
    }

    fun providerName(id: ProviderId): String = auth.info(id).displayName

    val internalPlayer: StateFlow<Boolean> = settingsStore.internalPlayerFlow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), true
    )
    fun setInternalPlayer(v: Boolean) = viewModelScope.launch { settingsStore.setInternalPlayer(v) }

    val autoplayNext: StateFlow<Boolean> = settingsStore.autoplayNextFlow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), true
    )
    fun setAutoplayNext(v: Boolean) = viewModelScope.launch { settingsStore.setAutoplayNext(v) }

    val pip: StateFlow<Boolean> = settingsStore.pipFlow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), true
    )
    fun setPip(v: Boolean) = viewModelScope.launch { settingsStore.setPip(v) }

    val autoSkipIntro: StateFlow<Boolean> = settingsStore.autoSkipIntroFlow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), false
    )
    fun setAutoSkipIntro(v: Boolean) = viewModelScope.launch { settingsStore.setAutoSkipIntro(v) }

    val streamPreviews: StateFlow<Boolean> = settingsStore.streamPreviewsFlow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), false
    )
    fun setStreamPreviews(v: Boolean) = viewModelScope.launch { settingsStore.setStreamPreviews(v) }

    val playerGestures: StateFlow<Boolean> = settingsStore.playerGesturesFlow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), true
    )
    fun setPlayerGestures(v: Boolean) = viewModelScope.launch { settingsStore.setPlayerGestures(v) }

    val audioLanguage: StateFlow<String> = settingsStore.audioLanguageFlow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), ""
    )
    fun setAudioLanguage(v: String) = viewModelScope.launch { settingsStore.setAudioLanguage(v) }

    val subtitleLanguage: StateFlow<String> = settingsStore.subtitleLanguageFlow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), ""
    )
    fun setSubtitleLanguage(v: String) = viewModelScope.launch { settingsStore.setSubtitleLanguage(v) }

    /** Picture-in-picture exists here (most TVs and some phones don't have it). */
    val pipAvailable: Boolean = appContext.packageManager
        .hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)

    val clipboardOffer: StateFlow<Boolean> = settingsStore.clipboardOfferFlow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), true
    )
    fun setClipboardOffer(v: Boolean) = viewModelScope.launch { settingsStore.setClipboardOffer(v) }

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

    /** A TMDB key is in effect (the user's, or a debug build's own). */
    val postersEnabled: StateFlow<Boolean> = metadata.enabled.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), false
    )


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
