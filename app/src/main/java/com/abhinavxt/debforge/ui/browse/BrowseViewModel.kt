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
import android.os.SystemClock
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
import com.abhinavxt.debforge.download.StorageAccess
import com.abhinavxt.debforge.download.StorageSpace
import com.abhinavxt.debforge.domain.Housekeeping
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.abhinavxt.debforge.ui.add.ProviderEvents
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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
    private val downloadDao: DownloadDao,
    private val followStore: com.abhinavxt.debforge.data.follow.FollowStore,
    private val followChecker: com.abhinavxt.debforge.download.follow.FollowChecker,
    private val playbackPositions: com.abhinavxt.debforge.data.playback.PlaybackPositions,
    private val subtitleResolver: com.abhinavxt.debforge.player.SubtitleResolver,
    private val upNext: com.abhinavxt.debforge.player.UpNext
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

    /** How many items came from page 1 last time, so a quiet refresh knows what to replace. */
    private var firstPageCount = 0
    /** elapsedRealtime of the last successful page-1 load; 0 = never. */
    private var lastListLoad = 0L
    private var quietJob: Job? = null

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
                        emptyHint = str(
                            R.string.browse_empty_hint,
                            android.net.Uri.parse(info.websiteUrl).host?.removePrefix("www.") ?: info.displayName
                        ),
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
        quietJob?.cancel()
        nextPage = 1
        _state.update {
            it.copy(items = emptyList(), endReached = false, error = null, isInitialLoad = true, isAppending = false)
        }
        loadProcessing()
        loadJob = viewModelScope.launch {
            when (val result = downloadsRepository.getDownloadsPage(p, 1)) {
                is DataResult.Success -> {
                    nextPage = 2
                    firstPageCount = result.data.items.size
                    lastListLoad = SystemClock.elapsedRealtime()
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
                    checkFollows(result.data.items)
                }
                is DataResult.Error -> {
                    _state.update { it.copy(isInitialLoad = false, error = result.message) }
                }
            }
        }
    }

    /**
     * Re-reads page 1 in place: new items appear at the top and finished ones
     * that were deleted drop out, without clearing the list, showing a spinner
     * or losing the scroll position. Pages loaded further down are kept.
     */
    private fun refreshQuietly() {
        val p = provider ?: return
        val s = _state.value
        // A full load, a page append or the search fill owns the list right now.
        if (s.isInitialLoad || lastListLoad == 0L) return
        if (loadJob?.isActive == true || searchJob?.isActive == true || quietJob?.isActive == true) return
        quietJob = viewModelScope.launch {
            val result = downloadsRepository.getDownloadsPage(p, 1)
            if (result !is DataResult.Success || p != provider) return@launch
            val fresh = result.data.items
            _state.update { current ->
                val onlyFirstPage = current.items.size <= firstPageCount
                val merged = mergeFirstPage(current.items, firstPageCount, fresh) { it.id }
                current.copy(
                    items = merged,
                    error = null,
                    endReached = if (onlyFirstPage) !result.data.hasMore else current.endReached
                )
            }
            firstPageCount = fresh.size
            lastListLoad = SystemClock.elapsedRealtime()
            checkFollows(fresh)
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

    /**
     * Premium about to run out on ANY signed-in service, not just the one
     * shown: with several services, the others lapse unnoticed otherwise.
     * Warns about the one closest to expiring; Renew opens that service.
     */
    private fun checkExpiry(active: ProviderId) {
        viewModelScope.launch {
            val ids = ProviderId.entries.filter { it in auth.signedInProviders.first() }
            val soonest = ids.mapNotNull { id ->
                (auth.account(id) as? DataResult.Success)?.data?.daysLeft?.let { days -> id to days }
            }
                // Long-expired accounts that are merely still signed in would
                // nag forever: only the shown service warns once expired.
                .filter { (id, days) -> days >= 0 || id == active }
                .minByOrNull { it.second } ?: return@launch
            val (id, days) = soonest
            val name = auth.info(id).displayName
            val msg = when {
                days < 0 -> str(R.string.expiry_expired, name)
                days == 0L -> str(R.string.expiry_today, name)
                days <= 5 -> appContext.resources.getQuantityString(R.plurals.expiry_in_days, days.toInt(), days.toInt(), name)
                else -> null
            } ?: return@launch
            if (active == provider) _state.update { it.copy(expiryWarning = msg, renewUrl = auth.info(id).websiteUrl) }
        }
    }

    fun dismissExpiryWarning() = _state.update { it.copy(expiryWarning = null) }

    private fun loadProcessing() {
        provider ?: return
        processingJob?.cancel()
        processingJob = viewModelScope.launch { updateProcessing() }
    }

    /**
     * Refreshes the processing strip. Returns true when a job that was
     * processing has left the list, i.e. it finished (or was deleted), so its
     * files should now be in the library. Failures are non-fatal: the strip
     * just stays as it was.
     */
    private suspend fun updateProcessing(): Boolean {
        val p = provider ?: return false
        val r = downloadsRepository.listProcessing(p)
        if (r !is DataResult.Success || p != provider) return false
        val before = _processing.value.mapTo(HashSet()) { it.ref }
        _processing.value = r.data
        val after = r.data.mapTo(HashSet()) { it.ref }
        return (before - after).isNotEmpty()
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
     * Called from the screen while it's visible (repeatOnLifecycle), so it
     * stops in the background and restarts on return.
     *
     *  - On return: catch up at once if the list is more than a few seconds old.
     *  - Something processing or watched: check the processing list every
     *    [BUSY_POLL_MS]; when a job finishes, its files appear immediately.
     *  - Otherwise: re-read the newest page every [IDLE_POLL_MS], so items
     *    added on the service's website (or finished there) show up without a
     *    manual refresh.
     *
     * The intervals are deliberately not shorter: TorBox's uncached list call
     * is its most expensive endpoint and is rate-limited per key.
     */
    suspend fun pollWhileVisible() {
        if (lastListLoad != 0L && sinceListLoad() > RESUME_STALE_MS) {
            updateProcessing()
            refreshQuietly()
        }
        while (true) {
            val busy = _processing.value.isNotEmpty() || watchedJobs.value.isNotEmpty()
            delay(if (busy) BUSY_POLL_MS else IDLE_POLL_MS)
            val finished = updateProcessing()
            val ready = if (watchedJobs.value.isNotEmpty()) watcher.checkNow(fromBackground = false) else emptyList()
            if (ready.isNotEmpty()) {
                _events.send(
                    if (ready.size == 1) str(R.string.msg_ready_one, ready.first())
                    else appContext.resources.getQuantityString(R.plurals.msg_ready_many, ready.size, ready.size)
                )
            }
            if (finished || ready.isNotEmpty() || sinceListLoad() >= IDLE_POLL_MS) refreshQuietly()
        }
    }

    private fun sinceListLoad(): Long = SystemClock.elapsedRealtime() - lastListLoad

    // --- removing from the service -------------------------------------------

    private fun keyOf(item: DownloadItem): String? = downloadsRepository.removalKey(item.provider, item.id)

    /** True if the service can remove at least one of [items]. */
    fun canRemove(items: List<DownloadItem>): Boolean = items.any { keyOf(it) != null }

    /**
     * Deletes the torrents/entries holding [items] from the service. Files the
     * phone already downloaded stay; unfinished downloads of them are
     * cancelled (their links would stop working anyway).
     */
    fun removeFromService(items: List<DownloadItem>) {
        val p = provider ?: return
        val keys = items.filter { it.provider == p }.mapNotNull { keyOf(it) }.toSet()
        if (keys.isEmpty()) return
        viewModelScope.launch { removeKeys(p, keys) }
    }

    /** Removes a job that's still processing (e.g. a stalled torrent). */
    fun removeJob(job: RemoteJob) {
        viewModelScope.launch { removeKeys(job.provider, setOf(job.ref)) }
    }

    private suspend fun removeKeys(p: ProviderId, keys: Set<String>) {
        val removed = mutableSetOf<String>()
        var error: String? = null
        keys.forEach { key ->
            when (val r = downloadsRepository.remove(p, key)) {
                is DataResult.Success -> removed += key
                is DataResult.Error -> error = r.message
            }
        }
        if (removed.isNotEmpty()) {
            val gone = { item: DownloadItem -> item.provider == p && keyOf(item) in removed }
            val states = downloadStates.value
            _state.value.items.filter(gone).forEach { item ->
                val st = states[item.id]
                if (st != null && st != DownloadState.COMPLETED) downloadController.cancel(item.id)
            }
            // Keep the quiet-refresh bookkeeping in step with what's shown
            // (outside update{}: its lambda may run more than once).
            val goneFromFirstPage = _state.value.items.take(firstPageCount).count(gone)
            _state.update { s -> s.copy(items = s.items.filterNot(gone)) }
            firstPageCount = (firstPageCount - goneFromFirstPage).coerceAtLeast(0)
            _processing.update { jobs -> jobs.filterNot { it.provider == p && it.ref in removed } }
        }
        _events.send(error ?: appContext.resources.getQuantityString(
            R.plurals.msg_removed_n, removed.size, removed.size, auth.info(p).displayName
        ))
    }

    /** Resolve a streamable link and hand it to the player. */
    fun play(item: DownloadItem, title: String) {
        viewModelScope.launch {
            val url = item.downloadUrl ?: when (val r = downloadsRepository.resolveLink(item.provider, item.sourceRef)) {
                is DataResult.Success -> r.data.url
                is DataResult.Error -> {
                    _events.send(r.message)
                    return@launch
                }
            }
            val info = com.abhinavxt.debforge.domain.ReleaseNameParser.parse(item.filename)
            val pool = _state.value.items
            upNext.set(item.id, upNextFor(item, pool))
            _playRequests.send(
                PlayRequest(
                    Uri.parse(url),
                    title,
                    item = item,
                    showKey = info.groupKey.takeIf { info.kind == com.abhinavxt.debforge.domain.MediaKind.SHOW },
                    subtitles = subtitleResolver.resolve(com.abhinavxt.debforge.domain.Subtitles.forVideo(item, pool))
                )
            )
        }
    }

    // --- continue watching -------------------------------------------------------

    /** Every saved position by item id (progress bars, "Watched"). */
    val playback: StateFlow<Map<String, com.abhinavxt.debforge.data.playback.PlaybackEntity>> = playbackPositions.all
        .map { list -> list.associateBy { it.itemId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Started but not finished, most recent first. */
    val continueWatching: StateFlow<List<com.abhinavxt.debforge.data.playback.PlaybackEntity>> = playbackPositions.all
        .map { list -> com.abhinavxt.debforge.domain.Resume.continueWatching(list, { it.resumeEntry }) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Plays a "Continue watching" entry: gets a fresh link (the old one has
     * likely expired) from the service it came from, even if that isn't the
     * one the Library is showing.
     */
    fun playSaved(entry: com.abhinavxt.debforge.data.playback.PlaybackEntity) {
        viewModelScope.launch {
            val saved = DownloadItem(
                id = entry.itemId,
                provider = entry.provider,
                filename = entry.filename,
                sourceRef = entry.sourceRef,
                downloadUrl = null,
                host = "",
                filesize = 0,
                maxConnections = 1,
                addedAt = null,
                parentRef = entry.parentRef
            )
            when (val r = downloadsRepository.resolveLink(entry.provider, entry.sourceRef)) {
                is DataResult.Success -> {
                    // Its torrent's files: from the loaded Library, or asked
                    // from the service (the file may be pages away, or on a
                    // service the Library isn't showing).
                    val loaded = _state.value.items
                    val known = loaded.firstOrNull { it.id == entry.itemId }
                    val item = known ?: saved
                    val pool = if (known != null) loaded else {
                        entry.parentRef
                            ?.let { ref -> (downloadsRepository.filesForJob(entry.provider, ref) as? DataResult.Success)?.data }
                            .orEmpty() + loaded
                    }
                    upNext.set(item.id, upNextFor(item, pool))
                    _playRequests.send(
                        PlayRequest(
                            Uri.parse(r.data.url), entry.title, item, entry.showKey,
                            subtitleResolver.resolve(com.abhinavxt.debforge.domain.Subtitles.forVideo(item, pool))
                        )
                    )
                }
                // Most likely removed from the service; the user can drop it (long-press).
                is DataResult.Error -> _events.send(str(R.string.continue_unavailable, entry.title, r.message))
            }
        }
    }

    /**
     * Episodes after [video] from [pool] (same show and service), best copy
     * of each, with their subtitle files, for the player's "Next episode".
     */
    private fun upNextFor(video: DownloadItem, pool: List<DownloadItem>): List<com.abhinavxt.debforge.player.QueuedEpisode> {
        val parse = com.abhinavxt.debforge.domain.ReleaseNameParser::parse
        val candidates = com.abhinavxt.debforge.domain.ExtraFiles.hide(pool)
            .map { com.abhinavxt.debforge.domain.NextEpisode.Candidate(it, parse(it.filename)) }
        return com.abhinavxt.debforge.domain.NextEpisode.upcoming(video, parse(video.filename), candidates).map { c ->
            com.abhinavxt.debforge.player.QueuedEpisode(
                item = c.item,
                title = c.info.displayTitle,
                showKey = c.info.groupKey,
                subtitles = com.abhinavxt.debforge.domain.Subtitles.forVideo(c.item, pool)
            )
        }
    }

    fun forgetPlayback(entry: com.abhinavxt.debforge.data.playback.PlaybackEntity) {
        viewModelScope.launch { playbackPositions.forget(entry.itemId) }
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

    /** No TMDB key and the user hasn't closed the "Turn on posters" banner. */
    val showPosterPrompt: StateFlow<Boolean> = combine(
        metadata.enabled, settingsStore.posterPromptDismissedFlow
    ) { enabled, dismissed -> !enabled && !dismissed }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun dismissPosterPrompt() {
        viewModelScope.launch { settingsStore.dismissPosterPrompt() }
    }

    /** The setup sheet saved a key; artwork loads as cards recompose. */
    fun onPostersEnabled() {
        viewModelScope.launch { _events.send(str(R.string.poster_setup_done)) }
    }

    // --- following shows ------------------------------------------------------

    /** "PROVIDER|show:key" of every followed show. */
    val followed: StateFlow<Set<String>> = followStore.follows
        .map { list -> list.mapTo(HashSet()) { "${it.provider.name}|${it.showKey}" } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    fun isFollowed(group: TitleGroup): Boolean =
        group.files.firstOrNull()?.item?.provider?.let { "${it.name}|${group.info.groupKey}" in followed.value } == true

    /**
     * Follow / unfollow a show from its title sheet. Following means new
     * episodes from now on: what's already in the account isn't queued.
     * The quality to prefer is the one most of the user's files have.
     */
    fun toggleFollow(group: TitleGroup) {
        val p = group.files.firstOrNull()?.item?.provider ?: return
        val key = group.info.groupKey
        viewModelScope.launch {
            if (isFollowed(group)) {
                followStore.unfollow(p, key)
                _events.send(str(R.string.follow_stopped, group.title))
            } else {
                val resolution = group.files.mapNotNull { it.info.resolution }
                    .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
                followStore.follow(
                    com.abhinavxt.debforge.domain.Follow(
                        provider = p,
                        showKey = key,
                        title = group.title,
                        since = System.currentTimeMillis(),
                        resolution = resolution
                    )
                )
                _events.send(str(R.string.follow_started, group.title))
            }
            com.abhinavxt.debforge.download.follow.FollowJobService.sync(appContext, followStore.all().isNotEmpty())
        }
    }

    /** New episodes among files the Library just loaded (no extra request). */
    private fun checkFollows(items: List<DownloadItem>) {
        viewModelScope.launch {
            val queued = runCatching { followChecker.checkLoaded(items) }.getOrDefault(emptyList())
            if (queued.isNotEmpty()) {
                _events.send(
                    if (queued.size == 1) str(R.string.follow_queued_one, queued.first())
                    else appContext.resources.getQuantityString(R.plurals.follow_queued_many, queued.size, queued.size)
                )
            }
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

    /**
     * Show torrent extras (release-group .txt, covers, samples). The screen
     * hides them from what it shows; [BrowseUiState.items] always holds every
     * file so paging and quiet refreshes keep counting real pages.
     */
    /** Play in DebForge's player (else hand the link to VLC & co.). */
    val useInternalPlayer: StateFlow<Boolean> = settingsStore.internalPlayerFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val showExtraFiles: StateFlow<Boolean> = settingsStore.showExtraFilesFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val downloadDir: StateFlow<String> = settingsStore.downloadDirFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, SettingsStore.DEFAULT_DOWNLOAD_DIR)

    /** Switch the download folder to app storage, then queue what was waiting. */
    /** Files the user wanted when asked to choose a folder; queued once it's picked. */
    private var awaitingFolder: List<DownloadItem> = emptyList()

    fun awaitFolderFor(items: List<DownloadItem>) {
        awaitingFolder = items
    }

    /** Folder-picker result; null = backed out (nothing is queued). */
    fun onFolderPicked(treeUri: String?) {
        val items = awaitingFolder
        awaitingFolder = emptyList()
        if (treeUri != null) useFolder(treeUri, items)
    }

    /** A folder picked with the system folder picker (tree URI). */
    fun useFolder(treeUri: String, pending: List<DownloadItem>) {
        viewModelScope.launch {
            settingsStore.setDownloadDir(treeUri)
            _events.send(str(R.string.msg_folder_set, StorageAccess.label(appContext, treeUri)))
            enqueueAll(pending)
        }
    }

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

    /**
     * "Not enough space" before queueing: what was about to be queued, what
     * it (plus everything already waiting) needs, and what's free there.
     */
    data class SpaceWarning(
        val items: List<DownloadItem>,
        val needed: Long,
        val available: Long,
        val folder: String
    )

    private val _spaceWarning = MutableStateFlow<SpaceWarning?>(null)
    val spaceWarning: StateFlow<SpaceWarning?> = _spaceWarning.asStateFlow()

    /**
     * "Download all" / "Download season" / a single file. Checks free space
     * first; if it's short, asks via [spaceWarning] instead of queueing.
     */
    fun enqueueAll(items: List<DownloadItem>) {
        if (items.isEmpty()) return
        viewModelScope.launch {
            val warning = spaceWarningFor(items)
            if (warning != null) _spaceWarning.value = warning else enqueueNow(items)
        }
    }

    fun enqueue(item: DownloadItem) = enqueueAll(listOf(item))

    /** "Queue anyway": files that don't fit stop with a clear error, others run. */
    fun confirmSpaceWarning() {
        val w = _spaceWarning.value ?: return
        _spaceWarning.value = null
        viewModelScope.launch { enqueueNow(w.items) }
    }

    fun dismissSpaceWarning() {
        _spaceWarning.value = null
    }

    private suspend fun spaceWarningFor(items: List<DownloadItem>): SpaceWarning? {
        val tracked = downloadStates.value
        val fresh = items.filter { it.id !in tracked }
        if (fresh.isEmpty()) return null
        val dir = settingsStore.downloadDirFlow.first()
        val available = withContext(Dispatchers.IO) { StorageSpace.availableBytes(appContext, dir) }
        // Everything already waiting goes to the same place first.
        val needed = fresh.sumOf { it.filesize.coerceAtLeast(0) } + downloadDao.remainingPendingBytes()
        Housekeeping.shortfall(needed, available) ?: return null
        return SpaceWarning(fresh, needed, available ?: 0, StorageAccess.label(appContext, dir))
    }

    private suspend fun enqueueNow(items: List<DownloadItem>) {
        items.forEach { downloadController.enqueue(it) }
        // BUFFERED channel; send() suspends only if the buffer is full, which
        // is effectively never for a snackbar pipe.
        _events.send(
            if (items.size == 1) str(R.string.msg_added_one, items.first().filename)
            else appContext.resources.getQuantityString(R.plurals.added_n_files, items.size, items.size)
        )
    }

    private fun str(id: Int, vararg args: Any): String = appContext.getString(id, *args)

    companion object {
        /** Processing-strip check while something is downloading on the service. */
        private const val BUSY_POLL_MS = 10_000L
        /** Newest-page check when nothing is processing (catches website adds). */
        private const val IDLE_POLL_MS = 60_000L
        /** Returning to the screen with an older list than this refreshes at once. */
        private const val RESUME_STALE_MS = 15_000L
        private const val SEARCH_DEBOUNCE_MS = 400L
        /** Stop auto-paging past this many files (very large accounts). */
        private const val SEARCH_CAP = 5_000
    }
}
