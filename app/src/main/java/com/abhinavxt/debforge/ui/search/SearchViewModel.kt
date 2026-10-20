package com.abhinavxt.debforge.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.abhinavxt.debforge.data.search.SearchRepository
import com.abhinavxt.debforge.data.search.SearchSourceStore
import com.abhinavxt.debforge.domain.SearchQuality
import com.abhinavxt.debforge.domain.SearchResult
import com.abhinavxt.debforge.domain.SearchResults
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SearchUiState(
    val query: String = "",
    /** The query the results are for (what was submitted, not what's being typed). */
    val searched: String? = null,
    val loading: Boolean = false,
    /** Asking the services which results they have cached; results show meanwhile. */
    val checkingCache: Boolean = false,
    val results: List<SearchResult> = emptyList(),
    /** One message per source that didn't answer. All of them failing = nothing to show. */
    val failures: List<String> = emptyList(),
    val quality: SearchQuality = SearchQuality.ANY,
    val cachedOnly: Boolean = false
) {
    val shown: List<SearchResult> get() = SearchResults.filter(results, quality, cachedOnly)
}

/**
 * The Search tab: searches the user's Jackett / Prowlarr and hands the picked
 * torrent to the Add dialog, which picks the service like for any magnet.
 */
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repository: SearchRepository,
    store: SearchSourceStore
) : ViewModel() {

    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    /** Null until known; false = no source added yet, nothing to search with. */
    val available: StateFlow<Boolean?> = store.sources
        .map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private var job: Job? = null

    fun onQueryChange(q: String) = _state.update { it.copy(query = q) }

    /** Keyboard search key or the retry button. A blank query does nothing. */
    fun submit() {
        val q = _state.value.query.trim()
        if (q.isEmpty()) return
        job?.cancel()
        _state.update { it.copy(searched = q, loading = true, failures = emptyList(), results = emptyList()) }
        job = viewModelScope.launch {
            val outcome = repository.search(q)
            _state.update {
                it.copy(loading = false, results = outcome.results, failures = outcome.failures, checkingCache = outcome.results.isNotEmpty())
            }
            if (outcome.results.isEmpty()) return@launch
            val hashes = outcome.results.take(CACHE_CHECK_MAX).map { it.hash }
            val cached = repository.cachedAmong(hashes)
            _state.update { s ->
                val marked = if (cached == null) s.results else s.results.map { r ->
                    if (r.hash in hashes) r.copy(cached = r.hash in cached) else r
                }
                s.copy(results = SearchResults.rank(marked), checkingCache = false)
            }
        }
    }

    fun onQuality(quality: SearchQuality) = _state.update { it.copy(quality = quality) }
    fun onToggleCachedOnly() = _state.update { it.copy(cachedOnly = !it.cachedOnly) }

    private companion object {
        /** Best-ranked results checked for cache; the rest are rarely worth a request. */
        const val CACHE_CHECK_MAX = 100
    }
}
