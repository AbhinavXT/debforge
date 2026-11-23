package com.abhinavxt.debforge.ui.youtube

import android.content.Context
import android.os.Build
import androidx.lifecycle.SavedStateHandle
import androidx.compose.foundation.lazy.LazyListState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.abhinavxt.debforge.data.search.YouTubeSearchHistoryStore
import com.abhinavxt.debforge.data.youtube.YouTubeResolver
import com.abhinavxt.debforge.di.ApiHttpClient
import com.abhinavxt.debforge.domain.DownloadItem
import com.abhinavxt.debforge.domain.DefaultQuality
import com.abhinavxt.debforge.domain.FormatChoice
import com.abhinavxt.debforge.domain.YouTubeFormats
import com.abhinavxt.debforge.domain.pickDefaultChoice
import com.abhinavxt.debforge.domain.YouTubeLinks
import com.abhinavxt.debforge.domain.YouTubeSearchResult
import com.abhinavxt.debforge.domain.YouTubeSubtitle
import com.abhinavxt.debforge.domain.YouTubeVideo
import com.abhinavxt.debforge.download.DownloadController
import com.abhinavxt.debforge.download.StorageAccess
import com.abhinavxt.debforge.download.youtube.EnqueueMode
import com.abhinavxt.debforge.download.youtube.EnqueueResult
import com.abhinavxt.debforge.download.youtube.YouTubeEnqueuer
import com.abhinavxt.debforge.player.AudioPlayer
import com.abhinavxt.debforge.player.youtubeAudioTrack
import com.abhinavxt.debforge.player.youtube.YouTubeStreaming
import com.abhinavxt.debforge.ui.browse.PlayRequest
import com.abhinavxt.debforge.ui.video.ResultTap
import com.abhinavxt.debforge.ui.video.resultTap
import com.abhinavxt.debforge.ui.browse.youTubePlayRequest
import com.abhinavxt.debforge.domain.StreamKind
import com.abhinavxt.debforge.data.prefs.SettingsStore
import com.abhinavxt.debforge.data.playback.AudioLists
import com.abhinavxt.debforge.data.playback.Playlist
import com.abhinavxt.debforge.data.playback.YouTubeAddResult
import com.abhinavxt.debforge.data.playback.YouTubeMeta
import com.abhinavxt.debforge.player.EnqueueOutcome
import com.abhinavxt.debforge.data.playback.YouTubeMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.Page
import javax.inject.Inject

sealed interface YouTubeUi {
    data object Empty : YouTubeUi
    data object Resolving : YouTubeUi
    data class Preview(val video: YouTubeVideo, val choices: List<FormatChoice>, val selected: FormatChoice, val subtitle: YouTubeSubtitle? = null) : YouTubeUi
    /** A search ([query] is the text) or, when [channelUrl] is set, a channel's videos ([query] is the channel name). */
    data class Results(val query: String, val items: List<YouTubeSearchResult>, val loadingMore: Boolean = false, val channelUrl: String? = null,
        /** The last page load failed: the list stops asking until the user scrolls again. */
        val moreFailed: Boolean = false,
    ) : YouTubeUi
    /** [progress] is (current, total) while the selected videos are being added one by one. */
    data class Playlist(
        val title: String,
        val items: List<YouTubeSearchResult>,
        val selected: Set<String>,
        val quality: DefaultQuality,
        val progress: Pair<Int, Int>? = null,
    ) : YouTubeUi
    data class Error(val kind: YouTubeErrorKind, val search: Boolean = false, val channel: Boolean = false) : YouTubeUi
}

sealed interface YouTubeEvent {
    data object QUEUED : YouTubeEvent
    data object ALREADY_QUEUED : YouTubeEvent
    data object QUEUE_FAILED : YouTubeEvent
    /** The video was added but its chosen subtitle could not be. */
    data object QUEUED_NO_SUBTITLE : YouTubeEvent
    /** The video was already in downloads; the chosen subtitle was added. */
    data object SUBTITLE_ADDED : YouTubeEvent
    /** A playlist batch finished (not cancelled). */
    data class BatchDone(val added: Int, val already: Int, val failed: Int) : YouTubeEvent
    /** Following a channel failed (unreadable channel or no connection). */
    data object FollowFailed : YouTubeEvent
    /** Playing without downloading failed before it started. */
    data class StreamFailed(val kind: YouTubeErrorKind) : YouTubeEvent
    /** A related video went to the end of the play queue. */
    data object AddedToPlayQueue : YouTubeEvent
    /** A related video was already in the play queue. */
    data object AlreadyInPlayQueue : YouTubeEvent
    /** Result of adding a video to playlist [listName]. */
    data class PlaylistAdd(val result: YouTubeAddResult, val listName: String, val mode: YouTubeMode = YouTubeMode.AUDIO) : YouTubeEvent
}

/** A YouTube stream to play, with the video it came from (the watch page shows its related videos). */
data class StreamPlay(val request: PlayRequest, val video: YouTubeVideo)

/** What to tell the user after the video's own enqueue [video] and the chosen subtitle's [sub] (null: none chosen). */
internal fun downloadOutcome(video: YouTubeEvent, sub: YouTubeEvent?): YouTubeEvent = when (video) {
    YouTubeEvent.QUEUED -> if (sub == YouTubeEvent.QUEUE_FAILED) YouTubeEvent.QUEUED_NO_SUBTITLE else YouTubeEvent.QUEUED
    YouTubeEvent.ALREADY_QUEUED -> when (sub) {
        YouTubeEvent.QUEUED -> YouTubeEvent.SUBTITLE_ADDED
        YouTubeEvent.QUEUE_FAILED -> YouTubeEvent.QUEUE_FAILED // nothing was added and the subtitle was the reason to ask
        else -> YouTubeEvent.ALREADY_QUEUED
    }
    else -> video
}

@HiltViewModel
class YouTubeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val resolver: YouTubeResolver,
    private val downloads: DownloadController,
    private val enqueuer: YouTubeEnqueuer,
    private val settings: SettingsStore,
    private val history: YouTubeSearchHistoryStore,
    private val saved: SavedStateHandle,
    @ApiHttpClient private val http: OkHttpClient,
    private val audioPlayer: AudioPlayer,
    private val audioLists: AudioLists,
    private val subscriptions: com.abhinavxt.debforge.data.subscriptions.SubscriptionRepository,
) : ViewModel() {
    private val _ui = MutableStateFlow<YouTubeUi>(YouTubeUi.Empty)
    val ui: StateFlow<YouTubeUi> = _ui.asStateFlow()

    /** What the paste field shows; also set when a shared link is offered. */
    private val _input = MutableStateFlow("")
    val input: StateFlow<String> = _input.asStateFlow()

    /** Recent word searches, newest first, for the empty screen. */
    // Eagerly: the ViewModel exists from app start, so the list is already read when the tab is first shown (no one-frame pop-in).
    val recent: StateFlow<List<String>> = history.recent
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun removeRecent(query: String) { viewModelScope.launch { history.remove(query) } }
    fun clearRecent() { viewModelScope.launch { history.clear() } }

    private val _events = Channel<YouTubeEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private val _playRequests = Channel<StreamPlay>(Channel.BUFFERED)
    /** Video streams for the screen to open in the player. */
    val playRequests = _playRequests.receiveAsFlow()

    private val _streamingUrl = MutableStateFlow<String?>(null)
    /** The result row (by url) whose play icon is waiting for the video to resolve. */
    val streamingUrl: StateFlow<String?> = _streamingUrl.asStateFlow()
    private var streamJob: Job? = null

    private var job: Job? = null
    private var enqueueJob: Job? = null
    private var batchJob: Job? = null
    private var moreJob: Job? = null
    /** Extractor cursor for the shown search results; null when there are no more. */
    private var nextPage: Page? = null

    /** New uploads from followed channels, for the empty screen's "New" row. */
    val newItems: StateFlow<List<com.abhinavxt.debforge.data.subscriptions.NewFeedItem>> = subscriptions.newItems(20)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Canonical urls of the followed channels. */
    val followedUrls: StateFlow<Set<String>> = subscriptions.subscriptions
        .map { list -> list.map { it.url }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    private val _followBusy = MutableStateFlow<String?>(null)
    /** The channel url (as the page was opened) whose follow request is running (reading the channel's feed). */
    val followBusy: StateFlow<String?> = _followBusy.asStateFlow()

    private val _canonicalByPage = MutableStateFlow<Map<String, String>>(emptyMap())
    /** Canonical channel urls of channel pages opened with a @handle, /c/ or /user/ link, which do not name the channel themselves. */
    val canonicalByPage: StateFlow<Map<String, String>> = _canonicalByPage.asStateFlow()

    private fun rememberCanonical(pageUrl: String, canonical: String) { _canonicalByPage.update { it + (pageUrl to canonical) } }

    /** Resolves a channel page's canonical url once, so its Follow button reflects the real follow state. */
    private fun resolveCanonical(pageUrl: String) {
        if (com.abhinavxt.debforge.domain.YouTubeChannelLinks.canonicalUrl(pageUrl) != null || pageUrl in _canonicalByPage.value) return
        viewModelScope.launch { subscriptions.canonicalChannelUrl(pageUrl)?.let { rememberCanonical(pageUrl, it) } }
    }

    /** Follows the channel page's channel, or unfollows it when it is already followed. */
    fun toggleFollow(channelUrl: String) {
        if (_followBusy.value == channelUrl) return
        _followBusy.value = channelUrl
        viewModelScope.launch {
            try {
                val canonical = com.abhinavxt.debforge.domain.YouTubeChannelLinks.canonicalUrl(channelUrl)
                    ?: _canonicalByPage.value[channelUrl] ?: subscriptions.canonicalChannelUrl(channelUrl)
                val existing = canonical?.let { subscriptions.followedChannel(it) }
                if (existing != null) {
                    rememberCanonical(channelUrl, existing.url)
                    subscriptions.unfollow(existing.id)
                } else when (val r = subscriptions.follow(channelUrl)) {
                    is com.abhinavxt.debforge.data.subscriptions.SubscriptionRepository.FollowResult.Followed -> rememberCanonical(channelUrl, r.sub.url)
                    // Already followed (e.g. under another link form): the button now shows Following.
                    is com.abhinavxt.debforge.data.subscriptions.SubscriptionRepository.FollowResult.AlreadyFollowing -> rememberCanonical(channelUrl, r.sub.url)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.trySend(YouTubeEvent.FollowFailed)
            } finally {
                if (_followBusy.value == channelUrl) _followBusy.value = null
            }
        }
    }

    /** Opens a "New" tile like a search result, and marks it seen. */
    fun openNew(item: com.abhinavxt.debforge.data.subscriptions.NewFeedItem) {
        // The item key is the video id, so a tile without a stored url still opens.
        val url = item.mediaUrl ?: "https://www.youtube.com/watch?v=${item.itemKey}"
        viewModelScope.launch { subscriptions.markSeen(item.subId, item.itemKey) }
        pick(YouTubeSearchResult(url, item.title, item.subTitle, item.durationSec ?: 0, item.thumbUrl))
    }

    fun markAllSeen() { viewModelScope.launch { subscriptions.markAllSeen() } }

    private val _trending = MutableStateFlow<List<YouTubeSearchResult>>(emptyList())
    /** Videos for the empty screen; stays empty when loading failed. */
    val trending: StateFlow<List<YouTubeSearchResult>> = _trending.asStateFlow()
    private var trendingStarted = false

    /** Called whenever the empty screen is shown (the ViewModel itself exists from app start); fetches until one fetch succeeds, one at a time. */
    fun loadTrending() {
        if (trendingStarted) return
        trendingStarted = true
        viewModelScope.launch {
            try {
                _trending.value = resolver.trending()
            } catch (e: CancellationException) {
                trendingStarted = false
                throw e
            } catch (e: Exception) {
                trendingStarted = false // offline at launch: the next time the empty screen shows, try again
            }
        }
    }

    init {
        // Process death: bring the preview back for the last link the user submitted.
        saved.get<String>(LINK_KEY)?.let { run(it, record = false) }
    }

    fun setInput(text: String) {
        // Emptying the field over a search's results (the X) goes back to the empty screen rather than leaving stale results under an empty field.
        val cur = _ui.value
        if (text.isEmpty() && cur is YouTubeUi.Results && cur.channelUrl == null) { reset(); return }
        _input.value = text
    }

    /** Called by the shell with a shared link. */
    fun offer(link: String) = submit(link)

    /** The results a tapped result came from, so Back returns there; cleared by any new submit. */
    private var fromResults: YouTubeUi.Results? = null

    /** Previews left through an "Up next" tap (with the link in the field), newest last: Back steps through them before [fromResults]. */
    private val previewBack = ArrayDeque<Pair<YouTubeUi.Preview, String>>()

    /** An open channel list: [back] is the screen it was opened from (null: the empty screen). */
    private class ChannelNav(val name: String, val url: String, val back: Back?, val origin: String? = null)
    /** A screen to return to: its state, the field and saved link that went with it, and the results a preview came from. */
    private class Back(val ui: YouTubeUi, val input: String, val link: String?, val fromResults: YouTubeUi.Results?, val channel: ChannelNav?, val next: Page?, val list: LazyListState,
        /** The "Up next" previews that were behind a preview when a channel was opened from it. */
        val previews: List<Pair<YouTubeUi.Preview, String>> = emptyList())
    private var channel: ChannelNav? = null

    private val _returnTo = Channel<String>(Channel.BUFFERED)
    /** Emits the tab a channel page was opened from (another tab) once Back leaves that page: the shell returns there. */
    val returnTo = _returnTo.receiveAsFlow()

    fun submit(text: String) = run(text, record = true)

    /** [record]: a word search is added to the recent searches (not when restoring after process death). */
    private fun run(text: String, record: Boolean) {
        fromResults = null
        previewBack.clear()
        channel = null
        _input.value = text
        saved[LINK_KEY] = text
        job?.cancel()
        moreJob?.cancel()
        nextPage = null
        batchJob?.cancel()
        pending = null // a download waiting on the folder prompt belongs to the screen being left
        when {
            YouTubeLinks.isPlaylistOnly(text) && YouTubeLinks.playlistUrl(text) != null -> loadPlaylist(text)
            looksLikeYouTube(text) -> resolve(text)
            YouTubeLinks.isSearchQuery(text) -> {
                if (record) YouTubeLinks.historyQuery(text)?.let { q -> viewModelScope.launch { history.add(q) } }
                search(text.trim())
            }
            else -> _ui.value = YouTubeUi.Error(YouTubeErrorKind.NOT_LINK)
        }
    }

    /** Held here so the results keep their scroll position while a tapped result's preview is open. */
    var resultsList = LazyListState()
        private set

    private fun search(query: String) {
        resultsList = LazyListState()
        _ui.value = YouTubeUi.Resolving
        job = viewModelScope.launch {
            _ui.value = try {
                val page = resolver.search(query)
                nextPage = page.next
                YouTubeUi.Results(query, page.items)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ensureActive() // a cancelled job must not overwrite the state Back just set
                YouTubeUi.Error(errorKind(e), search = true)
            }
        }
    }

    /** Appends the next page of results; a failure keeps the list and the next scroll tries again. */
    fun loadMore() {
        val r = _ui.value as? YouTubeUi.Results ?: return
        val page = nextPage ?: return
        if (r.loadingMore) return
        val channelUrl = r.channelUrl
        _ui.value = r.copy(loadingMore = true, moreFailed = false)
        moreJob = viewModelScope.launch {
            try {
                val more = if (channelUrl != null) resolver.channelMore(channelUrl, page) else resolver.searchMore(r.query, page)
                nextPage = more.next
                _ui.update { cur ->
                    if (cur !is YouTubeUi.Results || cur.query != r.query || cur.channelUrl != channelUrl) cur
                    else { val seen = cur.items.mapTo(HashSet()) { it.url }; cur.copy(items = cur.items + more.items.filter { it.url !in seen }, loadingMore = false) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ensureActive()
                _ui.update { if (it is YouTubeUi.Results) it.copy(loadingMore = false, moreFailed = true) else it }
            }
        }
    }

    private fun loadPlaylist(text: String) {
        _ui.value = YouTubeUi.Resolving
        job = viewModelScope.launch {
            _ui.value = try {
                val list = resolver.playlist(text)
                val quality = settings.youTubeQualityFlow.first().takeIf { it != DefaultQuality.ASK } ?: DefaultQuality.P720
                YouTubeUi.Playlist(list.title, list.items, list.items.map { it.url }.toSet(), quality)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ensureActive() // a cancelled job must not overwrite the state Back just set
                YouTubeUi.Error(errorKind(e))
            }
        }
    }

    fun togglePlaylistItem(url: String) = updatePlaylist { it.copy(selected = if (url in it.selected) it.selected - url else it.selected + url) }

    fun selectAllPlaylist(all: Boolean) = updatePlaylist { it.copy(selected = if (all) it.items.map { r -> r.url }.toSet() else emptySet()) }

    fun setPlaylistQuality(q: DefaultQuality) = updatePlaylist { it.copy(quality = q) }

    private fun updatePlaylist(f: (YouTubeUi.Playlist) -> YouTubeUi.Playlist) =
        _ui.update { if (it is YouTubeUi.Playlist && it.progress == null) f(it) else it }

    /**
     * Opens a channel's videos from a result row, a preview, a playlist or another tab; Back returns to where it was opened
     * (a search, a preview, a playlist or another channel), or to the empty screen from anywhere else. Each Back is one step.
     * [origin] is the tab it was opened from when that is not the YouTube tab. Returns false when ignored: a playlist
     * download is running and must not be stopped by a tap elsewhere.
     */
    fun openChannel(name: String, url: String, origin: String? = null): Boolean {
        val cur = _ui.value
        if (cur is YouTubeUi.Playlist && cur.progress != null) return false
        if (cur is YouTubeUi.Results && cur.channelUrl == url) return true // already there
        job?.cancel()
        moreJob?.cancel()
        pending = null // a download waiting on the folder prompt belongs to the screen being left
        val back = when {
            cur is YouTubeUi.Results ->
                Back(cur.copy(loadingMore = false), _input.value, saved.get<String>(LINK_KEY), null, channel, nextPage, resultsList)
            cur is YouTubeUi.Preview || cur is YouTubeUi.Playlist ->
                Back(cur, _input.value, saved.get<String>(LINK_KEY), fromResults, channel, nextPage, resultsList, previewBack.toList())
            else -> null // empty, loading or an error
        }
        fromResults = null
        previewBack.clear()
        loadChannel(ChannelNav(name, url, back, origin))
        return true
    }

    private fun loadChannel(nav: ChannelNav) {
        channel = nav
        nextPage = null
        resultsList = LazyListState()
        _input.value = ""
        _ui.value = YouTubeUi.Resolving
        job = viewModelScope.launch {
            _ui.value = try {
                val page = resolver.channelVideos(nav.url)
                nextPage = page.next
                resolveCanonical(nav.url)
                YouTubeUi.Results(nav.name, page.items, channelUrl = nav.url)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ensureActive() // a cancelled job must not overwrite the state Back just set
                YouTubeUi.Error(errorKind(e), search = true, channel = true)
            }
        }
    }

    private val isTv by lazy {
        (context.getSystemService(Context.UI_MODE_SERVICE) as android.app.UiModeManager).currentModeType ==
            android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
    }

    /**
     * A tapped search, channel or trending result. A phone or tablet plays it straight away on the watch page at the
     * default quality ("ask every time": the quality sheet first); a TV opens the preview, as does a pasted link.
     */
    fun pick(result: YouTubeSearchResult) {
        val results = _ui.value as? YouTubeUi.Results
        if (results == null && _ui.value !is YouTubeUi.Empty) return
        startResult(result, viaPlayIcon = false)
    }

    /**
     * Starts a tapped result: resolves it, then plays it (or asks for its quality, or opens the preview). A newer tap
     * replaces one still resolving, as do Back and leaving the tab ([cancelStart]).
     */
    private fun startResult(result: YouTubeSearchResult, viaPlayIcon: Boolean) {
        streamJob?.cancel()
        _streamingUrl.value = result.url
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val tap = resultTap(settings.youTubeQualityFlow.first(), isTv, viaPlayIcon)
                if (tap == ResultTap.PREVIEW) {
                    _streamingUrl.value = null
                    openPreview(result)
                    return@launch
                }
                val video = resolver.resolve(result.url)
                val choices = YouTubeFormats.choices(video.streams, Build.VERSION.SDK_INT, video.durationSec)
                if (tap == ResultTap.ASK) {
                    val first = pickDefaultChoice(choices, DefaultQuality.BEST) ?: choices.firstOrNull()
                    if (first == null) _events.send(YouTubeEvent.StreamFailed(YouTubeErrorKind.UNAVAILABLE))
                    else _ask.value = AskPlay(video, choices, first)
                    return@launch
                }
                val choice = YouTubeStreaming.quickPlayChoice(settings.youTubeQualityFlow.first(), choices)
                if (choice == null || !startStream(video, choice, emptyList())) {
                    _events.send(YouTubeEvent.StreamFailed(YouTubeErrorKind.UNAVAILABLE))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.send(YouTubeEvent.StreamFailed(errorKind(e)))
            } finally {
                // A replaced tap must not clear the spinner of the one replacing it.
                if (streamJob === coroutineContext[Job]) _streamingUrl.value = null
            }
        }
        streamJob = job
        job.start()
    }

    /** Back, or leaving the YouTube tab, while a tapped result is still resolving: it no longer opens. */
    fun cancelStart() {
        if (_streamingUrl.value == null) return
        streamJob?.cancel()
        streamJob = null
        _streamingUrl.value = null
    }

    /** A resolved result waiting for its quality ("ask every time"). */
    data class AskPlay(val video: YouTubeVideo, val choices: List<FormatChoice>, val selected: FormatChoice)

    private val _ask = MutableStateFlow<AskPlay?>(null)
    val ask: StateFlow<AskPlay?> = _ask.asStateFlow()

    fun askSelect(choice: FormatChoice) = _ask.update { it?.copy(selected = choice) }

    fun askMode(audio: Boolean) {
        val a = _ask.value ?: return
        if (a.selected.isAudio == audio) return
        val inMode = a.choices.filter { it.isAudio == audio }
        (inMode.firstOrNull { it.isDefault } ?: inMode.firstOrNull())?.let(::askSelect)
    }

    fun askDismiss() { _ask.value = null }

    fun askPlay() {
        val a = _ask.value ?: return
        _ask.value = null
        if (!startStream(a.video, a.selected, emptyList())) viewModelScope.launch { _events.send(YouTubeEvent.StreamFailed(YouTubeErrorKind.UNAVAILABLE)) }
    }

    private fun openPreview(result: YouTubeSearchResult) {
        val results = _ui.value as? YouTubeUi.Results
        if (results == null && _ui.value !is YouTubeUi.Empty) return
        job?.cancel()
        moreJob?.cancel()
        _input.value = result.url
        saved[LINK_KEY] = result.url
        resolve(result.url)
        previewBack.clear()
        fromResults = results?.copy(loadingMore = false) // a cancelled page load must not leave the spinner on
    }

    /** A tapped "Up next" video: opens its preview, and Back returns to the preview it was tapped on. */
    fun openRelated(result: YouTubeSearchResult) {
        val cur = _ui.value as? YouTubeUi.Preview ?: return
        job?.cancel()
        previewBack.addLast(cur to _input.value)
        _input.value = result.url
        saved[LINK_KEY] = result.url
        resolve(result.url)
    }

    private fun resolve(text: String) {
        _ui.value = YouTubeUi.Resolving
        job = viewModelScope.launch {
            _ui.value = try {
                val video = resolver.resolve(text)
                val choices = YouTubeFormats.choices(video.streams, Build.VERSION.SDK_INT, video.durationSec)
                val pick = pickDefaultChoice(choices, settings.youTubeQualityFlow.first())
                if (pick == null) YouTubeUi.Error(YouTubeErrorKind.UNAVAILABLE) else YouTubeUi.Preview(video, choices, pick)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ensureActive() // a cancelled job must not overwrite the state Back just set
                YouTubeUi.Error(errorKind(e))
            }
        }
    }

    // A failed tapped result retries in place: submit() would drop fromResults and lose Back-to-results.
    fun retry() {
        val nav = channel
        if (fromResults != null || previewBack.isNotEmpty()) { job?.cancel(); resolve(_input.value) }
        else if (nav != null) { job?.cancel(); loadChannel(nav) }
        else submit(_input.value)
    }

    /** X and swipe-down: all the way back to where the first preview was opened from, dropping any "Up next" steps. Back (reset) is one step. */
    fun closePreview() {
        previewBack.clear()
        reset()
    }

    fun reset() {
        job?.cancel()
        moreJob?.cancel()
        batchJob?.cancel()
        pending = null // a download waiting on the folder prompt belongs to the screen being left
        previewBack.removeLastOrNull()?.let { (preview, link) ->
            _input.value = link
            saved[LINK_KEY] = link
            _ui.value = preview
            return
        }
        fromResults?.let {
            fromResults = null
            _input.value = if (it.channelUrl == null) it.query else ""
            saved[LINK_KEY] = it.query
            _ui.value = it
            return
        }
        channel?.let { nav ->
            val back = nav.back
            channel = back?.channel
            nextPage = back?.next
            nav.origin?.let { _returnTo.trySend(it) }
            if (back != null) {
                resultsList = back.list
                fromResults = back.fromResults
                previewBack.clear()
                previewBack.addAll(back.previews)
                _input.value = back.input
                if (back.link != null) saved[LINK_KEY] = back.link else saved.remove<String>(LINK_KEY)
                _ui.value = back.ui
                return
            }
        }
        saved.remove<String>(LINK_KEY)
        _input.value = ""
        _ui.value = YouTubeUi.Empty
    }

    fun select(choice: FormatChoice) {
        (_ui.value as? YouTubeUi.Preview)?.let { _ui.value = it.copy(selected = choice) }
    }

    fun selectSubtitle(s: YouTubeSubtitle?) {
        (_ui.value as? YouTubeUi.Preview)?.let { _ui.value = it.copy(subtitle = s) }
    }

    /** Segmented Video | Audio switch: selects that mode's default (first) row. */
    fun selectMode(audio: Boolean) {
        val p = _ui.value as? YouTubeUi.Preview ?: return
        if (p.selected.isAudio == audio) return
        val inMode = p.choices.filter { it.isAudio == audio }
        (inMode.firstOrNull { it.isDefault } ?: inMode.firstOrNull())?.let { select(it) }
    }

    /** Plays the preview's selected choice: audio in the mini player, video in the player screen. */
    fun play() {
        val p = _ui.value as? YouTubeUi.Preview ?: return
        if (streamJob?.isActive == true) return // double tap
        val subs = listOfNotNull(p.subtitle)
        // Held for a moment so a second tap can't open a second player; a failed start frees it at once.
        streamJob = viewModelScope.launch {
            if (!startStream(p.video, p.selected, subs)) _events.send(YouTubeEvent.StreamFailed(YouTubeErrorKind.GENERIC))
            else delay(1_500)
        }
    }

    private fun startStream(video: YouTubeVideo, choice: FormatChoice, subtitles: List<YouTubeSubtitle>): Boolean {
        if (choice.isAudio) {
            val a = video.streams.firstOrNull { it.itag == choice.audioItag && it.kind == StreamKind.AUDIO } ?: return false
            audioPlayer.play(listOf(youtubeAudioTrack(video, a)), 0)
            return true
        }
        val req = youTubePlayRequest(video, choice, subtitles) ?: return false
        return _playRequests.trySend(StreamPlay(req, video)).isSuccess
    }

    /** The playlists, for the add-to-playlist dialog. */
    suspend fun playlists(): List<Playlist> = audioLists.snapshot().playlists

    /** An "Up next" row's Add to queue: appends to the play queue and says whether it was new. */
    fun addToQueue(result: YouTubeSearchResult) {
        val id = YouTubeLinks.videoId(result.url) ?: return
        viewModelScope.launch {
            val meta = YouTubeMeta(result.title, result.channel?.takeIf { it.isNotBlank() }, result.durationSec.toInt().takeIf { it > 0 })
            val outcome = try {
                audioPlayer.enqueueYouTube(id, meta)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.send(YouTubeEvent.StreamFailed(YouTubeErrorKind.GENERIC)); return@launch
            }
            _events.send(if (outcome == EnqueueOutcome.ALREADY_QUEUED) YouTubeEvent.AlreadyInPlayQueue else YouTubeEvent.AddedToPlayQueue)
        }
    }

    fun addToPlaylist(req: AddToPlaylistRequest, listId: String, mode: YouTubeMode) {
        viewModelScope.launch {
            val name = audioLists.snapshot().playlists.firstOrNull { it.id == listId }?.name.orEmpty()
            _events.send(YouTubeEvent.PlaylistAdd(audioLists.addYouTube(listId, req.videoId, mode, req.meta), name, mode))
        }
    }

    fun addToNewPlaylist(req: AddToPlaylistRequest, name: String, mode: YouTubeMode) {
        viewModelScope.launch {
            val id = audioLists.create(name)
            if (id.isEmpty()) _events.send(YouTubeEvent.PlaylistAdd(YouTubeAddResult.NO_SUCH_LIST, name))
            else addToPlaylist(req, id, mode)
        }
    }

    /** A results row's Play icon: as a tap on the row, except that a TV plays at the default quality instead of opening the preview. */
    /** The download icon on a result: queues it at the default quality, or opens the preview to pick one ("ask every time"). */
    fun downloadResult(result: YouTubeSearchResult) {
        viewModelScope.launch {
            if (settings.youTubeQualityFlow.first() == DefaultQuality.ASK) { openPreview(result); return@launch }
            val go: suspend () -> Unit = {
                val event = try {
                    tryEnqueueResult(result.url)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    YouTubeEvent.StreamFailed(errorKind(e)) // resolve failed: unavailable, private, offline…
                }
                _events.send(event)
            }
            if (!StorageAccess.canWrite(context, settings.downloadDirFlow.first())) {
                pending = go
                _folderPrompt.value = true
            } else go()
        }
    }

    private suspend fun tryEnqueueResult(url: String): YouTubeEvent = when (enqueuer.enqueue(url, EnqueueMode.DEFAULT)) {
        EnqueueResult.QUEUED -> YouTubeEvent.QUEUED
        EnqueueResult.ALREADY_QUEUED -> YouTubeEvent.ALREADY_QUEUED
        EnqueueResult.FAILED, EnqueueResult.NO_FORMAT -> YouTubeEvent.QUEUE_FAILED
    }

    fun download() {
        val p = _ui.value as? YouTubeUi.Preview ?: return
        val c = p.selected
        if (enqueueJob?.isActive == true) return // double tap
        val item = itemFor(p.video, c)
        val subtitle = p.subtitle?.takeIf { !c.isAudio }?.let { subtitleItemFor(item, it) }
        enqueueJob = viewModelScope.launch {
            // Same rule as Library: no writable download folder yet means ask, never queue to the filesystem root.
            if (!StorageAccess.canWrite(context, settings.downloadDirFlow.first())) {
                pending = { enqueue(item, p.video.thumbnailUrl, subtitle) }
                _folderPrompt.value = true
                return@launch
            }
            enqueue(item, p.video.thumbnailUrl, subtitle)
        }
    }

    private fun subtitleItemFor(video: DownloadItem, s: YouTubeSubtitle) = youTubeSubtitleItem(video, s)

    private fun itemFor(video: YouTubeVideo, c: FormatChoice): DownloadItem = enqueuer.itemFor(video, c)

    private suspend fun enqueue(item: DownloadItem, thumbnailUrl: String?, subtitle: DownloadItem? = null) {
        val video = tryEnqueue(item, thumbnailUrl)
        // The subtitle is its own row: it is added (once) whether or not the video was new,
        // but never on its own when the video could not be queued.
        val sub = if (subtitle != null && video != YouTubeEvent.QUEUE_FAILED) tryEnqueue(subtitle, null) else null
        _events.send(downloadOutcome(video, sub))
    }

    /** Queues [item] and says what happened: QUEUED, ALREADY_QUEUED or QUEUE_FAILED. */
    private suspend fun tryEnqueue(item: DownloadItem, thumbnailUrl: String?): YouTubeEvent = when (enqueuer.tryEnqueue(item, thumbnailUrl)) {
        EnqueueResult.QUEUED -> YouTubeEvent.QUEUED
        EnqueueResult.ALREADY_QUEUED -> YouTubeEvent.ALREADY_QUEUED
        EnqueueResult.FAILED, EnqueueResult.NO_FORMAT -> YouTubeEvent.QUEUE_FAILED
    }

    /** Adds the ticked playlist videos, one at a time, each in its own best-matching format. */
    fun downloadPlaylist() {
        val p = _ui.value as? YouTubeUi.Playlist ?: return
        val chosen = p.items.filter { it.url in p.selected }
        if (chosen.isEmpty() || p.progress != null || batchJob?.isActive == true) return
        batchJob = viewModelScope.launch {
            if (!StorageAccess.canWrite(context, settings.downloadDirFlow.first())) {
                pending = { startBatch(chosen, p.quality) }
                _folderPrompt.value = true
            } else startBatch(chosen, p.quality)
        }
    }

    /** Stops a running playlist batch and keeps the list (videos already added stay queued). */
    fun cancelBatch() {
        batchJob?.cancel()
        _ui.update { (it as? YouTubeUi.Playlist)?.copy(progress = null) ?: it }
    }

    private fun startBatch(chosen: List<YouTubeSearchResult>, quality: DefaultQuality) {
        batchJob = viewModelScope.launch {
            var added = 0; var already = 0; var failed = 0
            chosen.forEachIndexed { i, r ->
                _ui.update { (it as? YouTubeUi.Playlist)?.copy(progress = i + 1 to chosen.size) ?: it }
                try {
                    when (enqueuer.enqueue(r.url, EnqueueMode.DEFAULT, quality)) {
                        EnqueueResult.QUEUED -> added++
                        EnqueueResult.ALREADY_QUEUED -> already++
                        else -> failed++
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ensureActive() // Back cancelled: stop, don't count it
                    failed++
                }
            }
            _ui.update { (it as? YouTubeUi.Playlist)?.copy(progress = null) ?: it }
            _events.send(YouTubeEvent.BatchDone(added, already, failed))
        }
    }

    private var pending: (suspend () -> Unit)? = null
    private val _folderPrompt = MutableStateFlow(false)
    /** True while the "where to save" dialog should show. */
    val folderPrompt: StateFlow<Boolean> = _folderPrompt.asStateFlow()

    fun dismissFolderPrompt() { _folderPrompt.value = false; pending = null }

    /** Folder chosen with the system picker, or app storage; then the waiting download is queued. */
    fun setFolder(dir: String) {
        val waiting = pending
        _folderPrompt.value = false
        pending = null
        viewModelScope.launch {
            settings.setDownloadDir(dir)
            waiting?.invoke()
        }
    }

    private companion object {
        const val LINK_KEY = "link"
    }
}
