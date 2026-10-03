package com.abhinavxt.debforge.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.abhinavxt.debforge.R
import com.abhinavxt.debforge.data.subtitles.OpenSubtitlesApi
import com.abhinavxt.debforge.data.subtitles.OpenSubtitlesRepository
import com.abhinavxt.debforge.data.subtitles.OpenSubtitlesStore
import com.abhinavxt.debforge.ui.components.SectionCard
import com.abhinavxt.debforge.ui.components.SettingRow
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OpenSubtitlesViewModel @Inject constructor(
    private val repository: OpenSubtitlesRepository,
    store: OpenSubtitlesStore
) : ViewModel() {

    val apiKey: StateFlow<String?> = store.apiKeyFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val username: StateFlow<String?> = store.usernameFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Null while idle; true while checking; the result's message otherwise. */
    sealed interface Check {
        data object Idle : Check
        data object Checking : Check
        data object Saved : Check
        data class Problem(val message: Int) : Check
    }

    private val _check = MutableStateFlow<Check>(Check.Idle)
    val check: StateFlow<Check> = _check.asStateFlow()

    fun resetCheck() { _check.value = Check.Idle }

    /** Checks with OpenSubtitles first; saves anyway only when it couldn't be reached. */
    fun save(apiKey: String, username: String, password: String, force: Boolean = false) {
        _check.value = Check.Checking
        viewModelScope.launch {
            val result = if (force) OpenSubtitlesRepository.KeyCheck.VALID else repository.check(apiKey, username, password)
            _check.value = when (result) {
                OpenSubtitlesRepository.KeyCheck.VALID -> {
                    repository.save(apiKey, username, password)
                    Check.Saved
                }
                OpenSubtitlesRepository.KeyCheck.REJECTED -> Check.Problem(R.string.os_key_rejected)
                OpenSubtitlesRepository.KeyCheck.BAD_LOGIN -> Check.Problem(R.string.os_login_rejected)
                OpenSubtitlesRepository.KeyCheck.UNREACHABLE -> Check.Problem(R.string.os_unreachable)
            }
        }
    }

    fun remove() { viewModelScope.launch { repository.remove() } }
}

@Composable
fun OpenSubtitlesSection(viewModel: OpenSubtitlesViewModel = hiltViewModel()) {
    val apiKey by viewModel.apiKey.collectAsStateWithLifecycle()
    val username by viewModel.username.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf(false) }

    SectionCard(title = stringResource(R.string.os_title)) {
        SettingRow(
            icon = Icons.Rounded.Subtitles,
            title = stringResource(R.string.os_title),
            subtitle = when {
                apiKey == null -> stringResource(R.string.os_detail)
                username != null -> stringResource(R.string.os_on_account, username!!)
                else -> stringResource(R.string.os_on_key)
            }
        )
        Row(
            Modifier.padding(start = 68.dp, end = 16.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilledTonalButton(onClick = { viewModel.resetCheck(); editing = true }) {
                Text(stringResource(if (apiKey == null) R.string.os_set_up else R.string.os_change))
            }
            if (apiKey != null) {
                OutlinedButton(onClick = viewModel::remove) { Text(stringResource(R.string.action_remove)) }
            }
        }
    }

    if (editing) {
        OpenSubtitlesDialog(
            viewModel = viewModel,
            initialKey = apiKey.orEmpty(),
            initialUser = username.orEmpty(),
            onDismiss = { editing = false }
        )
    }
}

@Composable
private fun OpenSubtitlesDialog(
    viewModel: OpenSubtitlesViewModel,
    initialKey: String,
    initialUser: String,
    onDismiss: () -> Unit
) {
    val uriHandler = LocalUriHandler.current
    val check by viewModel.check.collectAsStateWithLifecycle()
    var key by remember { mutableStateOf(initialKey) }
    var user by remember { mutableStateOf(initialUser) }
    var password by remember { mutableStateOf("") }
    if (check is OpenSubtitlesViewModel.Check.Saved) {
        androidx.compose.runtime.LaunchedEffect(Unit) { onDismiss() }
        return
    }
    val checking = check is OpenSubtitlesViewModel.Check.Checking
    val problem = (check as? OpenSubtitlesViewModel.Check.Problem)?.message

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.os_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.os_setup_body), style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = { uriHandler.openUri(OpenSubtitlesApi.KEY_PAGE_URL) }) {
                    Text(stringResource(R.string.os_get_key))
                }
                OutlinedTextField(
                    value = key, onValueChange = { key = it.trim(); viewModel.resetCheck() },
                    label = { Text(stringResource(R.string.os_api_key)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    stringResource(R.string.os_account_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = user, onValueChange = { user = it; viewModel.resetCheck() },
                    label = { Text(stringResource(R.string.os_username)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = password, onValueChange = { password = it; viewModel.resetCheck() },
                    label = { Text(stringResource(R.string.os_password)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )
                if (problem != null) Text(stringResource(problem), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            if (problem == R.string.os_unreachable) {
                TextButton(onClick = { viewModel.save(key, user, password, force = true) }) {
                    Text(stringResource(R.string.os_save_anyway))
                }
            } else {
                TextButton(onClick = { viewModel.save(key, user, password) }, enabled = key.isNotBlank() && !checking) {
                    if (checking) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Text(stringResource(R.string.action_save))
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    )
}
