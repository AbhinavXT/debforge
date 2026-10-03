package com.abhinavxt.debforge.ui.settings

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Visibility
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.abhinavxt.debforge.R
import com.abhinavxt.debforge.data.provider.DeviceCode
import com.abhinavxt.debforge.data.trakt.TraktApi
import com.abhinavxt.debforge.data.trakt.TraktRepository
import com.abhinavxt.debforge.data.trakt.TraktStore
import com.abhinavxt.debforge.ui.components.RowDivider
import com.abhinavxt.debforge.ui.components.SectionCard
import com.abhinavxt.debforge.ui.components.SettingRow
import com.abhinavxt.debforge.ui.components.SwitchSettingRow
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Where connecting to Trakt is up to. */
sealed interface TraktLogin {
    data object Idle : TraktLogin
    /** Asking for the Client ID and Secret. */
    data object Setup : TraktLogin
    data object Starting : TraktLogin
    data class Code(val code: DeviceCode) : TraktLogin
    data class Failed(val message: Int) : TraktLogin
}

@HiltViewModel
class TraktViewModel @Inject constructor(
    private val trakt: TraktRepository,
    private val store: TraktStore
) : ViewModel() {

    val user: StateFlow<String?> = store.userFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val credentials: StateFlow<TraktStore.Credentials?> =
        store.credentialsFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val scrobble: StateFlow<Boolean> = store.scrobbleFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val syncWatched: StateFlow<Boolean> = store.syncWatchedFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val lastSync: StateFlow<Long> = store.lastSyncFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    private val _login = MutableStateFlow<TraktLogin>(TraktLogin.Idle)
    val login: StateFlow<TraktLogin> = _login.asStateFlow()

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    private var pollJob: Job? = null

    fun openSetup() { _login.value = TraktLogin.Setup }

    fun cancel() {
        pollJob?.cancel()
        _login.value = TraktLogin.Idle
    }

    fun connect(clientId: String, clientSecret: String) {
        pollJob?.cancel()
        _login.value = TraktLogin.Starting
        pollJob = viewModelScope.launch {
            val code = try {
                trakt.startLogin(clientId, clientSecret)
            } catch (e: CancellationException) {
                throw e
            } catch (e: retrofit2.HttpException) {
                _login.value = TraktLogin.Failed(R.string.trakt_failed)
                return@launch
            } catch (e: Exception) {
                _login.value = TraktLogin.Failed(R.string.trakt_unreachable)
                return@launch
            }
            _login.value = TraktLogin.Code(code)
            while (System.currentTimeMillis() < code.expiresAtMillis) {
                delay(code.intervalSeconds * 1000L)
                when (trakt.poll(code)) {
                    TraktRepository.Poll.PENDING -> continue
                    TraktRepository.Poll.APPROVED -> { _login.value = TraktLogin.Idle; return@launch }
                    TraktRepository.Poll.DENIED -> { _login.value = TraktLogin.Failed(R.string.trakt_denied); return@launch }
                    TraktRepository.Poll.EXPIRED -> break
                    TraktRepository.Poll.FAILED -> { _login.value = TraktLogin.Failed(R.string.trakt_failed); return@launch }
                }
            }
            _login.value = TraktLogin.Failed(R.string.trakt_expired)
        }
    }

    fun setScrobble(v: Boolean) { viewModelScope.launch { store.setScrobble(v) } }

    fun setSyncWatched(v: Boolean) {
        viewModelScope.launch {
            store.setSyncWatched(v)
            if (v) syncNow()
        }
    }

    fun syncNow() {
        if (_syncing.value) return
        _syncing.value = true
        viewModelScope.launch {
            try { trakt.syncWatched(force = true) } finally { _syncing.value = false }
        }
    }

    fun signOut() { viewModelScope.launch { trakt.signOut() } }
}

@Composable
fun TraktSection(viewModel: TraktViewModel = hiltViewModel()) {
    val user by viewModel.user.collectAsStateWithLifecycle()
    val login by viewModel.login.collectAsStateWithLifecycle()
    val scrobble by viewModel.scrobble.collectAsStateWithLifecycle()
    val syncWatched by viewModel.syncWatched.collectAsStateWithLifecycle()
    val lastSync by viewModel.lastSync.collectAsStateWithLifecycle()
    val syncing by viewModel.syncing.collectAsStateWithLifecycle()
    val credentials by viewModel.credentials.collectAsStateWithLifecycle()

    SectionCard(title = stringResource(R.string.trakt_title)) {
        val signedIn = user
        if (signedIn == null) {
            SettingRow(
                icon = Icons.Rounded.Sync,
                title = stringResource(R.string.trakt_title),
                subtitle = stringResource(R.string.trakt_detail)
            )
            Row(Modifier.padding(start = 68.dp, end = 16.dp, bottom = 12.dp)) {
                FilledTonalButton(onClick = viewModel::openSetup) { Text(stringResource(R.string.trakt_connect)) }
            }
        } else {
            SettingRow(
                icon = Icons.Rounded.Sync,
                title = if (signedIn.isBlank()) stringResource(R.string.trakt_signed_in_plain)
                else stringResource(R.string.trakt_signed_in, signedIn),
                subtitle = if (syncWatched && lastSync > 0) {
                    stringResource(R.string.trakt_last_sync, DateUtils.getRelativeTimeSpanString(lastSync).toString())
                } else null,
                trailing = if (syncWatched) ({
                    if (syncing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else TextButton(onClick = viewModel::syncNow) { Text(stringResource(R.string.trakt_sync_now)) }
                }) else null
            )
            RowDivider()
            SwitchSettingRow(
                icon = Icons.Rounded.Visibility,
                title = stringResource(R.string.trakt_scrobble),
                subtitle = stringResource(R.string.trakt_scrobble_detail),
                checked = scrobble,
                onChange = viewModel::setScrobble
            )
            RowDivider()
            SwitchSettingRow(
                icon = Icons.Rounded.DoneAll,
                title = stringResource(R.string.trakt_sync_watched),
                subtitle = stringResource(R.string.trakt_sync_watched_detail),
                checked = syncWatched,
                onChange = viewModel::setSyncWatched
            )
            RowDivider()
            SettingRow(
                icon = Icons.AutoMirrored.Rounded.Logout,
                title = stringResource(R.string.trakt_sign_out),
                onClick = viewModel::signOut
            )
        }
    }

    when (val s = login) {
        TraktLogin.Idle -> Unit
        TraktLogin.Setup, TraktLogin.Starting -> TraktSetupDialog(
            saved = credentials,
            starting = s == TraktLogin.Starting,
            onConnect = viewModel::connect,
            onDismiss = viewModel::cancel
        )
        is TraktLogin.Code -> TraktCodeDialog(s.code, onDismiss = viewModel::cancel)
        is TraktLogin.Failed -> AlertDialog(
            onDismissRequest = viewModel::cancel,
            title = { Text(stringResource(R.string.trakt_setup_title)) },
            text = { Text(stringResource(s.message), color = MaterialTheme.colorScheme.error) },
            confirmButton = { TextButton(onClick = viewModel::openSetup) { Text(stringResource(R.string.action_retry)) } },
            dismissButton = { TextButton(onClick = viewModel::cancel) { Text(stringResource(R.string.action_close)) } }
        )
    }
}

/** The user's own Trakt app: how to make one, and its Client ID and Secret. */
@Composable
private fun TraktSetupDialog(
    saved: TraktStore.Credentials?,
    starting: Boolean,
    onConnect: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    val uriHandler = LocalUriHandler.current
    var clientId by remember(saved) { mutableStateOf(saved?.clientId.orEmpty()) }
    var clientSecret by remember(saved) { mutableStateOf(saved?.clientSecret.orEmpty()) }
    val ok = clientId.isNotBlank() && clientSecret.isNotBlank() && !starting

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.trakt_setup_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.trakt_setup_body, TraktApi.OOB_REDIRECT),
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedButton(onClick = { uriHandler.openUri(TraktApi.NEW_APP_URL) }) {
                    Text(stringResource(R.string.trakt_open_new_app))
                }
                OutlinedTextField(
                    value = clientId, onValueChange = { clientId = it.trim() },
                    label = { Text(stringResource(R.string.trakt_client_id)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = clientSecret, onValueChange = { clientSecret = it.trim() },
                    label = { Text(stringResource(R.string.trakt_client_secret)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConnect(clientId, clientSecret) }, enabled = ok) {
                if (starting) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text(stringResource(R.string.trakt_continue))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    )
}

/** The code to enter on trakt.tv/activate, while we wait for approval. */
@Composable
private fun TraktCodeDialog(code: DeviceCode, onDismiss: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.trakt_code_title)) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.trakt_code_body, code.verificationUrl), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(16.dp))
                SelectionContainer {
                    Text(
                        code.userCode,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 30.sp,
                        letterSpacing = 4.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.trakt_waiting), style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { uriHandler.openUri(code.verificationUrl) }) { Text(stringResource(R.string.trakt_code_open)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    )
}
