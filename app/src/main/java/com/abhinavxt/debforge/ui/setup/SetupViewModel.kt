package com.abhinavxt.debforge.ui.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.abhinavxt.debforge.data.provider.DeviceCode
import com.abhinavxt.debforge.data.provider.ProviderInfo
import com.abhinavxt.debforge.data.repository.AuthRepository
import com.abhinavxt.debforge.domain.DataResult
import com.abhinavxt.debforge.domain.ProviderId
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
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
    val error: String? = null,
    /** Selected provider supports "approve a code in the browser". */
    val deviceLoginAvailable: Boolean = false,
    /** Non-null while waiting for the user to approve [DeviceCode.userCode]. */
    val deviceCode: DeviceCode? = null,
    val startingDeviceLogin: Boolean = false
) {
    val canSubmit: Boolean get() = token.trim().length >= 8 && !isValidating && deviceCode == null
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

    private val _state = MutableStateFlow(
        SetupUiState(
            providers = auth.providers,
            deviceLoginAvailable = auth.supportsDeviceLogin(SetupUiState.DEFAULT_PROVIDER)
        )
    )
    val state: StateFlow<SetupUiState> = _state.asStateFlow()

    private var deviceJob: Job? = null

    init {
        // Preselect the provider the user last chose (e.g. they switched to a
        // service they aren't signed in to yet from Settings).
        viewModelScope.launch {
            auth.activeProvider.first()?.let { active ->
                _state.update {
                    it.copy(selected = active, deviceLoginAvailable = auth.supportsDeviceLogin(active))
                }
            }
        }
    }

    fun onProviderSelected(id: ProviderId) {
        if (id == _state.value.selected || _state.value.isValidating) return
        cancelDeviceLogin()
        _state.update {
            it.copy(
                selected = id,
                token = "",
                error = null,
                deviceLoginAvailable = auth.supportsDeviceLogin(id)
            )
        }
        // Persist the choice. If the user already has a token for this
        // provider, the auth gate flips straight to Home — a quick way back.
        viewModelScope.launch { auth.setActiveProvider(id) }
    }

    fun onTokenChanged(value: String) {
        _state.update { it.copy(token = value, error = null) }
    }

    /** Validates a pasted token against the selected provider. */
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

    /**
     * Device-code sign-in: get a code, show it, poll until the user approves
     * it on the service's website (or it expires). On success the token is
     * stored and the auth gate moves to Home by itself.
     */
    fun startDeviceLogin() {
        val provider = _state.value.selected
        cancelDeviceLogin()
        _state.update { it.copy(startingDeviceLogin = true, error = null) }
        deviceJob = viewModelScope.launch {
            val code = when (val r = auth.startDeviceLogin(provider)) {
                is DataResult.Success -> r.data
                is DataResult.Error -> {
                    _state.update { it.copy(startingDeviceLogin = false, error = r.message) }
                    return@launch
                }
            }
            _state.update { it.copy(startingDeviceLogin = false, deviceCode = code) }

            while (isActive) {
                delay(code.intervalSeconds * 1000L)
                if (System.currentTimeMillis() > code.expiresAtMillis) {
                    _state.update { it.copy(deviceCode = null, error = "The code expired — try again") }
                    return@launch
                }
                when (val r = auth.pollDeviceLogin(provider, code)) {
                    is DataResult.Success -> if (r.data != null) {
                        _state.update { it.copy(deviceCode = null) }
                        return@launch // gate flips to Home
                    }
                    is DataResult.Error -> {
                        _state.update { it.copy(deviceCode = null, error = r.message) }
                        return@launch
                    }
                }
            }
        }
    }

    fun cancelDeviceLogin() {
        deviceJob?.cancel()
        deviceJob = null
        _state.update { it.copy(deviceCode = null, startingDeviceLogin = false) }
    }
}
