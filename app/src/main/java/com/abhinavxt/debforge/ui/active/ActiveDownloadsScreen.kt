package com.abhinavxt.debforge.ui.active

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActiveDownloadsScreen(viewModel: ActiveDownloadsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(topBar = { TopAppBar(title = { Text("Active") }) }) { padding ->
        if (state.isEmpty) {
            EmptyState(modifier = Modifier.padding(padding).fillMaxSize())
            return@Scaffold
        }

        val grouped = state.grouped
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
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

private fun DownloadState.title(): String = when (this) {
    DownloadState.DOWNLOADING -> "Downloading"
    DownloadState.QUEUED -> "Queued"
    DownloadState.PAUSED -> "Paused"
    DownloadState.FAILED -> "Failed"
    DownloadState.COMPLETED -> "Completed"
}

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
    val subtitle = if (row.waitingForNetwork) {
        "Waiting for network · ${formatBytes(row.bytesNow)} / ${formatBytes(row.entity.filesize)}"
    } else {
        buildString {
            append(formatBytes(row.bytesNow))
            append(" / ")
            append(formatBytes(row.entity.filesize))
            append(" · ")
            append(formatSpeed(row.bytesPerSecond))
            row.etaSeconds?.let { append(" · ").append(formatEta(it)).append(" left") }
        }
    }
    RowFrame(
        filename = row.entity.filename,
        subtitle = subtitle,
        // Frozen at last-known fraction while waiting — gives the user a sense
        // of how far along the download already is, even without progress.
        progress = row.fraction,
        actions = {
            TextButton(onClick = { vm.pause(row.id) }) { Text("Pause") }
            TextButton(onClick = { vm.cancel(row.id) }) { Text("Cancel") }
        }
    )
}

@Composable
private fun QueuedRow(row: ActiveRow, vm: ActiveDownloadsViewModel) {
    RowFrame(
        filename = row.entity.filename,
        subtitle = "Waiting · ${formatBytes(row.entity.filesize)}",
        progress = null,
        actions = {
            TextButton(onClick = { vm.cancel(row.id) }) { Text("Cancel") }
        }
    )
}

@Composable
private fun PausedRow(row: ActiveRow, vm: ActiveDownloadsViewModel) {
    RowFrame(
        filename = row.entity.filename,
        subtitle = "Paused · ${formatBytes(row.bytesNow)} / ${formatBytes(row.entity.filesize)}",
        progress = row.fraction,
        actions = {
            TextButton(onClick = { vm.resume(row.id) }) { Text("Resume") }
            TextButton(onClick = { vm.cancel(row.id) }) { Text("Cancel") }
        }
    )
}

@Composable
private fun FailedRow(row: ActiveRow, vm: ActiveDownloadsViewModel) {
    RowFrame(
        filename = row.entity.filename,
        subtitle = row.entity.errorMessage ?: "Download failed",
        subtitleColor = MaterialTheme.colorScheme.error,
        progress = null,
        actions = {
            TextButton(onClick = { vm.retry(row.id) }) { Text("Retry") }
            TextButton(onClick = { vm.cancel(row.id) }) { Text("Dismiss") }
        }
    )
}

@Composable
private fun CompletedRow(row: ActiveRow, vm: ActiveDownloadsViewModel) {
    RowFrame(
        filename = row.entity.filename,
        subtitle = "Done · ${row.entity.finalFilePath.orEmpty()}",
        progress = null,
        actions = {
            TextButton(onClick = { vm.cancel(row.id) }) { Text("Dismiss") }
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
        if (progress != null) {
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
            Text("Nothing active", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Tap Download on an item in Browse to queue it.",
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
