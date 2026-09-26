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
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
    onToggleWatch: (RemoteJob) -> Unit
) {
    if (jobs.isEmpty()) return
    Column(Modifier.padding(top = 8.dp)) {
        Text(
            stringResource(R.string.processing_on, providerName),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(jobs, key = { it.ref }) { job -> JobCard(job, isWatched(job)) { onToggleWatch(job) } }
        }
    }
}

@Composable
private fun JobCard(job: RemoteJob, watched: Boolean, onToggle: () -> Unit) {
    Card(Modifier.width(230.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(
                job.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(8.dp))
            val p = job.progress
            if (p != null) {
                LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
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
            FilterChip(
                selected = watched,
                onClick = onToggle,
                label = {
                    Text(
                        stringResource(
                            if (watched) R.string.processing_will_auto else R.string.processing_auto_when_ready
                        )
                    )
                }
            )
        }
    }
}

@Composable
private fun formatEta(seconds: Long): String = when {
    seconds < 60 -> stringResource(R.string.eta_seconds, seconds.toInt())
    seconds < 3600 -> stringResource(R.string.eta_minutes, (seconds / 60).toInt())
    else -> stringResource(R.string.eta_hours, (seconds / 3600).toInt(), ((seconds % 3600) / 60).toInt())
}
