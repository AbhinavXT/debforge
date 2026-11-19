package com.abhinavxt.debforge.ui.media

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.abhinavxt.debforge.data.local.DownloadDao
import com.abhinavxt.debforge.data.playback.AudioLists
import com.abhinavxt.debforge.data.playback.AudioListsState
import com.abhinavxt.debforge.data.playback.PlaylistEntry
import com.abhinavxt.debforge.download.youtube.EnqueueResult
import com.abhinavxt.debforge.download.youtube.YouTubeEnqueuer
import com.abhinavxt.debforge.player.audioTrackFor
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import com.abhinavxt.debforge.data.local.DownloadEntity
import com.abhinavxt.debforge.domain.DownloadState
import com.abhinavxt.debforge.domain.FileKind
import com.abhinavxt.debforge.domain.SortOrder
import com.abhinavxt.debforge.domain.kindOf
import com.abhinavxt.debforge.data.prefs.SettingsStore
import com.abhinavxt.debforge.download.DownloadController
import com.abhinavxt.debforge.download.DownloadProgressTracker
import com.abhinavxt.debforge.player.AudioPlayer
import com.abhinavxt.debforge.player.LocalPlayback
import com.abhinavxt.debforge.ui.browse.PlayRequest
import com.abhinavxt.debforge.ui.components.releaseLabels
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** In-progress downloads for the banner: [percent] is overall (bytes done of bytes known). */
data class ActiveSummary(val count: Int, val percent: Int)

data class MediaUiState(
    /** Filtered and sorted, per segment. */
    val shown: Map<FileKind, List<DownloadEntity>> = emptyMap(),
    val totalCount: Int = 0,
    val query: String = "",
    val sort: SortOrder = SortOrder.DATE_DESC,
    val kind: FileKind = FileKind.Video,
    val selected: Set<String> = emptySet(),
    val active: ActiveSummary? = null,
    /** Audio segment only: null = all, "fav", or a playlist id. */
    val audioList: String? = null,
    val lists: AudioListsState = AudioListsState(),
    /** Number of audio tracks matching the search, before the list pick (what the tab badge shows). */
    val audioTotal: Int = 0,
    /** False until Room has emitted the first time, so the screen does not flash its empty message. */
    val loaded: Boolean = true,
    /** A playlist is open: its rows, downloads and YouTube items in playlist order. Null otherwise. */
    val playlistRows: List<PlaylistRow>? = null,
    /** The open playlist's YouTube items that "Download all" would queue. */
    val ytToDownload: List<PlaylistEntry.YouTube> = emptyList()
) {
    /** Tab badge count: the audio badge ignores the list pick. */
    fun count(k: FileKind): Int = shown[k].orEmpty().size.let { if (k == FileKind.Audio) maxOf(it, audioTotal) else it }
    val current: List<DownloadEntity> get() = shown[kind].orEmpty()
}

/** Why a tap on a file did nothing. */
enum class MediaMessage { FILE_MISSING, STREAM_FAILED, DOWNLOAD_ALL_FAILED, DOWNLOAD_ALL_ALREADY }

/** The selection restricted to rows that are still on screen, so the count always matches what an action touches. */
internal fun pruneSelection(selected: Set<String>, visibleIds: Collection<String>): Set<String> =
    if (selected.isEmpty()) selected else selected.intersect(visibleIds.toSet())

/** Audio rows for a list pick: null = all, "fav" = favourites in row order, else that playlist in its own order (unknown id = all). */
internal fun audioListView(rows: List<DownloadEntity>, lists: AudioListsState, pick: String?): List<DownloadEntity> {
    if (pick == null) return rows
    if (pick == "fav") return rows.filter { it.id in lists.favourites }
    val playlist = lists.playlists.firstOrNull { it.id == pick } ?: return rows
    val byId = rows.associateBy { it.id }
    return playlist.trackIds.mapNotNull { byId[it] }
}

/** Name shown for a download: its title (YouTube) or the cleaned release name. */
fun DownloadEntity.displayTitle(): String = title?.takeIf { it.isNotBlank() } ?: releaseLabels(filename).title

@HiltViewModel
class MediaViewModel @Inject constructor(
    private val dao: DownloadDao,
    progress: DownloadProgressTracker,
    private val controller: DownloadController,
    private val localPlayback: LocalPlayback,
    private val audioPlayer: AudioPlayer,
    private val settings: SettingsStore,
    @ApplicationContext private val context: android.content.Context,
    private val videoHandoff: com.abhinavxt.debforge.player.VideoHandoff,
    private val ytEnqueuer: YouTubeEnqueuer,
    private val audioLists: AudioLists,
    private val localImporter: com.abhinavxt.debforge.download.LocalImporter,
    playbackPositions: com.abhinavxt.debforge.data.playback.PlaybackPositions
) : ViewModel() {

    private data class Ui(val query: String, val sort: SortOrder, val kind: FileKind, val selected: Set<String>, val audioList: String? = null)

    init {
        // Pick up files added to the folder since the last scan (at most every 30 s).
        viewModelScope.launch { localImporter.scanIfStale() }
    }

    private val _refreshing = MutableStateFlow(false)
    /** A pull-to-refresh (or the menu's Refresh) scan is running. */
    val refreshing: StateFlow<Boolean> = _refreshing

    fun refresh() {
        if (!_refreshing.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                val started = android.os.SystemClock.elapsedRealtime()
                localImporter.scan()
                // A quick scan would flash the indicator unseen; keep it up briefly.
                val left = 600L - (android.os.SystemClock.elapsedRealtime() - started)
                if (left > 0) kotlinx.coroutines.delay(left)
            } finally { _refreshing.value = false }
        }
    }

    private val ui = MutableStateFlow(Ui("", SortOrder.DATE_DESC, FileKind.Video, emptySet()))

    /** Settings: DebForge's player, or the "open with" chooser. */
    val useInternalPlayer: StateFlow<Boolean> = settings.internalPlayerFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /** Saved watch position per download id: local playback saves under the DownloadEntity id (LocalPlayback.toLocalItem). */
    /** Null until Room has emitted the first time. */
    val watch: StateFlow<Map<String, com.abhinavxt.debforge.data.playback.PlaybackEntity>?> = playbackPositions.all
        .map { list -> list.associateBy { it.itemId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _playRequests = Channel<PlayRequest>(Channel.BUFFERED)
    val playRequests = _playRequests.receiveAsFlow()

    private val _messages = Channel<MediaMessage>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    private val activeSummary = combine(dao.observeAll(), progress.progress) { all, live ->
        val running = all.filter { it.state == DownloadState.DOWNLOADING || it.state == DownloadState.QUEUED }
        val total = running.sumOf { it.filesize }
        val done = running.sumOf { live[it.id]?.bytesDownloaded ?: it.bytesDownloaded }
        running.size.takeIf { it > 0 }?.let { ActiveSummary(it, if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else 0) }
    }.distinctUntilChanged()

    /** A completed row with its title worked out once; progress ticks on other rows re-emit equal lists and are dropped. */
    private class Row(val entity: DownloadEntity, val kind: FileKind, val title: String) {
        val titleLower = title.lowercase()
    }

    /** Every YouTube download of any state, for the "Download all" selection. */
    private val ytDownloads = dao.observeAll()
        .map { all -> youTubeDownloadStates(all) }
        .distinctUntilChanged()

    private val completed = dao.observeCompleted().distinctUntilChanged()
        .map { rows -> rows.map { Row(it, kindOf(it.filename), it.displayTitle()) } }

    val state: StateFlow<MediaUiState> = combine(completed, ui, activeSummary, audioLists.state, ytDownloads) { rows, u, active, lists, ytDl ->
        val q = u.query.trim()
        val byKind = rows.groupBy { it.kind }
        val shown = FileKind.entries.associateWith { kind ->
            byKind[kind].orEmpty()
                .filter { q.isEmpty() || it.title.contains(q, true) || it.entity.filename.contains(q, true) || it.entity.artist?.contains(q, true) == true }
                .sortedWith(comparator(u.sort))
                .map { it.entity }
        }
        val audioMatching = shown[FileKind.Audio].orEmpty().size
        val view = shown.toMutableMap().also { m ->
            if (u.audioList != null) m[FileKind.Audio] = audioListView(m[FileKind.Audio].orEmpty(), lists, u.audioList)
        }
        val selected = pruneSelection(u.selected, view[u.kind].orEmpty().map { it.id })
        val typed = u.audioList?.let { pick -> lists.typedPlaylists().firstOrNull { it.id == pick } }
        val playlistRows = typed?.let {
            val locals = YouTubeLocals.of(rows.map { r -> r.entity })
            playlistRows(it.entries, shown[FileKind.Audio].orEmpty().associateBy { e -> e.id }, lists.ytMeta, locals, u.query)
        }
        val toDownload = typed?.let { youTubeToDownload(it.entries, ytDl) }.orEmpty()
        MediaUiState(view, rows.size, u.query, u.sort, u.kind, selected, active, u.audioList, lists, audioMatching, true, playlistRows, toDownload)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MediaUiState(loaded = false))

    private fun comparator(sort: SortOrder): Comparator<Row> = when (sort) {
        SortOrder.DATE_DESC -> compareByDescending { it.entity.updatedAt }
        SortOrder.DATE_ASC -> compareBy { it.entity.updatedAt }
        SortOrder.NAME_ASC -> compareBy { it.titleLower }
        SortOrder.NAME_DESC -> compareByDescending { it.titleLower }
        SortOrder.SIZE_DESC -> compareByDescending { it.entity.filesize }
        SortOrder.SIZE_ASC -> compareBy { it.entity.filesize }
    }

    fun setQuery(q: String) = ui.update { it.copy(query = q) }
    fun setSort(s: SortOrder) = ui.update { it.copy(sort = s) }
    /** Selection belongs to one segment, so switching drops it. */
    fun setKind(k: FileKind) = ui.update { it.copy(kind = k, selected = emptySet()) }

    fun selectAudioList(pick: String?) = ui.update { it.copy(audioList = pick, selected = emptySet()) }
    fun toggleFavourite(id: String) { viewModelScope.launch { audioLists.toggleFavourite(id) } }
    fun createPlaylist(name: String, addIds: Collection<String> = emptyList()) {
        viewModelScope.launch {
            val id = audioLists.create(name)
            if (id.isNotEmpty() && addIds.isNotEmpty()) audioLists.add(id, addIds)
        }
    }
    fun renamePlaylist(listId: String, name: String) { viewModelScope.launch { audioLists.rename(listId, name) } }
    fun deletePlaylist(listId: String) {
        viewModelScope.launch { audioLists.delete(listId) }
        ui.update { if (it.audioList == listId) it.copy(audioList = null, selected = emptySet()) else it }
    }
    fun addToPlaylist(listId: String, ids: Collection<String>) { viewModelScope.launch { audioLists.add(listId, ids) } }
    fun removeFromPlaylist(listId: String, id: String) { viewModelScope.launch { audioLists.remove(listId, id) } }

    /** Removes one entry from a playlist. Only the playlist changes: no file is touched. */
    fun removeEntry(listId: String, entry: PlaylistEntry) { viewModelScope.launch { audioLists.removeEntry(listId, entry) } }

    /** Plays the open playlist as audio from [startRow]: downloads and YouTube items through one queue. */
    fun playPlaylist(rows: List<PlaylistRow>, startRow: Int) {
        val (entries, start) = mixedQueueFor(rows, startRow) { audioTrackFor(context, it) }
        if (entries.isNotEmpty()) audioPlayer.playMixed(entries, start)
    }

    private var videoJob: Job? = null

    /** Opens a video-mode item as video: its local video download, else streamed the way the YouTube tab does. */
    fun openVideo(row: PlaylistRow.YouTube) {
        row.videoDownload?.let { play(it); return }
        if (videoJob?.isActive == true) return // double tap
        videoJob = viewModelScope.launch {
            val request = videoHandoff.youTubeVideoRequest(row.videoId)
            if (request != null) _playRequests.send(request) else _messages.send(MediaMessage.STREAM_FAILED)
        }
    }

    private val _bulkRunning = MutableStateFlow(false)
    /** True while a "Download all" batch is being queued. */
    val bulkRunning: StateFlow<Boolean> = _bulkRunning

    private val _bulkQueued = Channel<Int>(Channel.BUFFERED)
    /** How many items a "Download all" actually queued. */
    val bulkQueued = _bulkQueued.receiveAsFlow()

    /** Queues [items] one by one, each in its own mode at the default quality; a failed one is re-queued, as a retry does. */
    fun downloadAll(items: List<PlaylistEntry.YouTube>) {
        if (items.isEmpty() || !_bulkRunning.compareAndSet(expect = false, update = true)) return
        viewModelScope.launch {
            try {
                val existing = youTubeDownloadStates(dao.observeAll().first())
                var queued = 0
                var already = 0
                for (item in items) {
                    val failedId = failedDownloadId(item, existing)
                    if (failedId != null && ytEnqueuer.retryIfFailed(failedId)) { queued++; continue }
                    val result = try {
                        ytEnqueuer.enqueue(item.videoId, item.enqueueMode())
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        EnqueueResult.FAILED
                    }
                    when (result) {
                        EnqueueResult.QUEUED -> queued++
                        EnqueueResult.ALREADY_QUEUED -> already++
                        else -> Unit
                    }
                }
                when (bulkOutcome(queued, already)) {
                    BulkOutcome.QUEUED -> _bulkQueued.send(queued)
                    BulkOutcome.ALREADY -> _messages.send(MediaMessage.DOWNLOAD_ALL_ALREADY)
                    BulkOutcome.FAILED -> _messages.send(MediaMessage.DOWNLOAD_ALL_FAILED)
                }
            } finally {
                _bulkRunning.value = false
            }
        }
    }

    fun toggleSelected(id: String) = ui.update { it.copy(selected = if (id in it.selected) it.selected - id else it.selected + id) }
    fun selectAll() = ui.update { u -> u.copy(selected = state.value.current.map { it.id }.toSet()) }
    fun clearSelection() = ui.update { it.copy(selected = emptySet()) }

    fun play(entity: DownloadEntity) {
        viewModelScope.launch {
            val request = localPlayback.requestFor(entity)
            if (request != null) _playRequests.send(request) else _messages.send(MediaMessage.FILE_MISSING)
        }
    }

    /** Deletes files from storage; [onFailed] gets how many could not be deleted. */
    fun delete(ids: Collection<String>, onFailed: (Int) -> Unit) {
        viewModelScope.launch {
            val deleted = ids.filter { controller.deleteFromDevice(it) }
            val failed = ids.size - deleted.size
            ui.update { it.copy(selected = it.selected - ids.toSet()) }
            if (deleted.isNotEmpty()) {
                audioPlayer.removeTracks(deleted.toSet())
                audioLists.prune(deleted.toSet())
            }
            if (failed > 0) onFailed(failed)
        }
    }
}
