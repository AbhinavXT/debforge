package com.abhinavxt.debforge.ui.media

import com.abhinavxt.debforge.data.youtube.decodeEntities
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.abhinavxt.debforge.data.local.DownloadDao
import com.abhinavxt.debforge.data.playback.AudioLists
import com.abhinavxt.debforge.data.playback.AudioListsState
import com.abhinavxt.debforge.data.playback.PlaylistEntry
import com.abhinavxt.debforge.download.youtube.EnqueueResult
import com.abhinavxt.debforge.download.youtube.YouTubeEnqueuer
import com.abhinavxt.debforge.player.audioTracksFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
import kotlinx.coroutines.flow.flowOn
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
    val ytToDownload: List<PlaylistEntry.YouTube> = emptyList(),
    /** Folders / Artists / Albums pick, no group open: the group list. Null otherwise. */
    val groups: List<AudioGroup>? = null,
    /** A group is open: its header info (name, marker). Its tracks are in [shown]. */
    val openGroup: AudioGroup? = null
) {
    /** Tab badge count: the audio badge ignores the list pick. */
    fun count(k: FileKind): Int = shown[k].orEmpty().size.let { if (k == FileKind.Audio) maxOf(it, audioTotal) else it }
    val current: List<DownloadEntity> get() = shown[kind].orEmpty()
}

/** Why a tap on a file did nothing. */
enum class MediaMessage { FILE_MISSING, STREAM_FAILED, DOWNLOAD_ALL_FAILED, DOWNLOAD_ALL_ALREADY }

/** Where "Play as audio" starts for a downloaded video: its saved watch position, or the beginning when unwatched or finished. */
internal fun playAsAudioStartMs(saved: com.abhinavxt.debforge.data.playback.PlaybackEntity?): Long =
    if (saved == null || saved.finished) 0L else saved.positionMs.coerceAtLeast(0L)

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

internal const val BROWSE_FOLDERS = "folders"
internal const val BROWSE_ARTISTS = "artists"
internal const val BROWSE_ALBUMS = "albums"

/** The browse mode a reserved audioList id stands for; null for All, Favourites, Podcasts and playlist ids. */
internal fun browseModeOf(pick: String?): BrowseMode? = when (pick) {
    BROWSE_FOLDERS -> BrowseMode.FOLDERS
    BROWSE_ARTISTS -> BrowseMode.ARTISTS
    BROWSE_ALBUMS -> BrowseMode.ALBUMS
    else -> null
}

/** What a browse pick shows: [groups] (list) or [open] with its [tracks] (query applied). */
internal class BrowseView(val groups: List<AudioGroup>?, val open: AudioGroup?, val tracks: List<DownloadEntity>)

/**
 * [audio] is every audio row in the current sort. Null when [pick] is not a browse pick.
 * An [openKey] that no longer exists (group emptied) falls back to the group list.
 */
internal fun browseView(audio: List<DownloadEntity>, pick: String?, openKey: String?, root: String, query: String): BrowseView? {
    val mode = browseModeOf(pick) ?: return null
    if (openKey != null) {
        val open = groupsOf(audio, mode, root).firstOrNull { it.key == openKey }
        if (open != null) return BrowseView(null, open, tracksOf(audio, mode, openKey, root, query))
    }
    return BrowseView(groupsOf(audio, mode, root, query), null, emptyList())
}

/** Name shown for a download: its title (YouTube) or the cleaned release name. */
fun DownloadEntity.displayTitle(): String =
    title?.takeIf { it.isNotBlank() }?.let { decodeEntities(it) } ?: releaseLabels(filename).title

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
    private val playbackPositions: com.abhinavxt.debforge.data.playback.PlaybackPositions
) : ViewModel() {

    private data class Ui(val query: String, val sort: SortOrder, val kind: FileKind, val selected: Set<String>, val audioList: String? = null, val openGroup: String? = null)

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

    private val listsAndRoot = combine(audioLists.state, settings.downloadDirFlow) { l, r -> l to r }

    val state: StateFlow<MediaUiState> = combine(completed, ui, activeSummary, listsAndRoot, ytDownloads) { rows, u, active, (lists, root), ytDl ->
        val q = u.query.trim()
        val byKind = rows.groupBy { it.kind }
        val shown = FileKind.entries.associateWith { kind ->
            byKind[kind].orEmpty()
                .filter { q.isEmpty() || it.title.contains(q, true) || it.entity.filename.contains(q, true) || it.entity.artist?.contains(q, true) == true }
                .sortedWith(comparator(u.sort))
                .map { it.entity }
        }
        val audioMatching = shown[FileKind.Audio].orEmpty().size
        // Browse picks group every audio row (not only the matching ones): a folder or artist name can match the query.
        val browse = browseModeOf(u.audioList)?.let {
            val all = byKind[FileKind.Audio].orEmpty().sortedWith(comparator(u.sort)).map { r -> r.entity }
            browseView(all, u.audioList, u.openGroup, root, u.query)
        }
        // The open group emptied: forget it, so it doesn't reopen by itself when files with that key return.
        if (browse != null && u.openGroup != null && browse.open == null) {
            ui.update { if (it.openGroup == u.openGroup) it.copy(openGroup = null) else it }
        }
        val view = shown.toMutableMap().also { m ->
            if (browse != null) m[FileKind.Audio] = browse.tracks
            else if (u.audioList != null) m[FileKind.Audio] = audioListView(m[FileKind.Audio].orEmpty(), lists, u.audioList)
        }
        val selected = pruneSelection(u.selected, view[u.kind].orEmpty().map { it.id })
        val typed = u.audioList?.let { pick -> lists.typedPlaylists().firstOrNull { it.id == pick } }
        val playlistRows = typed?.let {
            val locals = YouTubeLocals.of(rows.map { r -> r.entity })
            playlistRows(it.entries, shown[FileKind.Audio].orEmpty().associateBy { e -> e.id }, lists.ytMeta, locals, u.query)
        }
        val toDownload = typed?.let { youTubeToDownload(it.entries, ytDl) }.orEmpty()
        MediaUiState(view, rows.size, u.query, u.sort, u.kind, selected, active, u.audioList, lists, audioMatching, true, playlistRows, toDownload, browse?.groups, browse?.open)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MediaUiState(loaded = false))

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
    fun setKind(k: FileKind) = ui.update { it.copy(kind = k, selected = emptySet(), openGroup = null) }

    /** Changing the chip closes any open group. */
    fun selectAudioList(pick: String?) = ui.update { it.copy(audioList = pick, selected = emptySet(), openGroup = null) }
    fun openGroup(key: String) = ui.update { it.copy(openGroup = key, selected = emptySet()) }
    fun closeGroup() = ui.update { it.copy(openGroup = null, selected = emptySet()) }
    fun toggleFavourite(id: String) { viewModelScope.launch { audioLists.toggleFavourite(id) } }
    fun createPlaylist(name: String, addIds: Collection<String> = emptyList()) {
        viewModelScope.launch {
            val id = audioLists.create(name)
            if (id.isNotEmpty() && addIds.isNotEmpty()) audioLists.add(id, addIds)
        }
    }

    /** Reads the .m3u at [uri] off the main thread and makes a playlist of the downloaded audio it lists; [done] gets (name, added, notFound), name null when none matched. */
    fun importM3u(uri: android.net.Uri, done: (String?, Int, Int) -> Unit) {
        viewModelScope.launch {
            val (name, ids, missing) = withContext(Dispatchers.IO) {
                val cr = context.contentResolver
                val file = cr.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: uri.lastPathSegment.orEmpty()
                val text = runCatching { cr.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull().orEmpty()
                val tracks = dao.completed().filter { kindOf(it.filename) == FileKind.Audio && it.finalFilePath != null }
                    .map { it.id to it.finalFilePath!! }
                val (found, miss) = matchM3u(parseM3u(text), tracks)
                Triple(file.substringBeforeLast('.').ifBlank { file }, found, miss)
            }
            if (ids.isEmpty()) return@launch done(null, 0, missing)
            val id = audioLists.create(name)
            audioLists.add(id, ids)
            done(audioLists.snapshot().playlists.firstOrNull { it.id == id }?.name ?: name, ids.size, missing)
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
        playlistJob?.cancel()
        playlistJob = viewModelScope.launch {
            // Off the main thread: a long playlist of files in a picked folder takes a while to look up.
            val tracks = withContext(Dispatchers.IO) {
                audioTracksFor(context, rows.filterIsInstance<PlaylistRow.Download>().map { it.entity })
            }.associateBy { it.id }
            val (entries, start) = mixedQueueFor(rows, startRow) { tracks[it.id] }
            if (entries.isNotEmpty()) audioPlayer.playMixed(entries, start)
        }
    }

    private var playlistJob: Job? = null

    /** Plays a downloaded video as audio from its saved watch position (or the start). */
    fun playAsAudio(entity: DownloadEntity) {
        viewModelScope.launch {
            val track = videoHandoff.audioTrackForVideo(entity.id, null, entity.displayTitle())
            if (track == null) { _messages.send(MediaMessage.FILE_MISSING); return@launch }
            val saved = watch.value?.get(entity.id) ?: playbackPositions.get(entity.id)
            audioPlayer.playHandoff(track, playAsAudioStartMs(saved), audioPlayer.state.value.speed)
        }
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
