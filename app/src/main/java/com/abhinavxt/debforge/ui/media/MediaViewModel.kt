package com.abhinavxt.debforge.ui.media

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.abhinavxt.debforge.data.local.DownloadDao
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
    val active: ActiveSummary? = null
) {
    val current: List<DownloadEntity> get() = shown[kind].orEmpty()
}

/** Why a tap on a file did nothing. */
enum class MediaMessage { FILE_MISSING }

/** The selection restricted to rows that are still on screen, so the count always matches what an action touches. */
internal fun pruneSelection(selected: Set<String>, visibleIds: Collection<String>): Set<String> =
    if (selected.isEmpty()) selected else selected.intersect(visibleIds.toSet())

/** Name shown for a download: its title (YouTube) or the cleaned release name. */
fun DownloadEntity.displayTitle(): String = title?.takeIf { it.isNotBlank() } ?: releaseLabels(filename).title

@HiltViewModel
class MediaViewModel @Inject constructor(
    dao: DownloadDao,
    progress: DownloadProgressTracker,
    private val controller: DownloadController,
    private val localPlayback: LocalPlayback,
    private val audioPlayer: AudioPlayer,
    settings: SettingsStore
) : ViewModel() {

    private data class Ui(val query: String, val sort: SortOrder, val kind: FileKind, val selected: Set<String>)

    private val ui = MutableStateFlow(Ui("", SortOrder.DATE_DESC, FileKind.Video, emptySet()))

    /** Settings: DebForge's player, or the "open with" chooser. */
    val useInternalPlayer: StateFlow<Boolean> = settings.internalPlayerFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

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

    private val completed = dao.observeCompleted().distinctUntilChanged()
        .map { rows -> rows.map { Row(it, kindOf(it.filename), it.displayTitle()) } }

    val state: StateFlow<MediaUiState> = combine(completed, ui, activeSummary) { rows, u, active ->
        val q = u.query.trim()
        val byKind = rows.groupBy { it.kind }
        val shown = FileKind.entries.associateWith { kind ->
            byKind[kind].orEmpty()
                .filter { q.isEmpty() || it.title.contains(q, true) || it.entity.filename.contains(q, true) || it.entity.artist?.contains(q, true) == true }
                .sortedWith(comparator(u.sort))
                .map { it.entity }
        }
        val selected = pruneSelection(u.selected, shown[u.kind].orEmpty().map { it.id })
        MediaUiState(shown, rows.size, u.query, u.sort, u.kind, selected, active)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MediaUiState())

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
            if (deleted.isNotEmpty()) audioPlayer.removeTracks(deleted.toSet())
            if (failed > 0) onFailed(failed)
        }
    }
}
