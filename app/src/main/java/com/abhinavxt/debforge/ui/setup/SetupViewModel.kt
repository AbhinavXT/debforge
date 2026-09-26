package com.abhinavxt.debforge.ui.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.abhinavxt.debforge.data.provider.ProviderInfo
import com.abhinavxt.debforge.data.repository.AuthRepository
import com.abhinavxt.debforge.domain.DataResult
import com.abhinavxt.debforge.domain.ProviderId
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI state for the sign-in screen. The screen is fully driven by this —
 * loading shows a spinner, error shows a message, neither hides the input so
 * the user can edit and retry.
 */
data class SetupUiState(
    val providers: List<ProviderInfo> = emptyList(),
    val selected: ProviderId = DEFAULT_PROVIDER,
    val token: String = "",
    val isValidating: Boolean = false,
    val error: String? = null
) {
    val canSubmit: Boolean get() = token.trim().length >= 8 && !isValidating
    val selectedInfo: ProviderInfo? get() = providers.firstOrNull { it.id == selected }

    companion object {
        /** Preselected on a fresh install. */
        val DEFAULT_PROVIDER = ProviderId.TORBOX
    }
}

@HiltViewModel
class SetupViewModel @Inject constructor(
    private val auth: AuthRepository
) : ViewModel() {

    private val _state = MutableStateFlow(SetupUiState(providers = auth.providers))
    val state: StateFlow<SetupUiState> = _state.asStateFlow()

    init {
        // Preselect the provider the user last chose (e.g. they switched to a
        // service they aren't signed in to yet from Settings).
        viewModelScope.launch {
            auth.activeProvider.first()?.let { active ->
                _state.update { it.copy(selected = active) }
            }
        }
    }

    fun onProviderSelected(id: ProviderId) {
        if (id == _state.value.selected || _state.value.isValidating) return
        _state.update { it.copy(selected = id, token = "", error = null) }
        // Persist the choice. If the user already has a token for this
        // provider, the auth gate flips straight to Home — a quick way back.
        viewModelScope.launch { auth.setActiveProvider(id) }
    }

    fun onTokenChanged(value: String) {
        // Clear any prior error as soon as the user edits.
        _state.update { it.copy(token = value, error = null) }
    }

    /**
     * Validates the token against the selected provider. On success it is
     * persisted and the root composable flips to Home automatically; on
     * failure we surface the error and stay here.
     */
    fun submit() {
        val current = _state.value
        if (!current.canSubmit) return
        _state.update { it.copy(isValidating = true, error = null) }
        viewModelScope.launch {
            when (val result = auth.saveAndValidate(current.selected, current.token.trim())) {
                is DataResult.Success -> _state.update { it.copy(isValidating = false) }
                is DataResult.Error -> _state.update { it.copy(isValidating = false, error = result.message) }
            }
        }
    }
}
