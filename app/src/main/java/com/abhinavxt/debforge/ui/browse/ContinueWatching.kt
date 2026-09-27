package com.abhinavxt.debforge.ui.browse

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.abhinavxt.debforge.R
import com.abhinavxt.debforge.data.metadata.MediaMeta
import com.abhinavxt.debforge.data.playback.PlaybackEntity
import com.abhinavxt.debforge.domain.ReleaseInfo
import com.abhinavxt.debforge.domain.ReleaseNameParser
import com.abhinavxt.debforge.ui.components.GeneratedArt
import com.abhinavxt.debforge.ui.pluralRes

/**
 * "Continue watching": videos started in DebForge's player and not finished,
 * most recent first. Tap to carry on (a fresh link is fetched), long-press
 * to remove one from the row.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ContinueWatchingRow(
    entries: List<PlaybackEntity>,
    meta: Map<String, MediaMeta?>,
    onRequestMeta: (ReleaseInfo) -> Unit,
    onPlay: (PlaybackEntity) -> Unit,
    onRemove: (PlaybackEntity) -> Unit
) {
    if (entries.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        Text(
            stringResource(R.string.continue_watching),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(end = 16.dp)
        ) {
            items(entries, key = { it.itemId }) { entry ->
                val info = remember(entry.filename) { ReleaseNameParser.parse(entry.filename) }
                LaunchedEffect(info.groupKey) { onRequestMeta(info) }
                ContinueCard(
                    entry = entry,
                    info = info,
                    meta = meta[info.groupKey],
                    onPlay = { onPlay(entry) },
                    onRemove = { onRemove(entry) }
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContinueCard(
    entry: PlaybackEntity,
    info: ReleaseInfo,
    meta: MediaMeta?,
    onPlay: () -> Unit,
    onRemove: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    Column(
        Modifier
            .width(220.dp)
            .clip(MaterialTheme.shapes.medium)
            .combinedClickable(onClick = onPlay, onLongClick = { menu = true })
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(MaterialTheme.shapes.medium)
        ) {
            val image = meta?.backdropUrl ?: meta?.posterUrl
            if (image != null) {
                AsyncImage(
                    model = image,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                GeneratedArt(title = info.title, tag = info.episodeLabel.orEmpty(), modifier = Modifier.fillMaxSize())
            }
            LinearProgressIndicator(
                progress = { entry.progress ?: 0f },
                modifier = Modifier.fillMaxWidth().height(4.dp).align(Alignment.BottomCenter)
            )
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.continue_remove)) },
                    leadingIcon = { Icon(Icons.Rounded.DeleteOutline, contentDescription = null) },
                    onClick = { menu = false; onRemove() }
                )
            }
        }
        Text(
            entry.title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp, start = 2.dp)
        )
        val minutesLeft = ((entry.durationMs - entry.positionMs) / 60_000L).toInt().coerceAtLeast(1)
        Text(
            pluralRes(R.plurals.continue_minutes_left, minutesLeft, minutesLeft),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 2.dp)
        )
    }
}
