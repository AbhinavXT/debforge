package com.abhinavxt.debforge.ui.browse

import androidx.compose.ui.res.stringResource
import com.abhinavxt.debforge.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.abhinavxt.debforge.data.provider.RemoteJob

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
    onRemove: ((RemoteJob) -> Unit)? = null
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
                JobCard(job, isWatched(job), onToggle = { onToggleWatch(job) }, onRemove = onRemove?.let { { it(job) } })
            }
        }
    }
}

@Composable
private fun JobCard(job: RemoteJob, watched: Boolean, onToggle: () -> Unit, onRemove: (() -> Unit)?) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.width(248.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(
                    Icons.Rounded.CloudDownload,
                    container = MaterialTheme.colorScheme.tertiaryContainer,
                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                    size = 32.dp
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    job.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(12.dp))
            val p = job.progress
            val barModifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape)
            if (p != null) {
                LinearProgressIndicator(progress = { p }, modifier = barModifier)
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
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilterChip(
                    selected = watched,
                    onClick = onToggle,
                    modifier = Modifier.weight(1f, fill = false),
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
                                if (watched) R.string.processing_will_auto else R.string.processing_auto_when_ready
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                )
                if (onRemove != null) {
                    IconButton(onClick = onRemove) {
                        Icon(
                            Icons.Rounded.DeleteOutline,
                            contentDescription = stringResource(R.string.action_remove),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
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
