package com.abhinavxt.debforge.ui.setup

import androidx.compose.ui.res.stringResource
import com.abhinavxt.debforge.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun SetupScreen(viewModel: SetupViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    val clipboard = LocalClipboardManager.current
    val info = state.selectedInfo
    val serviceName = info?.displayName ?: stringResource(R.string.setup_your_service)
    val tokenLabel = info?.tokenLabel ?: "API token"

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(Modifier.height(32.dp))

            Text(
                text = "DebForge",
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = stringResource(R.string.setup_intro, serviceName, tokenLabel.lowercase()),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(8.dp))

            // Service picker — one chip per registered provider. Scrolls
            // horizontally once there are more services than fit.
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState())
            ) {
                state.providers.forEach { p ->
                    FilterChip(
                        selected = p.id == state.selected,
                        onClick = { viewModel.onProviderSelected(p.id) },
                        enabled = !state.isValidating,
                        label = { Text(p.displayName) }
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            if (state.deviceLoginAvailable) {
                DeviceLoginBlock(
                    serviceName = serviceName,
                    state = state,
                    onStart = viewModel::startDeviceLogin,
                    onCancel = viewModel::cancelDeviceLogin,
                    onOpen = { url -> uriHandler.openUri(url) }
                )
                Text(
                    stringResource(R.string.setup_or_paste, tokenLabel.lowercase()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            OutlinedTextField(
                value = state.token,
                onValueChange = viewModel::onTokenChanged,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(tokenLabel) },
                singleLine = true,
                enabled = !state.isValidating,
                trailingIcon = {
                    // Tokens are long random strings — pasting is the normal path.
                    TextButton(
                        onClick = { clipboard.getText()?.text?.let { viewModel.onTokenChanged(it.trim()) } },
                        enabled = !state.isValidating
                    ) { Text(stringResource(R.string.setup_paste)) }
                },
                isError = state.error != null,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(onDone = { viewModel.submit() }),
                supportingText = {
                    if (state.error != null) {
                        Text(state.error!!, color = MaterialTheme.colorScheme.error)
                    } else {
                        Text(stringResource(R.string.setup_pasted_from, info?.tokenUrlLabel.orEmpty()))
                    }
                }
            )

            Button(
                onClick = viewModel::submit,
                enabled = state.canSubmit,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (state.isValidating) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text(stringResource(R.string.setup_validate))
                }
            }

            Spacer(Modifier.height(8.dp))

            // Help block — non-essential, pushed below the fold visually.
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (info != null) {
                    TextButton(onClick = { uriHandler.openUri(info.tokenUrl) }) {
                        Text(stringResource(R.string.setup_get_token, tokenLabel.lowercase(), info.tokenUrlLabel))
                    }
                }
            }
            Text(
                text = stringResource(R.string.setup_privacy, tokenLabel.lowercase(), serviceName),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun DeviceLoginBlock(
    serviceName: String,
    state: SetupUiState,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onOpen: (String) -> Unit
) {
    val code = state.deviceCode
    if (code == null) {
        Button(
            onClick = onStart,
            enabled = !state.startingDeviceLogin && !state.isValidating,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (state.startingDeviceLogin) {
                CircularProgressIndicator(
                    modifier = Modifier.height(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            } else {
                Text(stringResource(R.string.setup_sign_in_with, serviceName))
            }
        }
        return
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                stringResource(R.string.setup_enter_code_at, code.verificationUrl.removePrefix("https://")),
                textAlign = TextAlign.Center
            )
            Text(
                code.userCode,
                style = MaterialTheme.typography.headlineMedium,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Button(onClick = { onOpen(code.directUrl ?: code.verificationUrl) }) {
                Text(stringResource(R.string.setup_open_service, serviceName))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.height(16.dp), strokeWidth = 2.dp)
                Text(
                    "  " + stringResource(R.string.setup_waiting_approval),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
        }
    }
}
