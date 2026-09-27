package com.abhinavxt.debforge.ui.active

import androidx.compose.ui.res.stringResource
import com.abhinavxt.debforge.R
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.abhinavxt.debforge.domain.DownloadState
import com.abhinavxt.debforge.download.LocalFiles
import androidx.compose.ui.platform.LocalContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActiveDownloadsScreen(viewModel: ActiveDownloadsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scheduledStart by viewModel.scheduledStartHour.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tab_downloads)) },
                actions = {
                    if (state.rows.any { it.entity.state == DownloadState.COMPLETED }) {
                        TextButton(onClick = viewModel::clearCompleted) { Text(stringResource(R.string.dl_clear_finished)) }
                    }
                }
            )
        }
    ) { padding ->
        if (state.isEmpty) {
            EmptyState(modifier = Modifier.padding(padding).fillMaxSize())
            return@Scaffold
        }

        val grouped = state.grouped
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            val start = scheduledStart
            if (start != null && grouped[DownloadState.QUEUED].orEmpty().isNotEmpty()) {
                item(key = "schedule-notice") { ScheduleNotice(start) }
            }
            // Display order: action-needed states first.
            SECTION_ORDER.forEach { sectionState ->
                val rows = grouped[sectionState].orEmpty()
                if (rows.isEmpty()) return@forEach
                item(key = "header-$sectionState") { SectionHeader(sectionState.title()) }
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

// --- row variants ------------------------------------------------------------

@Composable
private fun DownloadingRow(row: ActiveRow, vm: ActiveDownloadsViewModel) {
    val waitingLabel = row.waitingReasonRes?.let { stringResource(it) } ?: stringResource(R.string.dl_waiting)
    val soFar = stringResource(R.string.dl_so_far)
    val eta = row.etaSeconds?.let { stringResource(R.string.dl_time_left, formatEta(it)) }
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
    RowFrame(
        filename = row.entity.filename,
        subtitle = subtitle,
        // Frozen at last-known fraction while waiting — gives the user a sense
        // of how far along the download already is, even without progress.
        // Unknown size -> indeterminate bar rather than a stuck 0%.
        progress = if (row.entity.filesize > 0) row.fraction else -1f,
        actions = {
            TextButton(onClick = { vm.pause(row.id) }) { Text(stringResource(R.string.action_pause)) }
            TextButton(onClick = { vm.cancel(row.id) }) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

/** "Outside your download hours. Queued files start at 01:00." */
@Composable
private fun ScheduleNotice(startHour: Int) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            stringResource(R.string.dl_schedule_notice, "%02d:00".format(startHour)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
        )
    }
}

@Composable
private fun QueuedRow(row: ActiveRow, vm: ActiveDownloadsViewModel) {
    RowFrame(
        filename = row.entity.filename,
        subtitle = stringResource(R.string.dl_waiting_size, formatBytes(row.entity.filesize)),
        progress = null,
        actions = {
            TextButton(onClick = { vm.cancel(row.id) }) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

@Composable
private fun PausedRow(row: ActiveRow, vm: ActiveDownloadsViewModel) {
    RowFrame(
        filename = row.entity.filename,
        subtitle = stringResource(R.string.dl_paused_progress, formatBytes(row.bytesNow), formatBytes(row.entity.filesize)),
        progress = row.fraction,
        actions = {
            TextButton(onClick = { vm.resume(row.id) }) { Text(stringResource(R.string.action_resume)) }
            TextButton(onClick = { vm.cancel(row.id) }) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

@Composable
private fun FailedRow(row: ActiveRow, vm: ActiveDownloadsViewModel) {
    RowFrame(
        filename = row.entity.filename,
        subtitle = row.entity.errorMessage ?: stringResource(R.string.dl_failed),
        subtitleColor = MaterialTheme.colorScheme.error,
        progress = null,
        actions = {
            TextButton(onClick = { vm.retry(row.id) }) { Text(stringResource(R.string.action_retry)) }
            TextButton(onClick = { vm.cancel(row.id) }) { Text(stringResource(R.string.action_dismiss)) }
        }
    )
}

@Composable
private fun CompletedRow(row: ActiveRow, vm: ActiveDownloadsViewModel) {
    val context = LocalContext.current
    val file = row.entity.finalFilePath?.let(::File)
    val exists = file?.exists() == true
    RowFrame(
        filename = row.entity.filename,
        subtitle = if (exists) stringResource(R.string.dl_done_at, formatBytes(row.entity.filesize), file!!.parent.orEmpty())
        else stringResource(R.string.dl_file_missing),
        progress = null,
        actions = {
            if (exists) {
                val isVideo = LocalFiles.mimeType(file!!).startsWith("video/")
                TextButton(onClick = {
                    runCatching { context.startActivity(LocalFiles.openIntent(context, file)) }
                }) { Text(stringResource(if (isVideo) R.string.action_play else R.string.action_open)) }
                TextButton(onClick = {
                    runCatching { context.startActivity(LocalFiles.shareIntent(context, file)) }
                }) { Text(stringResource(R.string.action_share)) }
            }
            // Removes the entry only; the file stays where it is.
            TextButton(onClick = { vm.cancel(row.id) }) { Text(stringResource(R.string.action_remove)) }
        }
    )
}

// --- shared row frame --------------------------------------------------------

@Composable
private fun RowFrame(
    filename: String,
    subtitle: String,
    subtitleColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant,
    progress: Float?,
    actions: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Text(
            text = filename,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(4.dp))
        if (progress != null && progress < 0f) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(4.dp))
        } else if (progress != null) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(4.dp))
        }
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = subtitleColor,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) { actions() }
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.dl_empty_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.dl_empty_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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

private fun formatEta(seconds: Long): String = when {
    seconds < 60 -> "${seconds}s"
    seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
    else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
}
