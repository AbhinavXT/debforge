package com.abhinavxt.debforge.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.abhinavxt.debforge.ui.home.HomeScreen
import com.abhinavxt.debforge.ui.setup.SetupScreen

/**
 * App root. Watches [AuthGateViewModel] and renders the right tree:
 *  - Loading → centered spinner (very brief, while the DataStore flow emits its
 *    first value).
 *  - Unauthenticated → [SetupScreen]. When the user validates a token, the auth
 *    flow flips and the composable swaps to [HomeScreen] automatically.
 *  - Authenticated → [HomeScreen] with bottom-nav between Browse / Active /
 *    Settings.
 *
 * We deliberately do NOT thread auth through a NavHost route; logging out
 * doesn't need a "back" path to the previous tree.
 */
@Composable
fun DebForgeRoot(viewModel: AuthGateViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    when (state) {
        AuthState.Loading -> SplashGate()
        AuthState.Unauthenticated -> SetupScreen()
        AuthState.Authenticated -> HomeScreen()
    }
}

@Composable
private fun SplashGate() {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    }
}
