package com.abhinavxt.debforge.ui.browse

import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material.icons.rounded.NotificationAdd
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material3.FilterChip
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.FilledTonalIconButton
import com.abhinavxt.debforge.ui.components.GeneratedArt
import com.abhinavxt.debforge.ui.components.IconBadge
import com.abhinavxt.debforge.ui.components.StatusPill
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
    onLoadMore: () -> Unit,
    /** Full-width content above the posters (the Continue watching row). */
    header: (@Composable () -> Unit)? = null
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
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        if (header != null) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "header") { header() }
        }
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
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
    ) {
        Poster(group = group, meta = meta, done = allDone, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Text(
            text = meta?.title ?: group.title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp)
        )
        Text(
            text = if (allDone) stringResource(R.string.poster_downloaded) else group.subtitleText(),
            style = MaterialTheme.typography.labelMedium,
            color = if (allDone) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp)
        )
    }
}

/**
 * 2:3 poster. While artwork loads (or when there's none) it shows generated
 * art in the theme's colours so the grid never looks broken.
 */
@Composable
fun Poster(group: TitleGroup, meta: MediaMeta?, modifier: Modifier = Modifier, done: Boolean = false) {
    Box(
        modifier = modifier
            .aspectRatio(2f / 3f)
            .clip(RoundedCornerShape(16.dp))
    ) {
        GeneratedArt(
            title = group.title,
            tag = when (group.kind) {
                MediaKind.SHOW -> stringResource(R.string.poster_series_tag)
                MediaKind.MOVIE -> stringResource(R.string.poster_movie_tag)
                MediaKind.OTHER -> group.info.extension?.uppercase() ?: stringResource(R.string.poster_file_tag)
            },
            modifier = Modifier.fillMaxSize()
        )
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
                color = Color.Black.copy(alpha = 0.55f),
                contentColor = Color.White,
                shape = CircleShape,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
            ) {
                Text(
                    badge,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
        }
        // Everything downloaded: a check in the corner.
        if (done) {
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
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
    onDismiss: () -> Unit,
    /** Null when the service can't remove these files. */
    onRemove: (() -> Unit)? = null,
    serviceName: String = "",
    /** Shows only: followed right now? Null hides the button (movies, other files). */
    following: Boolean? = null,
    onToggleFollow: () -> Unit = {},
    /** Saved positions by item id: progress bars and "Watched". */
    playback: Map<String, com.abhinavxt.debforge.data.playback.PlaybackEntity> = emptyMap()
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

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
            item(key = "header") {
                Row(Modifier.padding(horizontal = 20.dp)) {
                    Poster(group = group, meta = meta, modifier = Modifier.width(112.dp))
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            meta?.title ?: group.title,
                            style = MaterialTheme.typography.headlineSmall
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
                        Spacer(Modifier.height(14.dp))
                        Button(
                            onClick = { onDownload(pending) },
                            enabled = pending.isNotEmpty()
                        ) {
                            Icon(
                                if (pending.isEmpty()) Icons.Rounded.Check else Icons.Rounded.Download,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                when {
                                    pending.isEmpty() -> stringResource(R.string.sheet_all_queued)
                                    pending.size == 1 -> stringResource(R.string.action_download)
                                    else -> stringResource(R.string.sheet_download_all, pending.size)
                                }
                            )
                        }
                        if (following != null) {
                            FilterChip(
                                selected = following,
                                onClick = onToggleFollow,
                                label = { Text(stringResource(if (following) R.string.follow_following else R.string.follow_action)) },
                                leadingIcon = {
                                    Icon(
                                        if (following) Icons.Rounded.NotificationsActive else Icons.Rounded.NotificationAdd,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            )
                            if (following) {
                                Text(
                                    stringResource(R.string.follow_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        if (onRemove != null) {
                            TextButton(onClick = onRemove) {
                                Icon(
                                    Icons.Rounded.DeleteOutline,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    stringResource(R.string.action_remove_from, serviceName),
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }
            zips.forEach { zip ->
                item(key = zip.id) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, top = 12.dp)
                            .clip(MaterialTheme.shapes.medium)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconBadge(Icons.Rounded.FolderZip)
                        Spacer(Modifier.width(12.dp))
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
                            DownloadStatePill(zipState, Modifier.padding(horizontal = 8.dp))
                        } else {
                            TextButton(onClick = { onDownload(listOf(zip)) }) { Text(stringResource(R.string.sheet_zip_download)) }
                        }
                    }
                }
            }
            meta?.overview?.let { overview ->
                item(key = "overview") { Overview(overview) }
            }
            item(key = "divider") { Spacer(Modifier.height(8.dp)) }

            var lastSeason: Int? = null
            files.forEach { f ->
                val season = f.info.season
                if (group.kind == MediaKind.SHOW && season != null && season != lastSeason) {
                    lastSeason = season
                    val seasonFiles = files.filter { it.info.season == season }
                    val seasonPending = seasonFiles.filter { downloadStates[it.item.id] == null }.map { it.item }
                    item(key = "season-$season") {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 16.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                stringResource(R.string.sheet_season, season),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
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
                        onPlay = { onPlay(f) },
                        playback = playback[f.item.id]
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
private fun FileRow(
    file: ParsedItem,
    state: DownloadState?,
    onDownload: () -> Unit,
    onPlay: () -> Unit,
    playback: com.abhinavxt.debforge.data.playback.PlaybackEntity? = null
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 3.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(start = 16.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                listOfNotNull(file.info.episodeLabel, file.info.qualityLabel.ifEmpty { null }, formatSize(file.item.filesize))
                    .joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                file.item.filename,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            // Watched, or how far along, in DebForge's player.
            when {
                playback == null -> Unit
                playback.finished -> Text(
                    stringResource(R.string.watched),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                playback.progress != null && playback.positionMs >= com.abhinavxt.debforge.domain.Resume.MIN_RESUME_MS ->
                    LinearProgressIndicator(
                        progress = { playback.progress ?: 0f },
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp, end = 8.dp).height(3.dp)
                    )
            }
        }
        if (file.info.isVideo) {
            // Stream straight from the service — no download needed.
            IconButton(onClick = onPlay) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = stringResource(R.string.action_play))
            }
        }
        if (state != null) {
            DownloadStatePill(state, Modifier.padding(start = 4.dp, end = 6.dp))
        } else {
            FilledTonalIconButton(onClick = onDownload) {
                Icon(Icons.Rounded.Download, contentDescription = stringResource(R.string.action_download))
            }
        }
    }
}

/** Download state as a coloured pill with an icon, used in lists and the title sheet. */
@Composable
fun DownloadStatePill(state: DownloadState, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val (container, content, icon) = when (state) {
        DownloadState.COMPLETED -> Triple(cs.primaryContainer, cs.onPrimaryContainer, Icons.Rounded.CheckCircle)
        DownloadState.FAILED -> Triple(cs.errorContainer, cs.onErrorContainer, Icons.Rounded.ErrorOutline)
        DownloadState.DOWNLOADING -> Triple(cs.tertiaryContainer, cs.onTertiaryContainer, Icons.Rounded.ArrowDownward)
        DownloadState.PAUSED -> Triple(cs.surfaceContainerHighest, cs.onSurfaceVariant, Icons.Rounded.Pause)
        DownloadState.QUEUED -> Triple(cs.secondaryContainer, cs.onSecondaryContainer, Icons.Rounded.Schedule)
    }
    StatusPill(text = state.badgeLabel(), modifier = modifier, container = container, content = content, icon = icon)
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
