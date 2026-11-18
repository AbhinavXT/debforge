package com.abhinavxt.debforge.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.abhinavxt.debforge.data.local.DownloadDao
import com.abhinavxt.debforge.data.prefs.SettingsStore
import com.abhinavxt.debforge.domain.DownloadState
import com.abhinavxt.debforge.player.AudioPlayer
import com.abhinavxt.debforge.ui.add.PendingAddStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Set by MainActivity (notification tap); HomeScreen opens Now playing and clears it. */
object NowPlayingRequest {
    val expand = MutableStateFlow(false)
}

/** What the shell needs beyond its tabs: audio, the unfinished-download count, shared YouTube links. */
@HiltViewModel
class ShellViewModel @Inject constructor(
    val audio: AudioPlayer,
    val pending: PendingAddStore,
    dao: DownloadDao,
    settings: SettingsStore
) : ViewModel() {
    val playerArtColor: StateFlow<Boolean> = settings.playerArtColorFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val activeCount: StateFlow<Int> = dao.observeAll()
        .map { rows -> rows.count { it.state == DownloadState.DOWNLOADING || it.state == DownloadState.QUEUED } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
}
