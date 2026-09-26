package com.abhinavxt.debforge.ui.browse

import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import com.abhinavxt.debforge.R
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.abhinavxt.debforge.data.local.DownloadDao
import com.abhinavxt.debforge.data.metadata.MediaMeta
import com.abhinavxt.debforge.data.metadata.MetadataRepository
import com.abhinavxt.debforge.data.provider.RemoteJob
import com.abhinavxt.debforge.download.watch.ReadyWatcher
import android.net.Uri
import kotlinx.coroutines.delay
import com.abhinavxt.debforge.data.prefs.SettingsStore
import com.abhinavxt.debforge.data.repository.AuthRepository
import com.abhinavxt.debforge.data.repository.DownloadsRepository
import com.abhinavxt.debforge.domain.DataResult
import com.abhinavxt.debforge.domain.DownloadItem
import com.abhinavxt.debforge.domain.DownloadState
import com.abhinavxt.debforge.domain.ProviderId
import com.abhinavxt.debforge.domain.ReleaseInfo
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
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
    val posterView: Boolean = true,
    /** Loading the rest of the library so a search covers everything. */
    val searchingAll: Boolean = false,
    /** "Premium expires in 3 days" — shown as a banner; null = nothing to warn. */
    val expiryWarning: String? = null,
    val renewUrl: String? = null,
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
    private val metadata: MetadataRepository,
    private val watcher: ReadyWatcher,
    @ApplicationContext private val appContext: Context,
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

    /**
     * Artwork per title group (ReleaseInfo.groupKey). A key mapped to null
     * means "looked up, nothing found" — the card keeps its placeholder.
     */
    private val _meta = MutableStateFlow<Map<String, MediaMeta?>>(emptyMap())
    val meta: StateFlow<Map<String, MediaMeta?>> = _meta.asStateFlow()
    private val requestedMeta = HashSet<String>() // main thread only

    /** Jobs the service is still fetching (shown in the Processing strip). */
    private val _processing = MutableStateFlow<List<RemoteJob>>(emptyList())
    val processing: StateFlow<List<RemoteJob>> = _processing.asStateFlow()

    /** "provider|jobRef" of every job set to auto-download. */
    val watchedJobs: StateFlow<Set<String>> = watcher.watches
        .map { list -> list.map { "${it.provider.name}|${it.jobRef}" }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /** One-shot "open this in a video player" requests for the screen. */
    private val _playRequests = Channel<PlayRequest>(Channel.BUFFERED)
    val playRequests: Flow<PlayRequest> = _playRequests.receiveAsFlow()

    private var processingJob: Job? = null

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
            val posters = settingsStore.posterViewFlow.first()
            _state.update { it.copy(sort = defaultSort, posterView = posters) }

            // Reload whenever the active service changes (Settings switcher).
            // signedInProvider only emits providers that have a token.
            auth.signedInProvider.filterNotNull().collect { id ->
                if (id == provider) return@collect
                provider = id
                val info = auth.info(id)
                _state.update {
                    it.copy(
                        providerName = info.displayName,
                        emptyHint = info.emptyListHint,
                        expiryWarning = null,
                        renewUrl = info.websiteUrl
                    )
                }
                refresh()
                checkExpiry(id)
            }
        }
    }

    fun refresh() {
        val p = provider ?: return
        // Drop any in-flight page so a slow response from the previous
        // provider (or previous refresh) can't land in the new list.
        loadJob?.cancel()
        searchJob?.cancel()
        nextPage = 1
        _state.update {
            it.copy(items = emptyList(), endReached = false, error = null, isInitialLoad = true, isAppending = false)
        }
        loadProcessing()
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
                    // A search was active (e.g. pull-to-refresh mid-search):
                    // keep covering the whole library.
                    if (_state.value.query.isNotBlank()) startSearchFill(debounceMs = 0)
                }
                is DataResult.Error -> {
                    _state.update { it.copy(isInitialLoad = false, error = result.message) }
                }
            }
        }
    }

    fun loadMore() {
        val p = provider ?: return
        // Guard against double-fires from rapid scrolls / multiple end-of-list
        // triggers, and against racing the search fill (it pages on its own).
        if (!_state.value.canLoadMore || searchJob?.isActive == true) return
        loadJob = viewModelScope.launch { appendNextPage(p) }
    }

    /** Loads [nextPage] and appends it. Returns false on error or end of list. */
    private suspend fun appendNextPage(p: ProviderId): Boolean {
        _state.update { it.copy(isAppending = true) }
        val page = nextPage
        return when (val result = downloadsRepository.getDownloadsPage(p, page)) {
            is DataResult.Success -> {
                nextPage = page + 1
                _state.update { current ->
                    // A provider can list the same file on two pages if items
                    // were added between requests (offset paging); de-dupe by
                    // id so LazyColumn keys stay unique.
                    val seen = current.items.mapTo(HashSet()) { it.id }
                    current.copy(
                        items = current.items + result.data.items.filter { it.id !in seen },
                        isAppending = false,
                        endReached = !result.data.hasMore
                    )
                }
                result.data.hasMore
            }
            is DataResult.Error -> {
                _state.update { it.copy(isAppending = false, error = result.message) }
                false
            }
        }
    }

    // --- library-wide search ----------------------------------------------------

    private var searchJob: Job? = null

    /**
     * None of the services can search your own library server-side (RD and
     * TorBox only list page by page), so a search pages through the rest of
     * the library in the background until everything is loaded — results
     * then cover the whole account, not just what was scrolled into view.
     */
    fun onQueryChange(q: String) {
        _state.update { it.copy(query = q) }
        searchJob?.cancel()
        if (q.isBlank()) {
            _state.update { it.copy(searchingAll = false) }
            return
        }
        startSearchFill(debounceMs = SEARCH_DEBOUNCE_MS)
    }

    private fun startSearchFill(debounceMs: Long) {
        val p = provider ?: return
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(debounceMs) // wait for typing to pause
            loadJob?.join()   // let an in-flight page land first
            if (_state.value.endReached || _state.value.isInitialLoad) return@launch
            _state.update { it.copy(searchingAll = true) }
            try {
                while (_state.value.items.size < SEARCH_CAP && appendNextPage(p)) {
                    // keep going until the end of the library (or the cap)
                }
            } finally {
                _state.update { it.copy(searchingAll = false) }
            }
        }
    }

    private fun checkExpiry(id: ProviderId) {
        viewModelScope.launch {
            val account = (auth.account(id) as? DataResult.Success)?.data ?: return@launch
            val days = account.daysLeft ?: return@launch
            val name = auth.info(id).displayName
            val msg = when {
                days < 0 -> str(R.string.expiry_expired, name)
                days == 0L -> str(R.string.expiry_today, name)
                days <= 5 -> appContext.resources.getQuantityString(R.plurals.expiry_in_days, days.toInt(), days.toInt(), name)
                else -> null
            }
            if (id == provider) _state.update { it.copy(expiryWarning = msg) }
        }
    }

    fun dismissExpiryWarning() = _state.update { it.copy(expiryWarning = null) }

    private fun loadProcessing() {
        val p = provider ?: return
        processingJob?.cancel()
        processingJob = viewModelScope.launch {
            // Failures here are non-fatal: the strip just stays as it was.
            val r = downloadsRepository.listProcessing(p)
            if (r is DataResult.Success) _processing.value = r.data
        }
    }

    fun isWatched(job: RemoteJob) = "${job.provider.name}|${job.ref}" in watchedJobs.value

    fun toggleWatch(job: RemoteJob) {
        viewModelScope.launch {
            if (isWatched(job)) {
                watcher.unwatch(job.provider, job.ref)
            } else {
                watcher.watch(job.provider, job.ref, job.name)
                _events.send(str(R.string.msg_will_download_when_ready, job.name))
            }
        }
    }

    /**
     * Called from the screen while it's visible (repeatOnLifecycle). Every
     * 20 s, if anything is processing or watched: refresh the strip and let
     * the watcher queue anything that finished.
     */
    suspend fun pollWhileVisible() {
        while (true) {
            delay(POLL_MS)
            if (_processing.value.isEmpty() && watchedJobs.value.isEmpty()) continue
            loadProcessing()
            val ready = watcher.checkNow(fromBackground = false)
            if (ready.isNotEmpty()) {
                _events.send(
                    if (ready.size == 1) str(R.string.msg_ready_one, ready.first())
                    else appContext.resources.getQuantityString(R.plurals.msg_ready_many, ready.size, ready.size)
                )
                refresh()
            }
        }
    }

    /** Resolve a streamable link and hand it to an external player. */
    fun play(item: DownloadItem, title: String) {
        viewModelScope.launch {
            val url = item.downloadUrl ?: when (val r = downloadsRepository.resolveLink(item.provider, item.sourceRef)) {
                is DataResult.Success -> r.data.url
                is DataResult.Error -> {
                    _events.send(r.message)
                    return@launch
                }
            }
            _playRequests.send(PlayRequest(Uri.parse(url), title))
        }
    }

    fun reportNoPlayer() {
        viewModelScope.launch { _events.send(str(R.string.msg_no_player)) }
    }

    /** Adding/changing the TMDB key: forget misses so cards retry. */
    private val metaReset = viewModelScope.launch {
        // drop(1): only react to CHANGES, or the initial emission could race
        // with the first cards and wipe their requests.
        metadata.enabled.distinctUntilChanged().drop(1).collect {
            requestedMeta.clear()
            _meta.value = emptyMap()
        }
    }

    /** Called by each poster card as it appears; de-duplicated here. */
    fun requestMeta(info: ReleaseInfo) {
        val key = info.groupKey
        if (!requestedMeta.add(key)) return
        metadata.peek(key)?.let { cached ->
            _meta.update { it + (key to cached) }
            return
        }
        viewModelScope.launch {
            val found = metadata.lookup(info)
            _meta.update { it + (key to found) }
        }
    }

    fun onTogglePosterView() {
        val next = !_state.value.posterView
        _state.update { it.copy(posterView = next) }
        viewModelScope.launch { settingsStore.setPosterView(next) }
    }

    fun onSortChange(sort: SortOrder) = _state.update { it.copy(sort = sort) }
    fun onToggleGroup() = _state.update { it.copy(grouped = !it.grouped) }
    fun clearError() = _state.update { it.copy(error = null) }

    val downloadDir: StateFlow<String> = settingsStore.downloadDirFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, SettingsStore.DEFAULT_DOWNLOAD_DIR)

    /** Switch the download folder to app storage, then queue what was waiting. */
    fun useAppStorage(dir: String, pending: List<DownloadItem>) {
        viewModelScope.launch {
            settingsStore.setDownloadDir(dir)
            _events.send(str(R.string.msg_app_storage))
            enqueueAll(pending)
        }
    }

    /** Zip-of-everything item for a torrent in the title sheet (TorBox), or null. */
    fun zipFor(file: DownloadItem, fileCount: Int): DownloadItem? {
        val ref = file.parentRef ?: return null
        return downloadsRepository.zipBundle(file.provider, ref, file.parentName ?: file.filename, fileCount)
    }

    /** "Download all" / "Download season" from the title sheet. */
    fun enqueueAll(items: List<DownloadItem>) {
        if (items.size == 1) return enqueue(items.first())
        viewModelScope.launch {
            items.forEach { downloadController.enqueue(it) }
            _events.send(appContext.resources.getQuantityString(R.plurals.added_n_files, items.size, items.size))
        }
    }

    private fun str(id: Int, vararg args: Any): String = appContext.getString(id, *args)

    companion object {
        private const val POLL_MS = 20_000L
        private const val SEARCH_DEBOUNCE_MS = 400L
        /** Stop auto-paging past this many files (very large accounts). */
        private const val SEARCH_CAP = 5_000
    }

    fun enqueue(item: DownloadItem) {
        viewModelScope.launch {
            downloadController.enqueue(item)
            // BUFFERED channel; send() suspends only if the buffer is full, which
            // is effectively never for a snackbar pipe.
            _events.send(str(R.string.msg_added_one, item.filename))
        }
    }
}
