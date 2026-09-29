package com.abhinavxt.debforge.ui.active

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ClearAll
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.abhinavxt.debforge.R
import com.abhinavxt.debforge.domain.DownloadState
import com.abhinavxt.debforge.download.LocalFiles
import com.abhinavxt.debforge.ui.components.BannerCard
import com.abhinavxt.debforge.ui.components.IconBadge
import com.abhinavxt.debforge.ui.components.MessageState
import com.abhinavxt.debforge.ui.components.ScreenHeader

@Composable
fun ActiveDownloadsScreen(viewModel: ActiveDownloadsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scheduledStart by viewModel.scheduledStartHour.collectAsStateWithLifecycle()
    val grouped = state.grouped
    // Combined speed of everything running, shown under the title.
    val running = grouped[DownloadState.DOWNLOADING].orEmpty()
    val totalSpeed = running.filterNot { it.waitingForNetwork }.sumOf { it.bytesPerSecond.coerceAtLeast(0) }

    // Finished videos open in DebForge's player (offline, from the file).
    val context = LocalContext.current
    androidx.compose.runtime.LaunchedEffect(Unit) {
        viewModel.playRequests.collect { req ->
            context.startActivity(com.abhinavxt.debforge.player.PlayerActivity.intent(context, req))
        }
    }

    Scaffold(
        topBar = {
            ScreenHeader(
                title = stringResource(R.string.tab_downloads),
                subtitle = if (totalSpeed > 0) "↓ ${formatSpeed(totalSpeed)}" else null
            ) {
                if (state.rows.any { it.entity.state == DownloadState.COMPLETED }) {
                    TextButton(onClick = viewModel::clearCompleted) {
                        Icon(Icons.Rounded.ClearAll, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.dl_clear_finished))
                    }
                }
            }
        }
    ) { padding ->
        if (state.isEmpty) {
            MessageState(
                icon = Icons.Rounded.Download,
                title = stringResource(R.string.dl_empty_title),
                body = stringResource(R.string.dl_empty_body),
                modifier = Modifier.padding(padding)
            )
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp)
        ) {
            val start = scheduledStart
            if (start != null && grouped[DownloadState.QUEUED].orEmpty().isNotEmpty()) {
                item(key = "schedule-notice") { ScheduleNotice(start) }
            }
            // Display order: action-needed states first.
            SECTION_ORDER.forEach { sectionState ->
                val rows = grouped[sectionState].orEmpty()
                if (rows.isEmpty()) return@forEach
                item(key = "header-$sectionState") { SectionHeader(sectionState.title(), rows.size) }
                items(rows, key = { it.id }) { row ->
                    when (sectionState) {
                        DownloadState.DOWNLOADING -> DownloadingRow(row, viewModel)
                        DownloadState.QUEUED -> QueuedRow(row, viewModel)
                        DownloadState.PAUSED -> PausedRow(row, viewModel)
                        DownloadState.FAILED -> FailedRow(row, viewModel)
                        DownloadState.COMPLETED -> CompletedRow(row, viewModel)
                    }
                }
            }
        }
    }
}

private val SECTION_ORDER = listOf(
    DownloadState.DOWNLOADING,
    DownloadState.QUEUED,
    DownloadState.PAUSED,
    DownloadState.FAILED,
    DownloadState.COMPLETED
)

@Composable
private fun DownloadState.title(): String = stringResource(
    when (this) {
        DownloadState.DOWNLOADING -> R.string.dl_section_downloading
        DownloadState.QUEUED -> R.string.dl_section_queued
        DownloadState.PAUSED -> R.string.dl_section_paused
        DownloadState.FAILED -> R.string.dl_section_failed
        DownloadState.COMPLETED -> R.string.dl_section_completed
    }
)

/** "Downloading  (2)" */
@Composable
private fun SectionHeader(title: String, count: Int) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, top = 16.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(8.dp))
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            shape = CircleShape
        ) {
            Text(
                count.toString(),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }
    }
}

// --- row variants ------------------------------------------------------------

@Composable
private fun DownloadingRow(row: ActiveRow, vm: ActiveDownloadsViewModel) {
    val waitingLabel = row.waitingReasonRes?.let { stringResource(it) } ?: stringResource(R.string.dl_waiting)
    val soFar = stringResource(R.string.dl_so_far)
    val eta = row.etaSeconds?.let { etaText(it) }
    // Archives built on the fly have no size until they finish.
    val sizePart = if (row.entity.filesize > 0) {
        "${formatBytes(row.bytesNow)} / ${formatBytes(row.entity.filesize)}"
    } else {
        "${formatBytes(row.bytesNow)} $soFar"
    }
    val subtitle = if (row.waitingForNetwork) {
        "$waitingLabel · $sizePart"
    } else {
        listOfNotNull(sizePart, formatSpeed(row.bytesPerSecond), eta).joinToString(" · ")
    }
    val cs = MaterialTheme.colorScheme
    RowFrame(
        filename = row.entity.filename,
        subtitle = subtitle,
        icon = if (row.waitingForNetwork) Icons.Rounded.Schedule else Icons.Rounded.ArrowDownward,
        iconContainer = cs.primaryContainer,
        iconTint = cs.onPrimaryContainer,
        // Frozen at last-known fraction while waiting — gives the user a sense
        // of how far along the download already is, even without progress.
        // Unknown size -> indeterminate bar rather than a stuck 0%.
        progress = if (row.entity.filesize > 0) row.fraction else -1f,
        actions = {
            FilledTonalIconButton(onClick = { vm.pause(row.id) }) {
                Icon(Icons.Rounded.Pause, contentDescription = stringResource(R.string.action_pause))
            }
            IconButton(onClick = { vm.cancel(row.id) }) {
                Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.action_cancel))
            }
        }
    )
}

/** "Outside your download hours. Queued files start at 01:00." */
@Composable
private fun ScheduleNotice(startHour: Int) {
    BannerCard(
        icon = Icons.Rounded.Schedule,
        text = stringResource(R.string.dl_schedule_notice, "%02d:00".format(startHour)),
        container = MaterialTheme.colorScheme.secondaryContainer,
        onContainer = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.padding(bottom = 4.dp)
    ) { Spacer(Modifier.width(12.dp)) }
}

@Composable
private fun QueuedRow(row: ActiveRow, vm: ActiveDownloadsViewModel) {
    val cs = MaterialTheme.colorScheme
    RowFrame(
        filename = row.entity.filename,
        subtitle = stringResource(R.string.dl_waiting_size, formatBytes(row.entity.filesize)),
        icon = Icons.Rounded.Schedule,
        iconContainer = cs.secondaryContainer,
        iconTint = cs.onSecondaryContainer,
        progress = null,
        actions = {
            IconButton(onClick = { vm.cancel(row.id) }) {
                Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.action_cancel))
            }
        }
    )
}

@Composable
private fun PausedRow(row: ActiveRow, vm: ActiveDownloadsViewModel) {
    val cs = MaterialTheme.colorScheme
    RowFrame(
        filename = row.entity.filename,
        subtitle = stringResource(R.string.dl_paused_progress, formatBytes(row.bytesNow), formatBytes(row.entity.filesize)),
        icon = Icons.Rounded.Pause,
        iconContainer = cs.surfaceContainerHighest,
        iconTint = cs.onSurfaceVariant,
        progress = row.fraction,
        actions = {
            FilledTonalIconButton(onClick = { vm.resume(row.id) }) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = stringResource(R.string.action_resume))
            }
            IconButton(onClick = { vm.cancel(row.id) }) {
                Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.action_cancel))
            }
        }
    )
}

@Composable
private fun FailedRow(row: ActiveRow, vm: ActiveDownloadsViewModel) {
    val cs = MaterialTheme.colorScheme
    RowFrame(
        filename = row.entity.filename,
        subtitle = row.entity.errorMessage ?: stringResource(R.string.dl_failed),
        subtitleColor = cs.error,
        icon = Icons.Rounded.ErrorOutline,
        iconContainer = cs.errorContainer,
        iconTint = cs.onErrorContainer,
        progress = null,
        actions = {
            FilledTonalIconButton(onClick = { vm.retry(row.id) }) {
                Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.action_retry))
            }
            IconButton(onClick = { vm.cancel(row.id) }) {
                Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.action_dismiss))
            }
        }
    )
}

@Composable
private fun CompletedRow(row: ActiveRow, vm: ActiveDownloadsViewModel) {
    val context = LocalContext.current
    val path = row.entity.finalFilePath
    // Existence check touches storage (a document query for picked folders),
    // so it runs off the main thread; until it answers, assume the file is there.
    val exists by androidx.compose.runtime.produceState(initialValue = path != null, path) {
        value = path != null && kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { LocalFiles.exists(path) }
    }
    val cs = MaterialTheme.colorScheme
    val present = exists && path != null
    RowFrame(
        filename = row.entity.filename,
        subtitle = if (present && path != null) {
            stringResource(R.string.dl_done_at, formatBytes(row.entity.filesize), LocalFiles.folderLabel(context, path))
        } else stringResource(R.string.dl_file_missing),
        icon = if (present) Icons.Rounded.CheckCircle else Icons.Rounded.Description,
        iconContainer = if (present) cs.tertiaryContainer else cs.surfaceContainerHighest,
        iconTint = if (present) cs.onTertiaryContainer else cs.onSurfaceVariant,
        progress = null,
        actions = {
            var menu by remember { mutableStateOf(false) }
            if (present && path != null) {
                val isVideo = LocalFiles.mimeType(LocalFiles.name(path)).startsWith("video/")
                FilledTonalIconButton(onClick = {
                    if (isVideo && vm.useInternalPlayer.value) {
                        vm.play(row.entity)
                    } else {
                        runCatching { LocalFiles.openIntent(context, path)?.let(context::startActivity) }
                    }
                }) {
                    Icon(
                        if (isVideo) Icons.Rounded.PlayArrow else Icons.AutoMirrored.Rounded.OpenInNew,
                        contentDescription = stringResource(if (isVideo) R.string.action_play else R.string.action_open)
                    )
                }
            }
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.dl_more_actions))
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (present && path != null) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_share)) },
                            leadingIcon = { Icon(Icons.Rounded.Share, contentDescription = null) },
                            onClick = {
                                menu = false
                                runCatching { LocalFiles.shareIntent(context, path)?.let(context::startActivity) }
                            }
                        )
                    }
                    // Removes the entry only; the file stays where it is.
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_remove)) },
                        leadingIcon = { Icon(Icons.Rounded.DeleteOutline, contentDescription = null) },
                        onClick = { menu = false; vm.cancel(row.id) }
                    )
                }
            }
        }
    )
}

// --- shared row frame --------------------------------------------------------

/** One download as a rounded card: state icon, name, details, actions, progress. */
@Composable
private fun RowFrame(
    filename: String,
    subtitle: String,
    icon: ImageVector,
    iconContainer: Color,
    iconTint: Color,
    subtitleColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    progress: Float?,
    actions: @Composable () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Column(Modifier.padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(icon, container = iconContainer, tint = iconTint, size = 40.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = filename,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = subtitleColor,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Row(horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) { actions() }
            }
            if (progress != null) {
                Spacer(Modifier.height(10.dp))
                val bar = Modifier.fillMaxWidth().padding(end = 8.dp).height(6.dp).clip(CircleShape)
                if (progress < 0f) {
                    LinearProgressIndicator(modifier = bar)
                } else {
                    LinearProgressIndicator(progress = { progress }, modifier = bar)
                }
            }
        }
    }
}

// --- formatters --------------------------------------------------------------

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024L * 1024 -> "${bytes / 1024} KB"
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    else -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
}

private fun formatSpeed(bps: Long): String = when {
    bps <= 0 -> "—"
    bps < 1024L * 1024 -> "${bps / 1024} KB/s"
    else -> "%.1f MB/s".format(bps / (1024.0 * 1024.0))
}

@Composable
private fun etaText(seconds: Long): String = when {
    // Same translated strings as the processing strip ("5m left", "noch 5 min").
    seconds < 60 -> stringResource(R.string.eta_seconds, seconds.toInt())
    seconds < 3600 -> stringResource(R.string.eta_minutes, (seconds / 60).toInt())
    else -> stringResource(R.string.eta_hours, (seconds / 3600).toInt(), ((seconds % 3600) / 60).toInt())
}
