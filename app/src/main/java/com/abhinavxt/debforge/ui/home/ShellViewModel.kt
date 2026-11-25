package com.abhinavxt.debforge.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.abhinavxt.debforge.data.local.DownloadDao
import com.abhinavxt.debforge.data.prefs.SettingsStore
import com.abhinavxt.debforge.domain.DownloadState
import com.abhinavxt.debforge.player.AudioPlayer
import com.abhinavxt.debforge.player.HandoffFailure
import com.abhinavxt.debforge.player.VideoHandoff
import com.abhinavxt.debforge.player.watchStart
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Set by MainActivity (notification tap); HomeScreen opens Now playing and clears it. */
object NowPlayingRequest {
    val expand = MutableStateFlow(false)

    /** The "Search" launcher shortcut: HomeScreen switches to the Search tab and clears it. */
    val openSearch = MutableStateFlow(false)

    /** Open Settings (at [com.abhinavxt.debforge.ui.settings.SettingsTarget]). */
    val openSettings = MutableStateFlow(false)
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
    private val lyricsRepo: com.abhinavxt.debforge.data.lyrics.LyricsRepository,
    dao: DownloadDao,
    settings: SettingsStore,
    searchSources: com.abhinavxt.debforge.data.search.SearchSourceStore
) : ViewModel() {
    /** Null until the first read of the tab settings. */
    val navPrefs: StateFlow<NavPrefs?> = combine(
        settings.tabsFlow, settings.startTabFlow, searchSources.sources.map { it.isNotEmpty() }
    ) { bar, start, hasSource -> NavPrefs(bar, start, hasSource) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    suspend fun lyrics(title: String, artist: String?, durationMs: Long) = lyricsRepo.load(title, artist, durationMs)

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

    /**
     * Continues the current audio item as video at its speed. The audio is paused at once, so it does not run on while
     * the video is looked up, and the position is read just before launching. A failed lookup leaves it paused.
     * [from] is a failed "Play as audio" handoff: the item and spot to go back to.
     */
    fun watchVideo(from: HandoffFailure? = null) {
        val state = audio.state.value
        val track = from?.track ?: state.track?.takeIf { it.canWatch } ?: return
        if (!_watchBusy.compareAndSet(expect = false, update = true)) return
        if (from == null) audio.pause()
        viewModelScope.launch {
            try {
                _watchEvents.send(when (val r = videoHandoff.watchRequest(track)) {
                    is WatchResult.Ready -> {
                        val start = from?.let { watchStart(it.startMs, it.speed) }
                            ?: watchStart(audio.positionNow(), audio.state.value.speed)
                        WatchEvent.Launch(r.request, start.positionMs, start.speed)
                    }
                    WatchResult.FileMissing -> WatchEvent.FileMissing
                    WatchResult.StreamFailed -> WatchEvent.StreamFailed
                })
            } finally {
                _watchBusy.value = false
            }
        }
    }
}
