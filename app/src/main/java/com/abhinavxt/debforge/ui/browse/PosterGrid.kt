package com.abhinavxt.debforge.ui.browse

import com.abhinavxt.debforge.ui.pluralRes
import androidx.compose.ui.res.stringResource
import com.abhinavxt.debforge.R
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.abhinavxt.debforge.data.metadata.MediaMeta
import com.abhinavxt.debforge.domain.DownloadItem
import com.abhinavxt.debforge.domain.DownloadState
import com.abhinavxt.debforge.domain.MediaKind

/** Poster grid. Columns adapt to width, so tablets/TV get more per row. */
@Composable
fun PosterGrid(
    groups: List<TitleGroup>,
    meta: Map<String, MediaMeta?>,
    downloadStates: Map<String, DownloadState>,
    isAppending: Boolean,
    endReached: Boolean,
    onRequestMeta: (TitleGroup) -> Unit,
    onOpen: (TitleGroup) -> Unit,
    onLoadMore: () -> Unit
) {
    val gridState = rememberLazyGridState()

    LaunchedEffect(gridState) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { last ->
                val total = gridState.layoutInfo.totalItemsCount
                if (total > 0 && last >= total - 6) onLoadMore()
            }
    }

    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Adaptive(minSize = 112.dp),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 88.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(groups, key = { it.key }) { group ->
            // Ask for artwork only for cards that are actually composed
            // (i.e. on or near screen); the ViewModel de-duplicates.
            LaunchedEffect(group.key) { onRequestMeta(group) }
            PosterCard(
                group = group,
                meta = meta[group.key],
                allDone = group.files.all { downloadStates[it.item.id] == DownloadState.COMPLETED },
                onClick = { onOpen(group) }
            )
        }
        item(span = { GridItemSpan(maxLineSpan) }, key = "footer") {
            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                when {
                    isAppending -> CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
                    endReached -> Text(
                        stringResource(R.string.browse_end_of_list),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun PosterCard(
    group: TitleGroup,
    meta: MediaMeta?,
    allDone: Boolean,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
    ) {
        Poster(group = group, meta = meta, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(6.dp))
        Text(
            text = meta?.title ?: group.title,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = if (allDone) stringResource(R.string.poster_downloaded) else group.subtitleText(),
            style = MaterialTheme.typography.labelSmall,
            color = if (allDone) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * 2:3 poster. While artwork loads (or when there's none) it shows a tinted
 * typographic placeholder so the grid never looks broken.
 */
@Composable
fun Poster(group: TitleGroup, meta: MediaMeta?, modifier: Modifier = Modifier) {
    val (bg, fg) = placeholderColors(group.key)
    Box(
        modifier = modifier
            .aspectRatio(2f / 3f)
            .clip(RoundedCornerShape(10.dp))
            .background(bg),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(10.dp)
        ) {
            Text(
                text = when (group.kind) {
                    MediaKind.SHOW -> stringResource(R.string.poster_series_tag)
                    MediaKind.MOVIE -> stringResource(R.string.poster_movie_tag)
                    MediaKind.OTHER -> group.info.extension?.uppercase() ?: stringResource(R.string.poster_file_tag)
                },
                style = MaterialTheme.typography.labelSmall,
                color = fg.copy(alpha = 0.7f)
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = group.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = fg,
                textAlign = TextAlign.Center,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (meta?.posterUrl != null) {
            AsyncImage(
                model = meta.posterUrl,
                contentDescription = meta.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        // Quality badge (top-right) for movies / single files.
        val badge = if (group.kind == MediaKind.SHOW) null else group.files.first().info.resolution
        if (badge != null) {
            Surface(
                color = Color.Black.copy(alpha = 0.6f),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)
            ) {
                Text(
                    badge,
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun placeholderColors(key: String): Pair<Color, Color> {
    val cs = MaterialTheme.colorScheme
    return when (Math.floorMod(key.hashCode(), 3)) {
        0 -> cs.primaryContainer to cs.onPrimaryContainer
        1 -> cs.secondaryContainer to cs.onSecondaryContainer
        else -> cs.tertiaryContainer to cs.onTertiaryContainer
    }
}

// --- detail sheet ------------------------------------------------------------

/**
 * Bottom sheet for one title: artwork, overview, "Download all", and every
 * file (grouped by season for shows) with its own Download button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TitleSheet(
    group: TitleGroup,
    meta: MediaMeta?,
    downloadStates: Map<String, DownloadState>,
    onDownload: (List<DownloadItem>) -> Unit,
    onPlay: (ParsedItem) -> Unit,
    zipFor: (DownloadItem, Int) -> DownloadItem?,
    onDismiss: () -> Unit
) {
    val files = remember(group) { group.sortedFiles }
    // One "whole torrent as .zip" option per multi-file torrent in this title
    // (only services that support archives return anything).
    val zips = remember(group) {
        group.files
            .filter { it.item.parentRef != null }
            .groupBy { it.item.parentRef }
            .values
            .filter { it.size >= 2 }
            .mapNotNull { fs -> zipFor(fs.first().item, fs.size) }
    }
    val pending = files.filter { downloadStates[it.item.id] == null }.map { it.item }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
            item(key = "header") {
                Row(Modifier.padding(horizontal = 20.dp)) {
                    Poster(group = group, meta = meta, modifier = Modifier.width(96.dp))
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            meta?.title ?: group.title,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        val year = meta?.year ?: group.info.year
                        Text(
                            listOfNotNull(
                                year?.toString(),
                                when (group.kind) {
                                    MediaKind.SHOW -> stringResource(R.string.kind_series)
                                    MediaKind.MOVIE -> stringResource(R.string.kind_movie)
                                    MediaKind.OTHER -> null
                                },
                                pluralRes(R.plurals.n_files, files.size, files.size),
                                formatSize(group.totalSize)
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = { onDownload(pending) },
                            enabled = pending.isNotEmpty()
                        ) {
                            Text(
                                when {
                                    pending.isEmpty() -> stringResource(R.string.sheet_all_queued)
                                    pending.size == 1 -> stringResource(R.string.action_download)
                                    else -> stringResource(R.string.sheet_download_all, pending.size)
                                }
                            )
                        }
                    }
                }
            }
            zips.forEach { zip ->
                item(key = zip.id) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.sheet_zip_title), style = MaterialTheme.typography.bodyMedium)
                            Text(
                                zip.parentName ?: zip.filename,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        val zipState = downloadStates[zip.id]
                        if (zipState != null) {
                            Text(
                                zipState.badgeLabel(),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 12.dp)
                            )
                        } else {
                            TextButton(onClick = { onDownload(listOf(zip)) }) { Text(stringResource(R.string.sheet_zip_download)) }
                        }
                    }
                }
            }
            meta?.overview?.let { overview ->
                item(key = "overview") { Overview(overview) }
            }
            item(key = "divider") { HorizontalDivider(Modifier.padding(top = 12.dp)) }

            var lastSeason: Int? = null
            files.forEach { f ->
                val season = f.info.season
                if (group.kind == MediaKind.SHOW && season != null && season != lastSeason) {
                    lastSeason = season
                    val seasonFiles = files.filter { it.info.season == season }
                    val seasonPending = seasonFiles.filter { downloadStates[it.item.id] == null }.map { it.item }
                    item(key = "season-$season") {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                stringResource(R.string.sheet_season, season),
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.weight(1f)
                            )
                            if (seasonPending.size > 1) {
                                TextButton(onClick = { onDownload(seasonPending) }) {
                                    Text(stringResource(R.string.sheet_download_season, seasonPending.size))
                                }
                            }
                        }
                    }
                }
                item(key = f.item.id) {
                    FileRow(
                        file = f,
                        state = downloadStates[f.item.id],
                        onDownload = { onDownload(listOf(f.item)) },
                        onPlay = { onPlay(f) }
                    )
                }
            }
        }
    }
}

@Composable
private fun Overview(text: String) {
    var expanded by remember { mutableStateOf(false) }
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        maxLines = if (expanded) Int.MAX_VALUE else 4,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .clickable { expanded = !expanded }
    )
}

@Composable
private fun FileRow(file: ParsedItem, state: DownloadState?, onDownload: () -> Unit, onPlay: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                listOfNotNull(file.info.episodeLabel, file.info.qualityLabel.ifEmpty { null }, formatSize(file.item.filesize))
                    .joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                file.item.filename,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (file.info.isVideo) {
            // Stream straight from the service — no download needed.
            IconButton(onClick = onPlay) {
                Icon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.action_play))
            }
        }
        if (state != null) {
            Text(
                state.badgeLabel(),
                style = MaterialTheme.typography.labelMedium,
                color = if (state == DownloadState.FAILED) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 12.dp)
            )
        } else {
            TextButton(onClick = onDownload) { Text(stringResource(R.string.action_download)) }
        }
    }
}

@Composable
fun DownloadState.badgeLabel(): String = stringResource(
    when (this) {
        DownloadState.QUEUED -> R.string.state_queued
        DownloadState.DOWNLOADING -> R.string.state_downloading
        DownloadState.PAUSED -> R.string.state_paused
        DownloadState.COMPLETED -> R.string.state_done
        DownloadState.FAILED -> R.string.state_failed
    }
)

/** "S1–S3 · 24 episodes", "2023 · 2 versions", "4.2 GB" — localised. */
@Composable
fun TitleGroup.subtitleText(): String = when (kind) {
    MediaKind.SHOW -> {
        val seasons = files.mapNotNull { it.info.season }.distinct().sorted()
        val seasonPart = when {
            seasons.isEmpty() -> null
            seasons.size == 1 -> "S${seasons.first()}"
            else -> "S${seasons.first()}–S${seasons.last()}"
        }
        listOfNotNull(seasonPart, pluralRes(R.plurals.n_episodes, files.size, files.size)).joinToString(" · ")
    }
    MediaKind.MOVIE -> listOfNotNull(
        info.year?.toString(),
        if (files.size > 1) pluralRes(R.plurals.n_versions, files.size, files.size) else files.first().info.resolution
    ).joinToString(" · ")
    MediaKind.OTHER -> formatSize(totalSize)
}
