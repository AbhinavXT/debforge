package com.abhinavxt.debforge.ui.search

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.abhinavxt.debforge.data.follow.FollowStore
import com.abhinavxt.debforge.data.repository.AuthRepository
import com.abhinavxt.debforge.data.repository.DownloadsRepository
import com.abhinavxt.debforge.data.search.SearchHistoryStore
import com.abhinavxt.debforge.data.search.SearchRepository
import com.abhinavxt.debforge.data.search.SearchSourceStore
import com.abhinavxt.debforge.domain.AddKind
import com.abhinavxt.debforge.domain.Follow
import com.abhinavxt.debforge.domain.MediaKind
import com.abhinavxt.debforge.domain.ProviderId
import com.abhinavxt.debforge.domain.SearchKind
import com.abhinavxt.debforge.domain.SearchQuality
import com.abhinavxt.debforge.domain.SearchResult
import com.abhinavxt.debforge.domain.SearchResults
import com.abhinavxt.debforge.download.follow.FollowJobService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SearchUiState(
    val query: String = "",
    val kind: SearchKind = SearchKind.ALL,
    /** Shows only; null = any. */
    val season: Int? = null,
    val episode: Int? = null,
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

/** The show a Shows search found, offered to follow; [following] once it is. */
data class FollowOffer(
    val title: String,
    val showKey: String,
    val provider: ProviderId,
    val serviceName: String,
    val following: Boolean
)

/**
 * The Search tab: searches the user's Jackett / Prowlarr and hands the picked
 * torrent to the Add dialog, which picks the service like for any magnet.
 * A Shows search can also follow the show, so new episodes are found and
 * added by themselves (see FollowChecker).
 */
@HiltViewModel
class SearchViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: SearchRepository,
    private val history: SearchHistoryStore,
    private val followStore: FollowStore,
    private val auth: AuthRepository,
    private val downloads: DownloadsRepository,
    store: SearchSourceStore
) : ViewModel() {

    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    /** Null until known; false = no source added yet, nothing to search with. */
    val available: StateFlow<Boolean?> = store.sources
        .map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val recent: StateFlow<List<String>> = history.recent
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The service a followed show's episodes go to: the active one if it takes magnets, else another that does. */
    private suspend fun followService(): ProviderId? {
        val signedIn = auth.signedInProviders.first()
        val takesMagnets = { p: ProviderId -> AddKind.MAGNET in downloads.addCapabilities(p) }
        return auth.signedInProvider.first()?.takeIf(takesMagnets)
            ?: ProviderId.entries.firstOrNull { it in signedIn && takesMagnets(it) }
    }

    private val service = MutableStateFlow<ProviderId?>(null)

    init {
        viewModelScope.launch {
            auth.signedInProviders.collect { service.value = followService() }
        }
    }

    val followOffer: StateFlow<FollowOffer?> = combine(_state, followStore.follows, service) { s, follows, provider ->
        if (s.kind != SearchKind.SHOW || s.loading || provider == null) return@combine null
        // The show most results are about: release groups spell the title differently.
        val show = s.results.map { it.info }.filter { it.kind == MediaKind.SHOW }
            .groupBy { it.groupKey }.maxByOrNull { it.value.size }?.value?.first() ?: return@combine null
        FollowOffer(
            title = show.title,
            showKey = show.groupKey,
            provider = provider,
            serviceName = auth.info(provider).displayName,
            following = follows.any { it.provider == provider && it.showKey == show.groupKey && it.autoSearch }
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private var job: Job? = null

    fun onQueryChange(q: String) = _state.update { it.copy(query = q) }

    /** Keyboard search key or the retry button. A blank query does nothing. */
    fun submit() {
        val q = _state.value.query.trim()
        if (q.isEmpty()) return
        job?.cancel()
        _state.update { it.copy(searched = q, loading = true, failures = emptyList(), results = emptyList()) }
        viewModelScope.launch { history.add(q) }
        val s = _state.value
        job = viewModelScope.launch {
            val outcome = repository.search(SearchRepository.Query(q, s.kind, s.season, s.episode))
            _state.update {
                it.copy(loading = false, results = outcome.results, failures = outcome.failures, checkingCache = outcome.results.isNotEmpty())
            }
            if (outcome.results.isEmpty()) return@launch
            val hashes = outcome.results.take(CACHE_CHECK_MAX).map { it.hash }
            val cached = repository.cachedAmong(hashes)
            _state.update { st ->
                val marked = if (cached == null) st.results else st.results.map { r ->
                    if (r.hash in hashes) r.copy(cached = r.hash in cached) else r
                }
                st.copy(results = SearchResults.rank(marked), checkingCache = false)
            }
        }
    }

    /** A recent search: fills the box and runs it. */
    fun runRecent(query: String) {
        _state.update { it.copy(query = query) }
        submit()
    }

    fun removeRecent(query: String) { viewModelScope.launch { history.remove(query) } }
    fun clearRecent() { viewModelScope.launch { history.clear() } }

    /** Changing what's searched for runs the last search again with it. */
    private fun changeSearch(change: (SearchUiState) -> SearchUiState) {
        _state.update(change)
        if (_state.value.searched != null) submit()
    }

    fun onKind(kind: SearchKind) = changeSearch { it.copy(kind = kind, season = null, episode = null) }
    fun onSeason(season: Int?) = changeSearch { it.copy(season = season, episode = null) }
    fun onEpisode(episode: Int?) = changeSearch { it.copy(episode = episode) }

    fun onQuality(quality: SearchQuality) = _state.update { it.copy(quality = quality) }
    fun onToggleCachedOnly() = _state.update { it.copy(cachedOnly = !it.cachedOnly) }

    /**
     * Follows the offered show with search on (keeping what an earlier follow
     * from the Library already handled), or stops following it. Episodes are
     * those listed from now on, in the quality picked, if any.
     */
    fun toggleFollow(offer: FollowOffer) {
        viewModelScope.launch {
            if (offer.following) {
                followStore.unfollow(offer.provider, offer.showKey)
            } else {
                val existing = followStore.all().firstOrNull { it.provider == offer.provider && it.showKey == offer.showKey }
                followStore.follow(
                    (existing ?: Follow(offer.provider, offer.showKey, offer.title, since = System.currentTimeMillis()))
                        .copy(
                            autoSearch = true,
                            resolution = SearchResults.resolutionOf(_state.value.quality) ?: existing?.resolution
                        )
                )
            }
            FollowJobService.sync(context, followStore.all().isNotEmpty())
        }
    }

    private companion object {
        /** Best-ranked results checked for cache; the rest are rarely worth a request. */
        const val CACHE_CHECK_MAX = 100
    }
}
