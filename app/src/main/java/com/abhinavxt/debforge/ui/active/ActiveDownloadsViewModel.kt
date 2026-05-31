package com.abhinavxt.debforge.ui.active

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.abhinavxt.debforge.data.local.DownloadDao
import com.abhinavxt.debforge.data.local.DownloadEntity
import com.abhinavxt.debforge.domain.DownloadProgress
import com.abhinavxt.debforge.domain.DownloadState
import com.abhinavxt.debforge.download.DownloadController
import com.abhinavxt.debforge.download.DownloadProgressTracker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * A single row in the active list. [live] is null until the engine pushes a
 * progress update — for QUEUED/PAUSED/COMPLETED items the durable [entity]
 * fields are the truth, so the UI reads via the computed properties below to
 * pick whichever source is fresher.
 */
data class ActiveRow(
    val entity: DownloadEntity,
    val live: DownloadProgress?
) {
    val id: String get() = entity.rdId
    val bytesNow: Long get() = live?.bytesDownloaded ?: entity.bytesDownloaded
    val bytesPerSecond: Long get() = live?.bytesPerSecond ?: 0L
    val waitingForNetwork: Boolean get() = live?.waitingForNetwork == true
    val fraction: Float
        get() = if (entity.filesize > 0) {
            (bytesNow.toFloat() / entity.filesize).coerceIn(0f, 1f)
        } else 0f
    /** Seconds remaining at the current rate, or null if not computable. */
    val etaSeconds: Long?
        get() = if (bytesPerSecond > 0 && entity.filesize > 0) {
            ((entity.filesize - bytesNow).coerceAtLeast(0)) / bytesPerSecond
        } else null
}

data class ActiveUiState(
    val rows: List<ActiveRow> = emptyList()
) {
    val isEmpty: Boolean get() = rows.isEmpty()
    val grouped: Map<DownloadState, List<ActiveRow>>
        get() = rows.groupBy { it.entity.state }
}

@HiltViewModel
class ActiveDownloadsViewModel @Inject constructor(
    downloadDao: DownloadDao,
    progressTracker: DownloadProgressTracker,
    private val controller: DownloadController
) : ViewModel() {

    val state: StateFlow<ActiveUiState> = combine(
        downloadDao.observeAll(),
        progressTracker.progress
    ) { entities, liveMap ->
        // Stable display ordering: most recently updated first within each
        // state section (the screen re-groups by state for headers).
        val sortedEntities = entities.sortedByDescending { it.updatedAt }
        ActiveUiState(
            rows = sortedEntities.map { e ->
                ActiveRow(entity = e, live = liveMap[e.rdId])
            }
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ActiveUiState()
    )

    fun pause(id: String) { viewModelScope.launch { controller.pause(id) } }
    fun resume(id: String) { viewModelScope.launch { controller.resume(id) } }
    fun cancel(id: String) { viewModelScope.launch { controller.cancel(id) } }
    fun retry(id: String) { viewModelScope.launch { controller.retry(id) } }
}
