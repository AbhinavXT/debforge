package com.abhinavxt.debforge.ui.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.abhinavxt.debforge.data.local.DownloadDao
import com.abhinavxt.debforge.data.prefs.SettingsStore
import com.abhinavxt.debforge.data.repository.AuthRepository
import com.abhinavxt.debforge.data.repository.DownloadsRepository
import com.abhinavxt.debforge.domain.DataResult
import com.abhinavxt.debforge.domain.DownloadItem
import com.abhinavxt.debforge.domain.DownloadState
import com.abhinavxt.debforge.domain.ProviderId
import com.abhinavxt.debforge.domain.SortOrder
import com.abhinavxt.debforge.download.DownloadController
import com.abhinavxt.debforge.ui.add.ProviderEvents
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI state for the browse screen. The raw fetched items are held here; sort,
 * filter, and grouping are derived in the composable from this + the toggles
 * (pure transforms, recompute on change).
 */
data class BrowseUiState(
    val providerName: String = "",
    val emptyHint: String = "",
    val items: List<DownloadItem> = emptyList(),
    val query: String = "",
    val sort: SortOrder = SortOrder.DATE_DESC,
    val grouped: Boolean = false,
    val isInitialLoad: Boolean = true,
    val isAppending: Boolean = false,
    val endReached: Boolean = false,
    val error: String? = null
) {
    val canLoadMore: Boolean get() = !endReached && !isAppending && !isInitialLoad && error == null
}

@HiltViewModel
class BrowseViewModel @Inject constructor(
    private val downloadsRepository: DownloadsRepository,
    private val downloadController: DownloadController,
    private val settingsStore: SettingsStore,
    private val auth: AuthRepository,
    providerEvents: ProviderEvents,
    downloadDao: DownloadDao
) : ViewModel() {

    private val _state = MutableStateFlow(BrowseUiState())
    val state: StateFlow<BrowseUiState> = _state.asStateFlow()

    /**
     * Live map of item id -> persisted [DownloadState] for any item the app is
     * tracking. The browse list joins against this to show a "Queued /
     * Downloading / Completed" badge instead of a Download button, preventing
     * accidental re-enqueue. Ids are provider-namespaced, so rows from another
     * provider simply never match.
     */
    val downloadStates: StateFlow<Map<String, DownloadState>> = downloadDao.observeAll()
        .map { entities -> entities.associate { it.id to it.state } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /**
     * Snackbar pipe. Buffered Channel rather than a StateFlow because StateFlow
     * conflates — two rapid enqueues within ~200 ms could drop a message. With
     * a Channel each event is delivered exactly once and they queue in order.
     */
    private val _events = Channel<String>(Channel.BUFFERED)
    val events: Flow<String> = _events.receiveAsFlow()

    /** Provider whose list is currently shown. Null until the first emission. */
    private var provider: ProviderId? = null
    private var nextPage = 1
    private var loadJob: Job? = null

    init {
        // Something was added from the Add dialog: confirm it and reload so
        // cached torrents (ready instantly on TorBox) appear right away.
        viewModelScope.launch {
            providerEvents.listChanged.collect { message ->
                _events.send(message)
                refresh()
            }
        }
        viewModelScope.launch {
            // Honor the user's default sort preference once per session (not
            // collected — changing it in Settings shouldn't stomp the current
            // selection mid-browse).
            val defaultSort = settingsStore.sortOrderFlow.first()
            _state.update { it.copy(sort = defaultSort) }

            // Reload whenever the active service changes (Settings switcher).
            // signedInProvider only emits providers that have a token.
            auth.signedInProvider.filterNotNull().collect { id ->
                if (id == provider) return@collect
                provider = id
                val info = auth.info(id)
                _state.update { it.copy(providerName = info.displayName, emptyHint = info.emptyListHint) }
                refresh()
            }
        }
    }

    fun refresh() {
        val p = provider ?: return
        // Drop any in-flight page so a slow response from the previous
        // provider (or previous refresh) can't land in the new list.
        loadJob?.cancel()
        nextPage = 1
        _state.update {
            it.copy(items = emptyList(), endReached = false, error = null, isInitialLoad = true, isAppending = false)
        }
        loadJob = viewModelScope.launch {
            when (val result = downloadsRepository.getDownloadsPage(p, 1)) {
                is DataResult.Success -> {
                    nextPage = 2
                    _state.update {
                        it.copy(
                            items = result.data.items,
                            isInitialLoad = false,
                            endReached = !result.data.hasMore
                        )
                    }
                }
                is DataResult.Error -> {
                    _state.update { it.copy(isInitialLoad = false, error = result.message) }
                }
            }
        }
    }

    fun loadMore() {
        val p = provider ?: return
        // Guard against double-fires from rapid scrolls / multiple end-of-list triggers.
        if (!_state.value.canLoadMore) return
        _state.update { it.copy(isAppending = true) }
        val page = nextPage
        loadJob = viewModelScope.launch {
            when (val result = downloadsRepository.getDownloadsPage(p, page)) {
                is DataResult.Success -> {
                    nextPage = page + 1
                    _state.update { current ->
                        // A provider can list the same file on two pages if
                        // items were added between requests (offset paging);
                        // de-dupe by id so LazyColumn keys stay unique.
                        val seen = current.items.mapTo(HashSet()) { it.id }
                        current.copy(
                            items = current.items + result.data.items.filter { it.id !in seen },
                            isAppending = false,
                            endReached = !result.data.hasMore
                        )
                    }
                }
                is DataResult.Error -> {
                    _state.update { it.copy(isAppending = false, error = result.message) }
                }
            }
        }
    }

    fun onQueryChange(q: String) = _state.update { it.copy(query = q) }
    fun onSortChange(sort: SortOrder) = _state.update { it.copy(sort = sort) }
    fun onToggleGroup() = _state.update { it.copy(grouped = !it.grouped) }
    fun clearError() = _state.update { it.copy(error = null) }

    fun enqueue(item: DownloadItem) {
        viewModelScope.launch {
            downloadController.enqueue(item)
            // BUFFERED channel; send() suspends only if the buffer is full, which
            // is effectively never for a snackbar pipe.
            _events.send("Added: ${item.filename}")
        }
    }
}
