package com.abhinavxt.debforge.ui.settings

import android.os.Environment
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.abhinavxt.debforge.domain.SortOrder
import com.abhinavxt.debforge.domain.ThemeMode
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val dir by viewModel.downloadDir.collectAsStateWithLifecycle()
    val sort by viewModel.sortDefault.collectAsStateWithLifecycle()
    val theme by viewModel.themeMode.collectAsStateWithLifecycle()

    var showFolderDialog by remember { mutableStateOf(false) }
    var showSignOutDialog by remember { mutableStateOf(false) }

    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }) }) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            item { SectionHeader("Download folder") }
            item {
                FolderRow(
                    path = dir,
                    onChange = { showFolderDialog = true }
                )
            }
            item { HorizontalDivider() }

            item { SectionHeader("Default sort") }
            items(SortOrder.entries) { option ->
                ChoiceRow(
                    label = option.label(),
                    selected = option == sort,
                    onClick = { viewModel.setSortDefault(option) }
                )
            }
            item { HorizontalDivider() }

            item { SectionHeader("Theme") }
            items(ThemeMode.entries) { option ->
                ChoiceRow(
                    label = option.label(),
                    selected = option == theme,
                    onClick = { viewModel.setThemeMode(option) }
                )
            }
            item { HorizontalDivider() }

            item { SectionHeader("Account") }
            item {
                Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    OutlinedButton(onClick = { showSignOutDialog = true }) {
                        Text("Sign out")
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (showFolderDialog) {
        FolderPickerDialog(
            current = dir,
            onPick = { picked ->
                viewModel.setDownloadDir(picked)
                showFolderDialog = false
            },
            onDismiss = { showFolderDialog = false }
        )
    }

    if (showSignOutDialog) {
        AlertDialog(
            onDismissRequest = { showSignOutDialog = false },
            title = { Text("Sign out?") },
            text = { Text("Your token will be removed from this device. You can paste it again any time.") },
            confirmButton = {
                TextButton(onClick = {
                    showSignOutDialog = false
                    viewModel.signOut()
                }) { Text("Sign out") }
            },
            dismissButton = {
                TextButton(onClick = { showSignOutDialog = false }) { Text("Cancel") }
            }
        )
    }
}

// --- rows --------------------------------------------------------------------

@Composable
private fun SectionHeader(title: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun FolderRow(path: String, onChange: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = path,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.width(8.dp))
        OutlinedButton(onClick = onChange) { Text("Change") }
    }
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

// --- folder picker dialog ----------------------------------------------------

@Composable
private fun FolderPickerDialog(
    current: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val presets = remember { buildPresetFolders() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose folder") },
        text = {
            Column {
                Text(
                    "Files will be saved as plain files under the chosen folder.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                presets.forEach { preset ->
                    ChoiceRow(
                        label = preset,
                        selected = preset == current,
                        onClick = { onPick(preset) }
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
}

/** A few common destinations under shared storage. */
private fun buildPresetFolders(): List<String> {
    fun publicDir(name: String) =
        File(Environment.getExternalStoragePublicDirectory(name), "DebForge").absolutePath
    return listOf(
        publicDir(Environment.DIRECTORY_MOVIES),
        publicDir(Environment.DIRECTORY_DOWNLOADS),
        publicDir(Environment.DIRECTORY_PICTURES)
    )
}

// --- labels ------------------------------------------------------------------

private fun SortOrder.label(): String = when (this) {
    SortOrder.DATE_DESC -> "Newest first"
    SortOrder.DATE_ASC -> "Oldest first"
    SortOrder.NAME_ASC -> "Name A-Z"
    SortOrder.NAME_DESC -> "Name Z-A"
    SortOrder.SIZE_DESC -> "Largest first"
    SortOrder.SIZE_ASC -> "Smallest first"
}

private fun ThemeMode.label(): String = when (this) {
    ThemeMode.SYSTEM -> "Follow system"
    ThemeMode.LIGHT -> "Light"
    ThemeMode.DARK -> "Dark"
}
