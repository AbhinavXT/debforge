package com.abhinavxt.debforge.ui.youtube

import android.content.Context
import android.os.Build
import androidx.lifecycle.SavedStateHandle
import androidx.compose.foundation.lazy.LazyListState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.abhinavxt.debforge.data.local.DownloadDao
import com.abhinavxt.debforge.data.youtube.YouTubeResolver
import com.abhinavxt.debforge.di.ApiHttpClient
import com.abhinavxt.debforge.domain.DownloadItem
import com.abhinavxt.debforge.domain.DefaultQuality
import com.abhinavxt.debforge.domain.FormatChoice
import com.abhinavxt.debforge.domain.ProviderId
import com.abhinavxt.debforge.domain.YouTubeFormats
import com.abhinavxt.debforge.domain.pickDefaultChoice
import com.abhinavxt.debforge.domain.YouTubeLinks
import com.abhinavxt.debforge.domain.YouTubeSearchResult
import com.abhinavxt.debforge.domain.YouTubeSubtitle
import com.abhinavxt.debforge.domain.YouTubeVideo
import com.abhinavxt.debforge.domain.subtitleFileName
import com.abhinavxt.debforge.download.DownloadController
import com.abhinavxt.debforge.download.StorageAccess
import com.abhinavxt.debforge.download.artworkFile
import com.abhinavxt.debforge.data.prefs.SettingsStore
import kotlinx.coroutines.flow.first
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.schabi.newpipe.extractor.Page
import java.io.File
import java.time.Instant
import javax.inject.Inject

sealed interface YouTubeUi {
    data object Empty : YouTubeUi
    data object Resolving : YouTubeUi
    data class Preview(val video: YouTubeVideo, val choices: List<FormatChoice>, val selected: FormatChoice, val subtitle: YouTubeSubtitle? = null) : YouTubeUi
    /** A search ([query] is the text) or, when [channelUrl] is set, a channel's videos ([query] is the channel name). */
    data class Results(val query: String, val items: List<YouTubeSearchResult>, val loadingMore: Boolean = false, val channelUrl: String? = null) : YouTubeUi
    /** [progress] is (current, total) while the selected videos are being added one by one. */
    data class Playlist(
        val title: String,
        val items: List<YouTubeSearchResult>,
        val selected: Set<String>,
        val quality: DefaultQuality,
        val progress: Pair<Int, Int>? = null,
    ) : YouTubeUi
    data class Error(val kind: YouTubeErrorKind, val search: Boolean = false) : YouTubeUi
}

sealed interface YouTubeEvent {
    data object QUEUED : YouTubeEvent
    data object ALREADY_QUEUED : YouTubeEvent
    data object QUEUE_FAILED : YouTubeEvent
    /** A playlist batch finished (not cancelled). */
    data class BatchDone(val added: Int, val already: Int, val failed: Int) : YouTubeEvent
}

@HiltViewModel
class YouTubeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val resolver: YouTubeResolver,
    private val downloads: DownloadController,
    private val dao: DownloadDao,
    private val settings: SettingsStore,
    private val saved: SavedStateHandle,
    @ApiHttpClient private val http: OkHttpClient,
) : ViewModel() {
    private val _ui = MutableStateFlow<YouTubeUi>(YouTubeUi.Empty)
    val ui: StateFlow<YouTubeUi> = _ui.asStateFlow()

    /** What the paste field shows; also set when a shared link is offered. */
    private val _input = MutableStateFlow("")
    val input: StateFlow<String> = _input.asStateFlow()

    private val _events = Channel<YouTubeEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var job: Job? = null
    private var enqueueJob: Job? = null
    private var batchJob: Job? = null
    private var moreJob: Job? = null
    /** Extractor cursor for the shown search results; null when there are no more. */
    private var nextPage: Page? = null

    private val _trending = MutableStateFlow<List<YouTubeSearchResult>>(emptyList())
    /** Videos for the empty screen; stays empty when loading failed. */
    val trending: StateFlow<List<YouTubeSearchResult>> = _trending.asStateFlow()
    private var trendingStarted = false

    /** Called when the empty screen is first shown (the ViewModel itself exists from app start); only the first call fetches. */
    fun loadTrending() {
        if (trendingStarted) return
        trendingStarted = true
        viewModelScope.launch {
            try { _trending.value = resolver.trending() } catch (e: CancellationException) { throw e } catch (e: Exception) { }
        }
    }

    init {
        // Process death: bring the preview back for the last link the user submitted.
        saved.get<String>(LINK_KEY)?.let(::submit)
    }

    fun setInput(text: String) { _input.value = text }

    /** Called by the shell with a shared link. */
    fun offer(link: String) = submit(link)

    /** The results a tapped result came from, so Back returns there; cleared by any new submit. */
    private var fromResults: YouTubeUi.Results? = null

    /** An open channel list: [back] is the search it was opened from (null: the empty screen). */
    private class ChannelNav(val name: String, val url: String, val back: Back?)
    private class Back(val results: YouTubeUi.Results, val next: Page?, val list: LazyListState)
    private var channel: ChannelNav? = null

    fun submit(text: String) {
        fromResults = null
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
            YouTubeLinks.isSearchQuery(text) -> search(text.trim())
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
        _ui.value = r.copy(loadingMore = true)
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
                _ui.update { if (it is YouTubeUi.Results) it.copy(loadingMore = false) else it }
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

    /** Opens a channel's videos from a result row or the empty screen; Back returns to where it was opened. */
    fun openChannel(name: String, url: String) {
        val cur = _ui.value
        if (cur !is YouTubeUi.Empty && !(cur is YouTubeUi.Results && cur.channelUrl == null)) return
        job?.cancel()
        moreJob?.cancel()
        val back = (cur as? YouTubeUi.Results)?.let { Back(it.copy(loadingMore = false), nextPage, resultsList) }
        loadChannel(ChannelNav(name, url, back))
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
                YouTubeUi.Results(nav.name, page.items, channelUrl = nav.url)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ensureActive() // a cancelled job must not overwrite the state Back just set
                YouTubeUi.Error(errorKind(e), search = true)
            }
        }
    }

    /** A tapped search result: same path as a pasted link, but Back returns to the results. */
    fun pick(result: YouTubeSearchResult) {
        val results = _ui.value as? YouTubeUi.Results
        if (results == null && _ui.value !is YouTubeUi.Empty) return
        job?.cancel()
        moreJob?.cancel()
        _input.value = result.url
        saved[LINK_KEY] = result.url
        resolve(result.url)
        fromResults = results?.copy(loadingMore = false) // a cancelled page load must not leave the spinner on
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
        if (fromResults != null) { job?.cancel(); resolve(_input.value) }
        else if (nav != null) { job?.cancel(); loadChannel(nav) }
        else submit(_input.value)
    }

    fun reset() {
        job?.cancel()
        moreJob?.cancel()
        batchJob?.cancel()
        pending = null // a download waiting on the folder prompt belongs to the screen being left
        fromResults?.let {
            fromResults = null
            _input.value = if (it.channelUrl == null) it.query else ""
            saved[LINK_KEY] = it.query
            _ui.value = it
            return
        }
        channel?.let { nav ->
            channel = null
            val back = nav.back
            nextPage = back?.next
            if (back != null) {
                resultsList = back.list
                _input.value = back.results.query
                saved[LINK_KEY] = back.results.query
                _ui.value = back.results
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

    /** A plain download of the track next to the video; the player finds it by the shared base name. */
    private fun subtitleItemFor(video: DownloadItem, s: YouTubeSubtitle) = DownloadItem(
        id = "${video.id}:sub:${s.languageCode}", provider = ProviderId.DIRECT, filename = subtitleFileName(video.filename, s),
        sourceRef = s.url, downloadUrl = s.url, host = "YouTube", filesize = 0, maxConnections = 1,
        addedAt = Instant.now().toString(),
    )

    private fun itemFor(video: YouTubeVideo, c: FormatChoice): DownloadItem {
        val ref = YouTubeLinks.sourceRef(video.id, c.videoItag, c.audioItag)
        // The engine re-resolves from sourceRef (URLs expire); the URL here is only the stream's current one.
        val url = video.streams.firstOrNull { it.itag == (c.videoItag ?: c.audioItag) }?.url
        return DownloadItem(
            id = ref, provider = ProviderId.DIRECT, filename = fileNameFor(video.title, c, video.id), sourceRef = ref,
            downloadUrl = url, host = "YouTube", filesize = c.estimatedBytes, maxConnections = 1,
            addedAt = Instant.now().toString(), title = video.title, artist = video.uploader,
        )
    }

    private suspend fun enqueue(item: DownloadItem, thumbnailUrl: String?, subtitle: DownloadItem? = null) {
        val result = tryEnqueue(item, thumbnailUrl)
        // A failed subtitle never fails the video that is already queued.
        if (result == YouTubeEvent.QUEUED && subtitle != null) {
            try { downloads.enqueue(subtitle) } catch (e: CancellationException) { throw e } catch (e: Exception) { }
        }
        _events.send(result)
    }

    /** Queues [item] and says what happened: QUEUED, ALREADY_QUEUED or QUEUE_FAILED. */
    private suspend fun tryEnqueue(item: DownloadItem, thumbnailUrl: String?): YouTubeEvent {
        try {
            // enqueue ignores an id it already has, so ask first and report what really happens.
            if (dao.getById(item.id) != null) return YouTubeEvent.ALREADY_QUEUED
            downloads.enqueue(item)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return YouTubeEvent.QUEUE_FAILED
        }
        saveThumbnail(thumbnailUrl, item.id)
        return YouTubeEvent.QUEUED
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

    private fun startBatch(chosen: List<YouTubeSearchResult>, quality: DefaultQuality) {
        batchJob = viewModelScope.launch {
            var added = 0; var already = 0; var failed = 0
            chosen.forEachIndexed { i, r ->
                _ui.update { (it as? YouTubeUi.Playlist)?.copy(progress = i + 1 to chosen.size) ?: it }
                try {
                    val video = resolver.resolve(r.url)
                    val pick = pickDefaultChoice(YouTubeFormats.choices(video.streams, Build.VERSION.SDK_INT, video.durationSec), quality)
                    when (if (pick == null) YouTubeEvent.QUEUE_FAILED else tryEnqueue(itemFor(video, pick), video.thumbnailUrl)) {
                        YouTubeEvent.QUEUED -> added++
                        YouTubeEvent.ALREADY_QUEUED -> already++
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

    private suspend fun saveThumbnail(url: String?, id: String) {
        if (url == null) return
        withContext(Dispatchers.IO) {
            runCatching {
                http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    if (!r.isSuccessful) return@use
                    val file = artworkFile(context, id).also { it.parentFile?.mkdirs() }
                    val tmp = File(file.path + ".tmp")
                    try {
                        r.body?.byteStream()?.use { input -> tmp.outputStream().use { input.copyTo(it) } } ?: return@use
                        if (!tmp.renameTo(file)) tmp.delete()
                    } finally {
                        tmp.delete() // no-op after a successful rename
                    }
                }
            }
        }
    }

    private companion object {
        const val LINK_KEY = "link"
    }
}
