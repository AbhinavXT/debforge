package com.abhinavxt.debforge.ui.settings

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.SettingsBackupRestore
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import com.abhinavxt.debforge.ui.components.SectionCard
import com.abhinavxt.debforge.ui.components.SettingRow
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.abhinavxt.debforge.R
import com.abhinavxt.debforge.data.backup.BackupCrypto
import com.abhinavxt.debforge.data.backup.BackupManager
import com.abhinavxt.debforge.data.repository.AuthRepository
import com.abhinavxt.debforge.download.DownloadScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.LocalDate
import javax.inject.Inject

sealed interface BackupUi {
    data object Idle : BackupUi
    data object Working : BackupUi
    /** Restore file needs the password for its sign-ins. */
    data class NeedsPassword(val uri: Uri, val wrongPassword: Boolean = false) : BackupUi
    data class Done(val message: String) : BackupUi
    data class Failed(val message: String) : BackupUi
}

@HiltViewModel
class BackupViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val backup: BackupManager,
    private val auth: AuthRepository,
    private val scheduler: DownloadScheduler
) : ViewModel() {

    private val _ui = MutableStateFlow<BackupUi>(BackupUi.Idle)
    val ui: StateFlow<BackupUi> = _ui.asStateFlow()

    /**
     * Password chosen in the options dialog, waiting for the user to pick where
     * to save. Held here (not in composition) so a rotation while the system
     * picker is open doesn't silently drop the sign-ins from the backup.
     */
    private var pendingPassword: CharArray? = null

    fun setPendingPassword(password: CharArray?) {
        pendingPassword?.fill('\u0000')
        pendingPassword = password
    }

    /** Called with the picker's result; null = the user backed out. */
    fun onSaveLocation(uri: Uri?) {
        val pw = pendingPassword
        pendingPassword = null
        if (uri != null) export(uri, pw) else pw?.fill('\u0000')
    }

    override fun onCleared() {
        pendingPassword?.fill('\u0000')
    }

    fun dismiss() { _ui.value = BackupUi.Idle }

    fun export(uri: Uri, password: CharArray?) {
        _ui.value = BackupUi.Working
        viewModelScope.launch {
            _ui.value = try {
                val json = withContext(Dispatchers.Default) { backup.export(password) }
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(json.toByteArray()) }
                        ?: throw IOException("can't write there")
                }
                BackupUi.Done(context.getString(
                    if (password != null) R.string.backup_saved_with_signins else R.string.backup_saved
                ))
            } catch (e: Exception) {
                BackupUi.Failed(context.getString(R.string.backup_failed, e.message.orEmpty()))
            } finally {
                password?.fill('\u0000')
            }
        }
    }

    /** Step 1 of restoring: read the file, ask for a password only if it has sign-ins. */
    fun startRestore(uri: Uri) {
        _ui.value = BackupUi.Working
        viewModelScope.launch {
            try {
                val preview = backup.preview(read(uri))
                if (preview.hasSecrets) _ui.value = BackupUi.NeedsPassword(uri) else restore(uri, null)
            } catch (e: Exception) {
                _ui.value = BackupUi.Failed(messageFor(e))
            }
        }
    }

    /** Step 2. A null password restores the settings only. */
    fun restore(uri: Uri, password: CharArray?) {
        _ui.value = BackupUi.Working
        viewModelScope.launch {
            _ui.value = try {
                val r = withContext(Dispatchers.Default) { backup.restore(read(uri), password) }
                scheduler.ensureRunning() // download rules may have changed
                val parts = mutableListOf(context.resources.getQuantityString(
                    R.plurals.restore_settings_n, r.settingsApplied, r.settingsApplied
                ))
                if (r.services.isNotEmpty()) {
                    parts += context.getString(R.string.restore_signins, r.services.joinToString { auth.info(it).displayName })
                }
                if (r.followsAdded > 0) {
                    parts += context.resources.getQuantityString(R.plurals.restore_follows_n, r.followsAdded, r.followsAdded)
                }
                com.abhinavxt.debforge.download.follow.FollowJobService.sync(context, r.anyFollows)
                if (r.skippedFolder) parts += context.getString(R.string.restore_folder_skipped)
                BackupUi.Done(parts.joinToString("\n"))
            } catch (e: BackupCrypto.WrongPasswordException) {
                BackupUi.NeedsPassword(uri, wrongPassword = true)
            } catch (e: Exception) {
                BackupUi.Failed(messageFor(e))
            } finally {
                password?.fill('\u0000')
            }
        }
    }

    private suspend fun read(uri: Uri): String = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)?.use { input ->
            // A settings backup is tiny; refuse anything big rather than load it.
            val bytes = input.readNBytesCompat(MAX_BYTES + 1)
            if (bytes.size > MAX_BYTES) throw BackupManager.BackupFormatException()
            String(bytes)
        } ?: throw IOException("can't open the file")
    }

    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8 * 1024)
        var total = 0
        while (total < limit) {
            val n = read(buf, 0, minOf(buf.size, limit - total))
            if (n < 0) break
            out.write(buf, 0, n)
            total += n
        }
        return out.toByteArray()
    }

    private fun messageFor(e: Exception): String = when {
        e is BackupManager.BackupFormatException && e.newer -> context.getString(R.string.restore_newer)
        e is BackupManager.BackupFormatException -> context.getString(R.string.restore_not_backup)
        else -> context.getString(R.string.restore_failed, e.message.orEmpty())
    }

    private companion object {
        const val MAX_BYTES = 256 * 1024
    }
}

@Composable
fun BackupSection(viewModel: BackupViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    var showOptions by remember { mutableStateOf(false) }

    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        viewModel.onSaveLocation(uri)
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.startRestore(uri)
    }

    SectionCard(title = stringResource(R.string.backup_title)) {
        SettingRow(
            icon = Icons.Rounded.SettingsBackupRestore,
            title = stringResource(R.string.backup_title),
            subtitle = stringResource(R.string.backup_detail)
        )
        Row(
            Modifier.padding(start = 68.dp, end = 16.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilledTonalButton(onClick = { showOptions = true }, enabled = ui !is BackupUi.Working) {
                Icon(Icons.Rounded.Upload, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.backup_create))
            }
            OutlinedButton(
                onClick = { open.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                enabled = ui !is BackupUi.Working
            ) {
                Icon(Icons.Rounded.Restore, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.backup_restore))
            }
        }
    }

    if (showOptions) {
        BackupOptionsDialog(
            onDismiss = { showOptions = false },
            onConfirm = { password ->
                showOptions = false
                viewModel.setPendingPassword(password)
                create.launch("debforge-backup-${LocalDate.now()}.json")
            }
        )
    }

    when (val s = ui) {
        is BackupUi.NeedsPassword -> RestorePasswordDialog(
            wrong = s.wrongPassword,
            onRestore = { viewModel.restore(s.uri, it) },
            onSettingsOnly = { viewModel.restore(s.uri, null) },
            onDismiss = viewModel::dismiss
        )
        is BackupUi.Done -> AlertDialog(
            onDismissRequest = viewModel::dismiss,
            title = { Text(stringResource(R.string.backup_title)) },
            text = { Text(s.message) },
            confirmButton = { TextButton(onClick = viewModel::dismiss) { Text(stringResource(R.string.action_ok)) } }
        )
        is BackupUi.Failed -> AlertDialog(
            onDismissRequest = viewModel::dismiss,
            title = { Text(stringResource(R.string.backup_title)) },
            text = { Text(s.message, color = MaterialTheme.colorScheme.error) },
            confirmButton = { TextButton(onClick = viewModel::dismiss) { Text(stringResource(R.string.action_ok)) } }
        )
        else -> Unit
    }
}

/** Include sign-ins? If so, a password (twice) that encrypts them. */
@Composable
private fun BackupOptionsDialog(onDismiss: () -> Unit, onConfirm: (CharArray?) -> Unit) {
    var includeSignIns by remember { mutableStateOf(false) }
    var pw by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    val tooShort = includeSignIns && pw.length < MIN_PASSWORD
    val mismatch = includeSignIns && pw2.isNotEmpty() && pw != pw2
    val ok = !includeSignIns || (!tooShort && pw == pw2)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_create)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.backup_what), style = MaterialTheme.typography.bodyMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.backup_include_signins), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            stringResource(R.string.backup_include_signins_detail),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = includeSignIns, onCheckedChange = { includeSignIns = it })
                }
                if (includeSignIns) {
                    OutlinedTextField(
                        value = pw, onValueChange = { pw = it },
                        label = { Text(stringResource(R.string.backup_password)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        supportingText = { if (tooShort) Text(stringResource(R.string.backup_password_short, MIN_PASSWORD)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = pw2, onValueChange = { pw2 = it },
                        label = { Text(stringResource(R.string.backup_password_again)) },
                        singleLine = true,
                        isError = mismatch,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        supportingText = { if (mismatch) Text(stringResource(R.string.backup_password_mismatch)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        stringResource(R.string.backup_password_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(if (includeSignIns) pw.toCharArray() else null) }, enabled = ok) {
                Text(stringResource(R.string.backup_choose_location))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    )
}

@Composable
private fun RestorePasswordDialog(
    wrong: Boolean,
    onRestore: (CharArray) -> Unit,
    onSettingsOnly: () -> Unit,
    onDismiss: () -> Unit
) {
    var pw by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_restore)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.restore_needs_password), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = pw, onValueChange = { pw = it },
                    label = { Text(stringResource(R.string.backup_password)) },
                    singleLine = true,
                    isError = wrong,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    supportingText = { if (wrong) Text(stringResource(R.string.restore_wrong_password)) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onRestore(pw.toCharArray()) }, enabled = pw.isNotEmpty()) {
                Text(stringResource(R.string.backup_restore))
            }
        },
        dismissButton = {
            TextButton(onClick = onSettingsOnly) { Text(stringResource(R.string.restore_settings_only)) }
        }
    )
}

private const val MIN_PASSWORD = 8
