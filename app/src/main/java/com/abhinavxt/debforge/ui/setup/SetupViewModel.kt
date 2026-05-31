package com.abhinavxt.debforge.ui.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.abhinavxt.debforge.data.repository.AuthRepository
import com.abhinavxt.debforge.domain.DataResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI state for the token setup screen. The screen is fully driven by this —
 * loading shows a spinner, error shows a message banner, neither hides the
 * input so the user can edit and retry.
 */
data class SetupUiState(
    val token: String = "",
    val isValidating: Boolean = false,
    val error: String? = null
) {
    val canSubmit: Boolean get() = token.trim().length >= 8 && !isValidating
}

@HiltViewModel
class SetupViewModel @Inject constructor(
    private val auth: AuthRepository
) : ViewModel() {

    private val _state = MutableStateFlow(SetupUiState())
    val state: StateFlow<SetupUiState> = _state.asStateFlow()

    fun onTokenChanged(value: String) {
        // Clear any prior error as soon as the user edits — feels much more
        // responsive than waiting for the next submit.
        _state.update { it.copy(token = value, error = null) }
    }

    /**
     * Validates the current token against the RD /user endpoint. On success the
     * token is persisted by [AuthRepository] and the root composable will flip
     * to the authenticated tree automatically; on failure we surface the error
     * and leave the user in the same state.
     */
    fun submit() {
        val current = _state.value
        if (!current.canSubmit) return
        _state.update { it.copy(isValidating = true, error = null) }
        viewModelScope.launch {
            when (val result = auth.saveAndValidate(current.token.trim())) {
                is DataResult.Success -> {
                    // No-op on UI: AuthGateViewModel observes hasToken and
                    // routes us to Home. We still clear the loading flag.
                    _state.update { it.copy(isValidating = false) }
                }
                is DataResult.Error -> {
                    _state.update { it.copy(isValidating = false, error = result.message) }
                }
            }
        }
    }
}
