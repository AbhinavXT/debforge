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

/**
 * App root. A debrid service is optional (the Browser tab downloads without
 * one), so [HomeScreen] always shows once [AuthGateViewModel] knows whether a
 * service is signed in; the Library tab shows Setup until one is.
 * Loading → centered spinner (very brief, while the DataStore flow emits its
 * first value).
 */
@Composable
fun DebForgeRoot(viewModel: AuthGateViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    when (state) {
        AuthState.Loading -> SplashGate()
        else -> HomeScreen(signedIn = state == AuthState.Authenticated)
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
