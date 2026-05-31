package com.abhinavxt.debforge.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.abhinavxt.debforge.data.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Top-level auth state — what the root composable switches on. */
sealed interface AuthState {
    data object Loading : AuthState
    data object Unauthenticated : AuthState
    data object Authenticated : AuthState
}

/**
 * Watches token presence and exposes a single [AuthState] for routing. This is
 * deliberately a thin wrapper over [AuthRepository.hasToken] so the rest of the
 * app doesn't have to know how authentication is stored — when the user pastes
 * a valid token in Setup, this flips to Authenticated automatically.
 */
@HiltViewModel
class AuthGateViewModel @Inject constructor(
    auth: AuthRepository
) : ViewModel() {

    val state: StateFlow<AuthState> = auth.hasToken
        .map { hasToken -> if (hasToken) AuthState.Authenticated else AuthState.Unauthenticated }
        .stateIn(
            scope = viewModelScope,
            // While subscribed only (with a small stop timeout) so the flow doesn't
            // run forever in the background.
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = AuthState.Loading
        )
}
