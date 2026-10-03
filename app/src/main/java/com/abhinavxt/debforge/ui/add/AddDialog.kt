package com.abhinavxt.debforge.ui.add

import com.abhinavxt.debforge.domain.ProviderId
import androidx.compose.ui.res.stringResource
import com.abhinavxt.debforge.R
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.rounded.AddLink
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.HourglassEmpty
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.draw.clip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
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
    val pickFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> if (uri != null) viewModel.useFolder(uri) }

    AlertDialog(
        onDismissRequest = viewModel::dismiss,
        icon = { Icon(Icons.Rounded.AddLink, contentDescription = null) },
        title = {
            Text(if (state.isDirect) stringResource(R.string.direct_title) else stringResource(R.string.add_title, state.providerName))
        },
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
                        label = { Text(stringResource(state.textHintRes)) },
                        shape = MaterialTheme.shapes.medium,
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
                            enabled = !state.submitting,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Rounded.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.add_choose_torrent))
                        }
                    } else {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.medium)
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                .padding(start = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Rounded.Description,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = name,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            IconButton(onClick = viewModel::clearTorrent, enabled = !state.submitting) {
                                Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.add_remove_file))
                            }
                        }
                    }
                }

                if (state.targets.isNotEmpty()) {
                    TargetPicker(state, onPick = viewModel::selectTarget)
                }

                state.cache?.let { CacheLine(it, state.providerName) }

                // Auto-download needs a download folder; without one, offer
                // the picker instead of queueing downloads that would fail.
                val hasStorage = state.canWriteStorage
                // Direct links always download now; the choice is for services.
                if (!state.isDirect) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = state.autoDownload && hasStorage,
                            onCheckedChange = viewModel::onAutoDownloadChange,
                            enabled = !state.submitting && hasStorage
                        )
                        Text(
                            stringResource(R.string.add_auto_download),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
                if (!hasStorage) {
                    Text(
                        stringResource(R.string.add_needs_storage),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(onClick = { pickFolder.launch(null) }) {
                        Text(stringResource(R.string.storage_pick_folder))
                    }
                }

                state.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(onClick = viewModel::submit, enabled = state.canSubmit) {
                if (state.submitting) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
                } else {
                    Text(stringResource(R.string.action_add))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = viewModel::dismiss, enabled = !state.submitting) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

/**
 * "Send to": one chip per signed-in service. A bolt marks services that
 * already have everything cached; services that can't take the input
 * (e.g. magnets on Real-Debrid) are disabled. When DebForge picked a service
 * other than the active one, a line says why.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TargetPicker(state: AddUiState, onPick: (ProviderId) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(R.string.add_send_to),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            state.targets.forEach { t ->
                val selected = t.id == state.target
                FilterChip(
                    selected = selected,
                    onClick = { onPick(t.id) },
                    enabled = t.supportsInput && !state.submitting,
                    label = { Text(t.name) },
                    leadingIcon = when {
                        t.cache?.allCached == true -> {
                            { Icon(Icons.Rounded.Bolt, contentDescription = stringResource(R.string.add_target_cached_cd), modifier = Modifier.size(18.dp)) }
                        }
                        selected -> {
                            { Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                        }
                        else -> null
                    }
                )
            }
        }
        if (state.directSuggested) {
            Text(
                stringResource(R.string.direct_suggested_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else if (state.targetIsSuggestion) {
            Text(
                if (state.activeCanTake) stringResource(R.string.add_target_cached_hint, state.providerName)
                else stringResource(R.string.add_target_cant_hint, state.activeName, state.providerName),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** "Cached on TorBox. Ready instantly." / "Not cached. ..." / "2 of 3 cached." — with a bolt when cached. */
@Composable
private fun CacheLine(cache: CacheSummary, service: String) {
    val text = when {
        cache.checking -> stringResource(R.string.add_cache_checking, service)
        cache.total == 1 && cache.cached == 1 -> stringResource(R.string.add_cache_one_yes, service)
        cache.total == 1 -> stringResource(R.string.add_cache_one_no, service)
        cache.allCached -> stringResource(R.string.add_cache_all_yes, cache.total)
        else -> stringResource(R.string.add_cache_some, cache.cached, cache.total)
    }
    val cs = MaterialTheme.colorScheme
    val hit = cache.cached > 0 && !cache.checking
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(if (hit) cs.primaryContainer else cs.surfaceContainerHighest)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (cache.checking) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        } else {
            Icon(
                if (hit) Icons.Rounded.Bolt else Icons.Rounded.HourglassEmpty,
                contentDescription = null,
                tint = if (hit) cs.onPrimaryContainer else cs.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (hit) cs.onPrimaryContainer else cs.onSurfaceVariant
        )
    }
}
