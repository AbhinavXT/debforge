package com.abhinavxt.debforge.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.abhinavxt.debforge.data.local.DownloadDao
import com.abhinavxt.debforge.data.prefs.SettingsStore
import com.abhinavxt.debforge.domain.DownloadState
import com.abhinavxt.debforge.player.AudioPlayer
import com.abhinavxt.debforge.player.VideoHandoff
import com.abhinavxt.debforge.player.WatchResult
import com.abhinavxt.debforge.ui.browse.PlayRequest
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import com.abhinavxt.debforge.ui.add.PendingAddStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Set by MainActivity (notification tap); HomeScreen opens Now playing and clears it. */
object NowPlayingRequest {
    val expand = MutableStateFlow(false)
}

/** The outcome of "Watch video" in the audio player. */
sealed interface WatchEvent {
    data class Launch(val request: PlayRequest, val startMs: Long, val speed: Float) : WatchEvent
    object FileMissing : WatchEvent
    object StreamFailed : WatchEvent
}

/** What the shell needs beyond its tabs: audio, the unfinished-download count, shared YouTube links. */
@HiltViewModel
class ShellViewModel @Inject constructor(
    val audio: AudioPlayer,
    val pending: PendingAddStore,
    private val videoHandoff: VideoHandoff,
    dao: DownloadDao,
    settings: SettingsStore
) : ViewModel() {
    val playerArtColor: StateFlow<Boolean> = settings.playerArtColorFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val audioSkipSeconds: StateFlow<Int> = settings.audioSkipSecondsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), com.abhinavxt.debforge.player.DEFAULT_SKIP_SECONDS)
    init {
        // The mini player comes back paused after the app was closed.
        viewModelScope.launch { audio.restore() }
    }

    val activeCount: StateFlow<Int> = dao.observeAll()
        .map { rows -> rows.count { it.state == DownloadState.DOWNLOADING || it.state == DownloadState.QUEUED } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val _watchBusy = MutableStateFlow(false)
    /** True while "Watch video" looks up the video. */
    val watchBusy: StateFlow<Boolean> = _watchBusy.asStateFlow()
    private val _watchEvents = Channel<WatchEvent>(Channel.BUFFERED)
    val watchEvents = _watchEvents.receiveAsFlow()

    /** Continues the current audio item as video from the current position and speed. The video player parks the audio queue. */
    fun watchVideo() {
        val state = audio.state.value
        val track = state.track?.takeIf { it.canWatch } ?: return
        if (!_watchBusy.compareAndSet(expect = false, update = true)) return
        val startMs = state.positionMs
        val speed = state.speed
        viewModelScope.launch {
            try {
                _watchEvents.send(when (val r = videoHandoff.watchRequest(track)) {
                    is WatchResult.Ready -> WatchEvent.Launch(r.request, startMs, speed)
                    WatchResult.FileMissing -> WatchEvent.FileMissing
                    WatchResult.StreamFailed -> WatchEvent.StreamFailed
                })
            } finally {
                _watchBusy.value = false
            }
        }
    }
}
