package com.abhinavxt.debforge.ui.active

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
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
import androidx.compose.animation.core.animateFloatAsState
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.ui.platform.LocalView
import com.abhinavxt.debforge.ui.components.MiddleEllipsisText
import com.abhinavxt.debforge.ui.components.ReleaseLabels
import com.abhinavxt.debforge.ui.media.releaseLabelsFor
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
import android.widget.Toast
import androidx.compose.material3.Button
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.abhinavxt.debforge.R
import com.abhinavxt.debforge.domain.DownloadState
import com.abhinavxt.debforge.download.LocalFiles
import com.abhinavxt.debforge.ui.components.BannerCard
import com.abhinavxt.debforge.ui.components.HeaderNavActions
import com.abhinavxt.debforge.ui.components.IconBadge
import com.abhinavxt.debforge.ui.components.MessageState
import com.abhinavxt.debforge.ui.components.ScreenHeader

@Composable
fun ActiveDownloadsScreen(onOpenMedia: () -> Unit, onOpenSettings: () -> Unit, viewModel: ActiveDownloadsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scheduledStart by viewModel.scheduledStartHour.collectAsStateWithLifecycle()
    val grouped = state.grouped
    // Showing the list is a chance to restart a queue that stalled.
    LaunchedEffect(Unit) { viewModel.kick() }
    // Combined speed of everything running, shown under the title.
    val running = grouped[DownloadState.DOWNLOADING].orEmpty()
    val totalSpeed = running.filterNot { it.waitingForNetwork || it.finishing }.sumOf { it.bytesPerSecond.coerceAtLeast(0) }

    Scaffold(
        topBar = {
            ScreenHeader(
                title = stringResource(R.string.tab_downloads),
                subtitle = if (totalSpeed > 0) "↓ ${formatSpeed(totalSpeed)}" else null
            ) { HeaderNavActions(onOpenSettings) }
        }
    ) { padding ->
        if (state.isEmpty) {
            MessageState(
                icon = Icons.Rounded.Download,
                title = stringResource(R.string.shell_dl_empty_title),
                body = stringResource(R.string.shell_dl_empty_body),
                modifier = Modifier.padding(padding),
                action = { Button(onClick = onOpenMedia) { Text(stringResource(R.string.shell_dl_open_media)) } }
            )
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp)
        ) {
            val start = scheduledStart
            if (start != null && grouped[DownloadState.QUEUED].orEmpty().isNotEmpty()) {
                item(key = "schedule-notice", contentType = "notice") { Box(Modifier.animateItem()) { ScheduleNotice(start) } }
            }
            // Display order: action-needed states first.
            SECTION_ORDER.forEach { sectionState ->
                val rows = grouped[sectionState].orEmpty()
                if (rows.isEmpty()) return@forEach
                item(key = "header-$sectionState", contentType = "header") { Box(Modifier.animateItem()) { SectionHeader(sectionState.title(), rows.size) } }
                items(rows, key = { it.id }, contentType = { sectionState }) { row ->
                    val m = Modifier.animateItem()
                    when (sectionState) {
                        DownloadState.DOWNLOADING -> DownloadingRow(row, viewModel, m)
                        DownloadState.QUEUED -> QueuedRow(row, viewModel, m)
                        DownloadState.PAUSED -> PausedRow(row, viewModel, m)
                        DownloadState.FAILED -> FailedRow(row, viewModel, m)
                        DownloadState.COMPLETED -> Unit
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
    DownloadState.FAILED
)

@Composable
private fun DownloadState.title(): String = stringResource(
    when (this) {
        DownloadState.DOWNLOADING -> R.string.dl_section_downloading
        DownloadState.QUEUED -> R.string.dl_section_queued
        DownloadState.PAUSED -> R.string.dl_section_paused
        DownloadState.FAILED -> R.string.dl_section_failed
        DownloadState.COMPLETED -> R.string.dl_section_completed // never listed here
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
private fun DownloadingRow(row: ActiveRow, vm: ActiveDownloadsViewModel, modifier: Modifier = Modifier) {
    val waitingLabel = row.waitingReasonRes?.let { stringResource(it) } ?: stringResource(R.string.dl_waiting)
    val soFar = stringResource(R.string.dl_so_far)
    val eta = row.etaSeconds?.let { etaText(it).replace(' ', '\u00A0') }
    val view = LocalView.current
    val name = row.entity.filename
    val known = row.entity.filesize > 0
    val pct = if (known) "${(row.fraction * 100).toInt().coerceIn(0, 100)}%" else null
    // Archives built on the fly have no size until they finish.
    val sizePart = if (known) formatBytePair(row.bytesNow, row.entity.filesize) else "${formatBytes(row.bytesNow)} $soFar"
    val speed = formatSpeed(row.bytesPerSecond)
    // Longest first; parts drop from the middle (speed, then size), never the percentage or the ETA.
    val statusOptions = if (row.finishing) {
        listOf(stringResource(R.string.dl_finishing))
    } else if (row.waitingForNetwork) {
        listOf(
            listOfNotNull(waitingLabel, pct, sizePart).joinToString(" · "),
            listOfNotNull(waitingLabel, pct).joinToString(" · ")
        )
    } else {
        listOf(
            listOfNotNull(pct, sizePart, speed, eta),
            listOfNotNull(pct, sizePart, eta),
            listOfNotNull(pct, eta)
        ).map { it.joinToString(" · ") }.distinct()
    }
    val cs = MaterialTheme.colorScheme
    val labels = remember(name) { row.entity.releaseLabelsFor() }
    RowFrame(
        modifier = modifier,
        labels = labels,
        filename = row.entity.filename,
        statusOptions = statusOptions,
        icon = if (row.waitingForNetwork) Icons.Rounded.Schedule else Icons.Rounded.ArrowDownward,
        iconContainer = cs.primaryContainer,
        iconTint = cs.onPrimaryContainer,
        // Frozen at last-known fraction while waiting — gives the user a sense
        // of how far along the download already is, even without progress.
        // Unknown size -> indeterminate bar rather than a stuck 0%.
        progress = if (row.entity.filesize > 0 && !row.finishing) row.fraction else -1f,
        actions = {
            FilledTonalIconButton(onClick = { view.click(); vm.pause(row.id) }) {
                Icon(Icons.Rounded.Pause, contentDescription = stringResource(R.string.dl_cd_pause, labels.title))
            }
            IconButton(onClick = { view.strong(); vm.cancel(row.id) }) {
                Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.dl_cd_cancel, labels.title))
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
private fun QueuedRow(row: ActiveRow, vm: ActiveDownloadsViewModel, modifier: Modifier = Modifier) {
    val reason = vm.blocked.collectAsStateWithLifecycle().value?.labelRes
    val cs = MaterialTheme.colorScheme
    val view = LocalView.current
    val labels = remember(row.entity.filename) { row.entity.releaseLabelsFor() }
    val title = labels.title
    RowFrame(
        modifier = modifier,
        labels = labels,
        filename = row.entity.filename,
        statusOptions = reason?.let {
            listOf("${stringResource(it)} · ${formatBytes(row.entity.filesize)}")
        } ?: listOf(stringResource(R.string.dl_waiting_size, formatBytes(row.entity.filesize))),
        icon = Icons.Rounded.Schedule,
        iconContainer = cs.secondaryContainer,
        iconTint = cs.onSecondaryContainer,
        progress = null,
        actions = {
            IconButton(onClick = { view.strong(); vm.cancel(row.id) }) {
                Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.dl_cd_cancel, title))
            }
        }
    )
}

@Composable
private fun PausedRow(row: ActiveRow, vm: ActiveDownloadsViewModel, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val view = LocalView.current
    val labels = remember(row.entity.filename) { row.entity.releaseLabelsFor() }
    val title = labels.title
    RowFrame(
        modifier = modifier,
        labels = labels,
        filename = row.entity.filename,
        statusOptions = listOf(
            stringResource(
                R.string.dl_paused_status,
                "${(row.fraction * 100).toInt().coerceIn(0, 100)}%",
                formatBytePair(row.bytesNow, row.entity.filesize)
            )
        ),
        icon = Icons.Rounded.Pause,
        iconContainer = cs.surfaceContainerHighest,
        iconTint = cs.onSurfaceVariant,
        progress = row.fraction,
        actions = {
            FilledTonalIconButton(onClick = { view.click(); vm.resume(row.id) }) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = stringResource(R.string.dl_cd_resume, title))
            }
            IconButton(onClick = { view.strong(); vm.cancel(row.id) }) {
                Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.dl_cd_cancel, title))
            }
        }
    )
}

@Composable
private fun FailedRow(row: ActiveRow, vm: ActiveDownloadsViewModel, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val view = LocalView.current
    val labels = remember(row.entity.filename) { row.entity.releaseLabelsFor() }
    val title = labels.title
    RowFrame(
        modifier = modifier,
        labels = labels,
        filename = row.entity.filename,
        statusMaxLines = 2,
        statusOptions = listOf(row.entity.errorMessage ?: stringResource(R.string.dl_failed)),
        subtitleColor = cs.error,
        icon = Icons.Rounded.ErrorOutline,
        iconContainer = cs.errorContainer,
        iconTint = cs.onErrorContainer,
        progress = null,
        actions = {
            FilledTonalIconButton(onClick = { view.click(); vm.retry(row.id) }) {
                Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.dl_cd_retry, title))
            }
            IconButton(onClick = { view.strong(); vm.cancel(row.id) }) {
                Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.dl_cd_dismiss, title))
            }
        }
    )
}

// --- shared row frame --------------------------------------------------------

/** One download as a rounded card: state icon, parsed title, tags, status, filename, actions, progress. */
@Composable
private fun RowFrame(
    labels: ReleaseLabels,
    filename: String,
    statusOptions: List<String>,
    icon: ImageVector,
    iconContainer: Color,
    iconTint: Color,
    modifier: Modifier = Modifier,
    subtitleColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    statusMaxLines: Int = 1,
    progress: Float?,
    actions: @Composable () -> Unit
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val tnum = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum")
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Column(Modifier.padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(icon, container = iconContainer, tint = iconTint, size = 40.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = labels.title,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (labels.tags.isNotEmpty()) {
                        Text(labels.tags, style = tnum, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    // Raw filename: the quietest line; the end (extension) stays visible.
                    MiddleEllipsisText(filename, modifier = Modifier.padding(top = 2.dp), hideFromAccessibility = true)
                }
                Row(horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) { actions() }
            }
            // Changing numbers get the full card width, directly above the bar.
            FitText(
                options = statusOptions,
                style = tnum,
                color = subtitleColor,
                maxLines = statusMaxLines,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, end = 8.dp)
            )
            if (progress != null) {
                Spacer(Modifier.height(6.dp))
                val bar = Modifier.fillMaxWidth().padding(end = 8.dp).height(6.dp).clip(CircleShape)
                if (progress < 0f) {
                    LinearProgressIndicator(modifier = bar)
                } else {
                    val shown by animateFloatAsState(progress.coerceIn(0f, 1f), label = "dl-progress")
                    LinearProgressIndicator(progress = { shown }, modifier = bar)
                }
            }
        }
    }
}

/**
 * Shows the first option that fits on [maxLines] lines at the available width; later options are
 * shorter. Measured up front, so there is no overflow frame and nothing resets when the numbers tick.
 */
@Composable
private fun FitText(
    options: List<String>,
    style: androidx.compose.ui.text.TextStyle,
    color: Color,
    maxLines: Int,
    modifier: Modifier = Modifier
) {
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier) {
        val maxWidth = constraints.maxWidth
        val text = options.firstOrNull { option ->
            !measurer.measure(
                text = option,
                style = style,
                overflow = TextOverflow.Ellipsis,
                softWrap = maxLines > 1,
                maxLines = maxLines,
                constraints = Constraints(maxWidth = maxWidth)
            ).hasVisualOverflow
        } ?: options.last()
        Text(
            text = text,
            style = style,
            color = color,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            softWrap = maxLines > 1
        )
    }
}

// --- formatters --------------------------------------------------------------

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024L * 1024 -> "${bytes / 1024} KB"
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    else -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
}

/** "10.67 / 18.39 GB" when both share a unit, otherwise "637.9 MB / 1.68 GB". */
private fun formatBytePair(now: Long, total: Long): String {
    val a = formatBytes(now)
    val b = formatBytes(total)
    val unitA = a.substringAfterLast(' ')
    return if (unitA == b.substringAfterLast(' ')) "${a.substringBeforeLast(' ')} / $b" else "$a / $b"
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

private fun View.click() = performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)

private fun View.strong() = performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
