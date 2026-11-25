package com.abhinavxt.debforge.ui.browse

import androidx.compose.ui.res.stringResource
import com.abhinavxt.debforge.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.abhinavxt.debforge.data.provider.JobAction
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.ui.draw.clip
import com.abhinavxt.debforge.ui.components.IconBadge
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.abhinavxt.debforge.data.provider.RemoteJob
import com.abhinavxt.debforge.ui.components.MiddleEllipsisText
import com.abhinavxt.debforge.ui.components.releaseLabels
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * Horizontal strip of jobs the service is still fetching, each with a
 * progress bar and an "Auto-download" toggle ("download when ready").
 */
@Composable
fun ProcessingStrip(
    providerName: String,
    jobs: List<RemoteJob>,
    isWatched: (RemoteJob) -> Boolean,
    onToggleWatch: (RemoteJob) -> Unit,
    onRemove: ((RemoteJob) -> Unit)? = null,
    onAction: ((RemoteJob, JobAction) -> Unit)? = null
) {
    if (jobs.isEmpty()) return
    Column(Modifier.padding(top = 4.dp)) {
        Text(
            stringResource(R.string.processing_on, providerName),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 24.dp)
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(jobs, key = { it.ref }) { job ->
                JobCard(job, isWatched(job), onToggle = { onToggleWatch(job) }, onRemove = onRemove?.let { { it(job) } },
                    onAction = onAction?.let { { a -> it(job, a) } })
            }
        }
    }
}

@Composable
private fun JobCard(
    job: RemoteJob,
    watched: Boolean,
    onToggle: () -> Unit,
    onRemove: (() -> Unit)?,
    onAction: ((JobAction) -> Unit)?
) {
    val labels = remember(job.name) { releaseLabels(job.name) }
    val haptic = LocalHapticFeedback.current
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.width(248.dp)
    ) {
        Box(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                IconBadge(
                    Icons.Rounded.CloudDownload,
                    container = MaterialTheme.colorScheme.tertiaryContainer,
                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                    size = 32.dp
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f).padding(end = if (onRemove != null || job.actions.isNotEmpty()) 36.dp else 0.dp)) {
                    Text(
                        labels.title,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (labels.tags.isNotEmpty()) {
                        Text(
                            labels.tags,
                            style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    MiddleEllipsisText(job.name)
                }
            }
            Spacer(Modifier.height(8.dp))
            val p = job.progress
            val barModifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape)
            if (p != null) {
                val shown by animateFloatAsState(p.coerceIn(0f, 1f), label = "job-progress")
                LinearProgressIndicator(progress = { shown }, modifier = barModifier)
            } else {
                LinearProgressIndicator(modifier = barModifier)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                listOfNotNull(
                    job.status.replaceFirstChar { it.uppercase() },
                    p?.let { "${(it * 100).toInt()}%" },
                    job.etaSeconds?.let { formatEta(it) },
                    job.bytesPerSecond?.let { formatSize(it) + "/s" }
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilterChip(
                    selected = watched,
                    onClick = onToggle,
                    leadingIcon = {
                        Icon(
                            if (watched) Icons.Rounded.Check else Icons.Rounded.Bolt,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    label = {
                        Text(
                            stringResource(
                                if (watched) R.string.processing_auto_on_short else R.string.processing_auto_short
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                )
            }
        }
        if (onAction != null && job.actions.isNotEmpty()) {
            var open by remember { mutableStateOf(false) }
            Box(Modifier.align(Alignment.TopEnd)) {
                IconButton(onClick = { open = true }) {
                    Icon(
                        Icons.Rounded.MoreVert,
                        contentDescription = stringResource(R.string.processing_cd_actions, labels.title)
                    )
                }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    job.actions.forEach { action ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(
                                        when (action) {
                                            JobAction.START_NOW -> R.string.processing_start_now
                                            JobAction.REANNOUNCE -> R.string.processing_reannounce
                                            JobAction.RESUME -> R.string.processing_resume
                                        }
                                    )
                                )
                            },
                            onClick = { open = false; onAction(action) }
                        )
                    }
                    if (onRemove != null) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.processing_menu_remove), color = MaterialTheme.colorScheme.error) },
                            onClick = { open = false; haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onRemove() }
                        )
                    }
                }
            }
        } else if (onRemove != null) {
            IconButton(
                onClick = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onRemove() },
                modifier = Modifier.align(Alignment.TopEnd)
            ) {
                Icon(
                    Icons.Rounded.DeleteOutline,
                    contentDescription = stringResource(R.string.processing_cd_remove, labels.title),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
        }
    }
}

@Composable
private fun formatEta(seconds: Long): String = when {
    seconds < 60 -> stringResource(R.string.eta_seconds, seconds.toInt())
    seconds < 3600 -> stringResource(R.string.eta_minutes, (seconds / 60).toInt())
    else -> stringResource(R.string.eta_hours, (seconds / 3600).toInt(), ((seconds % 3600) / 60).toInt())
}
