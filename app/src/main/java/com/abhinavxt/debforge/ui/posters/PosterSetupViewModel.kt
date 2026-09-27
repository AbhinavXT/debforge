package com.abhinavxt.debforge.ui.posters

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.abhinavxt.debforge.data.metadata.MetadataRepository
import com.abhinavxt.debforge.data.metadata.MetadataRepository.KeyCheck
import com.abhinavxt.debforge.data.prefs.SettingsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PosterSetupState(
    val draft: String = "",
    val checking: Boolean = false,
    /** Result of the last check; null = not checked since the draft changed. */
    val problem: KeyCheck? = null,
    /** Saved: the sheet closes. */
    val done: Boolean = false
)

/**
 * Drives [PosterSetupSheet]: checks a pasted TMDB key with TMDB before saving
 * it, so a typo shows up here instead of as a grid that silently never gets
 * posters. If TMDB can't be reached the user may save anyway.
 */
@HiltViewModel
class PosterSetupViewModel @Inject constructor(
    private val metadata: MetadataRepository,
    private val settings: SettingsStore
) : ViewModel() {

    private val _state = MutableStateFlow(PosterSetupState())
    val state: StateFlow<PosterSetupState> = _state.asStateFlow()
    private var checkJob: Job? = null

    fun reset() {
        checkJob?.cancel()
        _state.value = PosterSetupState()
    }

    fun onDraft(text: String) {
        checkJob?.cancel()
        _state.update { it.copy(draft = text, problem = null, checking = false) }
    }

    fun checkAndSave() {
        val draft = _state.value.draft
        if (draft.isBlank() || _state.value.checking) return
        checkJob = viewModelScope.launch {
            _state.update { it.copy(checking = true, problem = null) }
            when (val result = metadata.checkKey(draft)) {
                KeyCheck.VALID -> save(draft)
                else -> _state.update { it.copy(checking = false, problem = result) }
            }
        }
    }

    /** After UNREACHABLE: the key looked right, TMDB just didn't answer. */
    fun saveAnyway() {
        viewModelScope.launch { save(_state.value.draft) }
    }

    private suspend fun save(key: String) {
        settings.setTmdbKey(key)
        // A new key may find titles the previous one cached as "no poster".
        metadata.clearCache()
        _state.update { it.copy(checking = false, problem = null, done = true) }
    }
}
