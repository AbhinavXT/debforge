package com.abhinavxt.debforge.ui.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.abhinavxt.debforge.data.local.DownloadDao
import com.abhinavxt.debforge.data.prefs.SettingsStore
import com.abhinavxt.debforge.data.repository.DownloadsRepository
import com.abhinavxt.debforge.domain.DataResult
import com.abhinavxt.debforge.domain.DownloadItem
import com.abhinavxt.debforge.domain.DownloadState
import com.abhinavxt.debforge.domain.SortOrder
import com.abhinavxt.debforge.download.DownloadController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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
    downloadDao: DownloadDao
) : ViewModel() {

    private val _state = MutableStateFlow(BrowseUiState())
    val state: StateFlow<BrowseUiState> = _state.asStateFlow()

    /**
     * Live map of rdId -> persisted [DownloadState] for any item the app is
     * tracking. The browse list joins against this to show a "Queued /
     * Downloading / Completed" badge instead of a Download button, preventing
     * accidental re-enqueue.
     */
    val downloadStates: StateFlow<Map<String, DownloadState>> = downloadDao.observeAll()
        .map { entities -> entities.associate { it.rdId to it.state } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /**
     * Snackbar pipe. Buffered Channel rather than a StateFlow because StateFlow
     * conflates — two rapid enqueues within ~200 ms could drop a message. With
     * a Channel each event is delivered exactly once and they queue in order.
     */
    private val _events = Channel<String>(Channel.BUFFERED)
    val events: Flow<String> = _events.receiveAsFlow()

    private var nextPage = 1

    init {
        // Honor the user's default sort preference on first launch. We read it
        // once via `.first()` rather than collecting indefinitely — changing
        // the default in Settings should apply NEXT session, not stomp the
        // user's current sort selection mid-browse.
        viewModelScope.launch {
            val defaultSort = settingsStore.sortOrderFlow.first()
            _state.update { it.copy(sort = defaultSort) }
            refresh()
        }
    }

    fun refresh() {
        nextPage = 1
        _state.update { it.copy(items = emptyList(), endReached = false, error = null, isInitialLoad = true) }
        viewModelScope.launch {
            when (val result = downloadsRepository.getDownloadsPage(1)) {
                is DataResult.Success -> {
                    nextPage = 2
                    _state.update {
                        it.copy(
                            items = result.data,
                            isInitialLoad = false,
                            endReached = result.data.isEmpty()
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
        // Guard against double-fires from rapid scrolls / multiple end-of-list triggers.
        if (!_state.value.canLoadMore) return
        _state.update { it.copy(isAppending = true) }
        val page = nextPage
        viewModelScope.launch {
            when (val result = downloadsRepository.getDownloadsPage(page)) {
                is DataResult.Success -> {
                    nextPage = page + 1
                    _state.update { current ->
                        current.copy(
                            items = current.items + result.data,
                            isAppending = false,
                            endReached = result.data.isEmpty()
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
