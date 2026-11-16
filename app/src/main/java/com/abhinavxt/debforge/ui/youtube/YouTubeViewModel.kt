package com.abhinavxt.debforge.ui.youtube

import android.content.Context
import android.os.Build
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.abhinavxt.debforge.data.local.DownloadDao
import com.abhinavxt.debforge.data.youtube.YouTubeResolver
import com.abhinavxt.debforge.di.ApiHttpClient
import com.abhinavxt.debforge.domain.DownloadItem
import com.abhinavxt.debforge.domain.FormatChoice
import com.abhinavxt.debforge.domain.ProviderId
import com.abhinavxt.debforge.domain.YouTubeFormats
import com.abhinavxt.debforge.domain.pickDefaultChoice
import com.abhinavxt.debforge.domain.YouTubeLinks
import com.abhinavxt.debforge.domain.YouTubeSearchResult
import com.abhinavxt.debforge.domain.YouTubeVideo
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
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.time.Instant
import javax.inject.Inject

sealed interface YouTubeUi {
    data object Empty : YouTubeUi
    data object Resolving : YouTubeUi
    data class Preview(val video: YouTubeVideo, val choices: List<FormatChoice>, val selected: FormatChoice) : YouTubeUi
    data class Results(val query: String, val items: List<YouTubeSearchResult>) : YouTubeUi
    data class Error(val kind: YouTubeErrorKind, val search: Boolean = false) : YouTubeUi
}

enum class YouTubeEvent { QUEUED, ALREADY_QUEUED, QUEUE_FAILED }

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

    init {
        // Process death: bring the preview back for the last link the user submitted.
        saved.get<String>(LINK_KEY)?.let(::submit)
    }

    fun setInput(text: String) { _input.value = text }

    /** Called by the shell with a shared link. */
    fun offer(link: String) = submit(link)

    /** The results a tapped result came from, so Back returns there; cleared by any new submit. */
    private var fromResults: YouTubeUi.Results? = null

    fun submit(text: String) {
        fromResults = null
        _input.value = text
        saved[LINK_KEY] = text
        job?.cancel()
        when {
            looksLikeYouTube(text) -> resolve(text)
            YouTubeLinks.isSearchQuery(text) -> search(text.trim())
            else -> _ui.value = YouTubeUi.Error(YouTubeErrorKind.NOT_LINK)
        }
    }

    private fun search(query: String) {
        _ui.value = YouTubeUi.Resolving
        job = viewModelScope.launch {
            _ui.value = try {
                YouTubeUi.Results(query, resolver.search(query))
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
        val results = _ui.value as? YouTubeUi.Results ?: return
        job?.cancel()
        _input.value = result.url
        saved[LINK_KEY] = result.url
        resolve(result.url)
        fromResults = results
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
        if (fromResults != null) { job?.cancel(); resolve(_input.value) } else submit(_input.value)
    }

    fun reset() {
        job?.cancel()
        fromResults?.let {
            fromResults = null
            _input.value = it.query
            saved[LINK_KEY] = it.query
            _ui.value = it
            return
        }
        saved.remove<String>(LINK_KEY)
        _input.value = ""
        _ui.value = YouTubeUi.Empty
    }

    fun select(choice: FormatChoice) {
        (_ui.value as? YouTubeUi.Preview)?.let { _ui.value = it.copy(selected = choice) }
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
        val ref = YouTubeLinks.sourceRef(p.video.id, c.videoItag, c.audioItag)
        if (enqueueJob?.isActive == true) return // double tap
        // The engine re-resolves from sourceRef (URLs expire); the URL here is only the stream's current one.
        val url = p.video.streams.firstOrNull { it.itag == (c.videoItag ?: c.audioItag) }?.url
        val item = DownloadItem(
            id = ref, provider = ProviderId.DIRECT, filename = fileNameFor(p.video.title, c, p.video.id), sourceRef = ref,
            downloadUrl = url, host = "YouTube", filesize = c.estimatedBytes, maxConnections = 1,
            addedAt = Instant.now().toString(), title = p.video.title, artist = p.video.uploader,
        )
        enqueueJob = viewModelScope.launch {
            // Same rule as Library: no writable download folder yet means ask, never queue to the filesystem root.
            if (!StorageAccess.canWrite(context, settings.downloadDirFlow.first())) {
                pending = item to p.video.thumbnailUrl
                _folderPrompt.value = true
                return@launch
            }
            enqueue(item, p.video.thumbnailUrl)
        }
    }

    private suspend fun enqueue(item: DownloadItem, thumbnailUrl: String?) {
        try {
            // enqueue ignores an id it already has, so ask first and report what really happens.
            if (dao.getById(item.id) != null) { _events.send(YouTubeEvent.ALREADY_QUEUED); return }
            downloads.enqueue(item)
            _events.send(YouTubeEvent.QUEUED)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _events.send(YouTubeEvent.QUEUE_FAILED)
            return
        }
        saveThumbnail(thumbnailUrl, item.id)
    }

    private var pending: Pair<DownloadItem, String?>? = null
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
            if (waiting != null) enqueue(waiting.first, waiting.second)
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
