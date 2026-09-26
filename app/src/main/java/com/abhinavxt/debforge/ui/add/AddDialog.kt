package com.abhinavxt.debforge.ui.add

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * "Add to <service>" dialog: paste magnets/links and/or pick a .torrent file.
 * Rendered by HomeScreen whenever [AddViewModel] says it's visible.
 */
@Composable
fun AddDialog(viewModel: AddViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    if (!state.visible) return

    val pickTorrent = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> viewModel.onTorrentPicked(uri) }

    AlertDialog(
        onDismissRequest = viewModel::dismiss,
        title = { Text("Add to ${state.providerName}") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (state.acceptsText) {
                    OutlinedTextField(
                        value = state.text,
                        onValueChange = viewModel::onTextChange,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp, max = 220.dp),
                        label = { Text(state.textHint) },
                        enabled = !state.submitting
                    )
                }

                if (state.acceptsTorrentFile) {
                    val name = state.torrentName
                    if (name == null) {
                        OutlinedButton(
                            onClick = {
                                // Many file managers report .torrent as
                                // octet-stream, so accept both.
                                pickTorrent.launch(
                                    arrayOf("application/x-bittorrent", "application/octet-stream")
                                )
                            },
                            enabled = !state.submitting
                        ) { Text("Choose .torrent file") }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = name,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            IconButton(onClick = viewModel::clearTorrent, enabled = !state.submitting) {
                                Icon(Icons.Default.Clear, contentDescription = "Remove file")
                            }
                        }
                    }
                }

                state.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = viewModel::submit, enabled = state.canSubmit) {
                if (state.submitting) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text("Add")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = viewModel::dismiss, enabled = !state.submitting) { Text("Cancel") }
        }
    )
}
