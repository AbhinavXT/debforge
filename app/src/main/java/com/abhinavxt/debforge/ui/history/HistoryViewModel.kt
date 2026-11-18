package com.abhinavxt.debforge.ui.history

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.abhinavxt.debforge.R
import com.abhinavxt.debforge.data.history.HistoryEntity
import com.abhinavxt.debforge.data.history.PlayHistory
import com.abhinavxt.debforge.data.local.DownloadDao
import com.abhinavxt.debforge.data.prefs.SettingsStore
import com.abhinavxt.debforge.data.repository.DownloadsRepository
import com.abhinavxt.debforge.domain.DataResult
import com.abhinavxt.debforge.domain.DownloadItem
import com.abhinavxt.debforge.domain.DownloadState
import com.abhinavxt.debforge.download.LocalFiles
import com.abhinavxt.debforge.player.AudioPlayer
import com.abhinavxt.debforge.player.LocalPlayback
import com.abhinavxt.debforge.player.audioTrackFor
import com.abhinavxt.debforge.ui.browse.PlayRequest
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What the screen should do after a tap. */
sealed interface HistoryEvent {
    data class Play(val request: PlayRequest) : HistoryEvent
    data class OpenYouTube(val url: String) : HistoryEvent
    /** Could not replay [itemId]; the screen shows [text] with an offer to remove the row. */
    data class Unplayable(val itemId: String, val text: String) : HistoryEvent
}

/** Rows newest first; [loaded] is false until the first read, so "Nothing played yet" does not flash. */
data class HistoryUiState(val items: List<HistoryEntity> = emptyList(), val loaded: Boolean = false)

@HiltViewModel
class HistoryViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val history: PlayHistory,
    private val settings: SettingsStore,
    private val downloads: DownloadDao,
    private val localPlayback: LocalPlayback,
    private val repository: DownloadsRepository,
    private val audio: AudioPlayer
) : ViewModel() {

    /** From Room's Flow, so the read and every change run off the main thread. */
    val state: StateFlow<HistoryUiState> = history.observe()
        .map { HistoryUiState(it, loaded = true) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryUiState())

    val paused: StateFlow<Boolean> = settings.historyPausedFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val useInternalPlayer: StateFlow<Boolean> = settings.internalPlayerFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    private val _events = Channel<HistoryEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    fun setPaused(paused: Boolean) { viewModelScope.launch { settings.setHistoryPaused(paused) } }

    fun remove(itemId: String) { viewModelScope.launch { runCatching { history.remove(itemId) } } }

    fun clear() { viewModelScope.launch { runCatching { history.clear() } } }

    /** Replays [e]; anything that cannot be played ends as [HistoryEvent.Unplayable], never an exception. */
    fun replay(e: HistoryEntity) {
        viewModelScope.launch {
            try {
                when (replayRoute(e)) {
                    ReplayRoute.YOUTUBE -> {
                        val id = youtubeVideoId(e)
                        if (id == null) unplayable(e, missing(e)) else _events.send(HistoryEvent.OpenYouTube(youtubeWatchUrl(id)))
                    }
                    ReplayRoute.LOCAL_AUDIO -> playAudio(e)
                    ReplayRoute.VIDEO -> playVideo(e)
                    ReplayRoute.UNPLAYABLE -> unplayable(e, missing(e))
                }
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (t: Throwable) {
                unplayable(e, context.getString(R.string.continue_unavailable, e.title, t.message ?: t.javaClass.simpleName))
            }
        }
    }

    private suspend fun unplayable(e: HistoryEntity, text: String) = _events.send(HistoryEvent.Unplayable(e.itemId, text))

    private fun missing(e: HistoryEntity) = context.getString(R.string.history_missing, e.title)

    private suspend fun playAudio(e: HistoryEntity) {
        val row = downloads.getById(e.sourceRef)?.takeIf { it.state == DownloadState.COMPLETED }
        // The path may point at a file that was moved, or a content:// uri whose permission is gone.
        val track = row?.takeIf { r -> r.finalFilePath?.let(LocalFiles::exists) == true }?.let { audioTrackFor(context, it) }
        if (track == null) unplayable(e, missing(e)) else audio.play(listOf(track), 0)
    }

    private suspend fun playVideo(e: HistoryEntity) {
        // Downloaded since (or all along): no link, works offline.
        localPlayback.requestFor(e.itemId)?.let { _events.send(HistoryEvent.Play(it)); return }
        val provider = replayProvider(e)
        if (provider == null) { unplayable(e, missing(e)); return }
        val item = DownloadItem(
            id = e.itemId, provider = provider, filename = e.title, sourceRef = e.sourceRef, downloadUrl = null,
            host = "", filesize = 0, maxConnections = 1, addedAt = null, parentRef = e.parentRef
        )
        when (val r = repository.resolveLink(provider, e.sourceRef)) {
            is DataResult.Success -> _events.send(HistoryEvent.Play(PlayRequest(Uri.parse(r.data.url), e.title, item, e.showKey)))
            is DataResult.Error -> unplayable(e, context.getString(R.string.continue_unavailable, e.title, r.message))
        }
    }
}
