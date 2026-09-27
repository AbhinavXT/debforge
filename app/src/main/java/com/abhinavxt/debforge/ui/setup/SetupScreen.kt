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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
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
    // Translated ("API key" / "API token"); used as-is inside sentences.
    val tokenLabel = stringResource(info?.let { com.abhinavxt.debforge.data.provider.tokenLabelRes(it) } ?: R.string.token_api_token)

    val cs = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(cs.surface)
            // Soft glow of the theme colour behind the header.
            .background(
                Brush.verticalGradient(
                    0f to cs.primaryContainer.copy(alpha = 0.55f),
                    0.45f to cs.surface
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(Modifier.height(24.dp))

            // App mark: download arrow on a theme gradient.
            Box(
                Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(Brush.linearGradient(listOf(cs.primary, cs.tertiary))),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.Download,
                    contentDescription = null,
                    tint = cs.onPrimary,
                    modifier = Modifier.size(40.dp)
                )
            }
            Text(
                text = "DebForge",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = cs.onSurface
            )
            Text(
                text = stringResource(R.string.setup_intro, serviceName, tokenLabel),
                style = MaterialTheme.typography.bodyLarge,
                color = cs.onSurfaceVariant
            )

            Spacer(Modifier.height(8.dp))

            // Service picker — one chip per registered provider. Scrolls
            // horizontally once there are more services than fit.
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState())
            ) {
                state.providers.forEach { p ->
                    val selected = p.id == state.selected
                    FilterChip(
                        selected = selected,
                        onClick = { viewModel.onProviderSelected(p.id) },
                        enabled = !state.isValidating,
                        leadingIcon = if (selected) {
                            { Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                        } else null,
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
                    stringResource(R.string.setup_or_paste, tokenLabel),
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
                leadingIcon = { Icon(Icons.Rounded.Key, contentDescription = null) },
                shape = MaterialTheme.shapes.medium,
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
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) {
                if (state.isValidating) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(20.dp),
                        strokeWidth = 2.dp,
                        color = androidx.compose.material3.LocalContentColor.current
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
                        Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.setup_get_token, tokenLabel, info.tokenUrlLabel))
                    }
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .background(cs.surfaceContainerLow)
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.Lock, contentDescription = null, tint = cs.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text(
                    text = stringResource(R.string.setup_privacy, tokenLabel, serviceName),
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant
                )
            }
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
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) {
            if (state.startingDeviceLogin) {
                CircularProgressIndicator(
                    modifier = Modifier.height(20.dp),
                    strokeWidth = 2.dp,
                    color = androidx.compose.material3.LocalContentColor.current
                )
            } else {
                Text(stringResource(R.string.setup_sign_in_with, serviceName))
            }
        }
        return
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                stringResource(R.string.setup_enter_code_at, code.verificationUrl.removePrefix("https://")),
                textAlign = TextAlign.Center
            )
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shape = MaterialTheme.shapes.large
            ) {
                Text(
                    code.userCode,
                    style = MaterialTheme.typography.headlineMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
                )
            }
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
