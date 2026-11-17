
package com.abhinavxt.debforge.ui.browse

import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material.icons.rounded.NotificationAdd
import androidx.compose.material.icons.rounded.NotificationsActive
import com.abhinavxt.debforge.ui.pluralRes
import androidx.compose.ui.res.stringResource
import com.abhinavxt.debforge.R
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
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
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import coil.request.ImageRequest
import com.abhinavxt.debforge.ui.components.GeneratedArt
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.material3.SheetValue
import kotlinx.coroutines.launch
import com.abhinavxt.debforge.ui.player.focusRing
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import androidx.compose.ui.layout.layout
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
    header: (@Composable () -> Unit)? = null,
    /** Full-width blocks (poster prompt, Processing) that scroll with the posters, above everything else. */
    leading: (@Composable () -> Unit)? = null,
    /** Titles selected (multi-select); empty = not selecting. */
    selectedKeys: Set<String> = emptySet(),
    onLongPress: (TitleGroup) -> Unit = {},
    gridState: LazyGridState = rememberLazyGridState(),
    /** When given, the header and FAB follow this grid's exact scroll position. */
    scroll: LibraryScrollState? = null
) {
    if (scroll != null) {
        TrackLibraryScroll(
            scroll,
            atTop = { gridState.firstVisibleItemIndex == 0 && gridState.firstVisibleItemScrollOffset == 0 },
            cannotScroll = { !gridState.canScrollForward && !gridState.canScrollBackward }
        )
    }

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
        // Fixed items use "fixed:" keys and groups "group:" keys, so the two can never collide.
        if (leading != null) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "fixed:leading") {
                // The grid already pads 16dp each side; the blocks bring their own gutters.
                Box(Modifier.bleedHorizontally(16.dp).animateItem()) { leading() }
            }
        }
        if (header != null) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "fixed:header") { header() }
        }
        items(groups, key = { "group:" + it.key }) { group ->
            // Ask for artwork only for cards that are actually composed
            // (i.e. on or near screen); the ViewModel de-duplicates.
            LaunchedEffect(group.key) { onRequestMeta(group) }
            PosterCard(
                modifier = Modifier.animateItem(),
                group = group,
                meta = meta[group.key],
                allDone = group.files.all { downloadStates[it.item.id] == DownloadState.COMPLETED },
                onClick = { onOpen(group) },
                selected = group.key in selectedKeys,
                onLongClick = { onLongPress(group) }
            )
        }
        item(span = { GridItemSpan(maxLineSpan) }, key = "fixed:footer") {
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

/** Lets a child use the [horizontal] content padding of its parent as extra width. */
private fun Modifier.bleedHorizontally(horizontal: androidx.compose.ui.unit.Dp): Modifier =
    layout { measurable, constraints ->
        val extra = horizontal.roundToPx() * 2
        val placeable = measurable.measure(constraints.offset(horizontal = extra))
        layout(constraints.maxWidth, placeable.height) { placeable.place(-extra / 2, 0) }
    }

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun PosterCard(
    modifier: Modifier = Modifier,
    group: TitleGroup,
    meta: MediaMeta?,
    allDone: Boolean,
    onClick: () -> Unit,
    selected: Boolean = false,
    onLongClick: () -> Unit = {}
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, label = "posterPress")
    Column(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(16.dp))
            .focusRing(interaction, RoundedCornerShape(16.dp))
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .combinedClickable(
                interactionSource = interaction,
                indication = androidx.compose.foundation.LocalIndication.current,
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        Box {
            Poster(group = group, meta = meta, done = allDone, modifier = Modifier.fillMaxWidth())
            if (selected) {
                Box(
                    Modifier
                        .matchParentSize()
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Rounded.CheckCircle,
                        contentDescription = stringResource(R.string.browse_selected_cd),
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(40.dp)
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = meta?.title ?: group.title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp)
        )
        Text(
            text = if (allDone) stringResource(R.string.poster_downloaded) else group.subtitleText(withResolution = false),
            style = MaterialTheme.typography.bodySmall,
            color = if (allDone) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // Clear of the card's rounded bottom corners: the clip (which
            // shapes the ripple) would otherwise cut into this line.
            modifier = Modifier.padding(start = 2.dp, end = 2.dp, bottom = 10.dp)
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
            // Near-black artwork would otherwise melt into a black (AMOLED) background.
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
    ) {
        GeneratedArt(
            title = group.title,
            tag = when (group.kind) {
                MediaKind.SHOW -> stringResource(R.string.poster_series_tag)
                MediaKind.MOVIE -> stringResource(R.string.poster_movie_tag)
                MediaKind.OTHER -> group.info.extension?.uppercase() ?: stringResource(R.string.poster_file_tag)
            },
            showTitle = false,
            modifier = Modifier.fillMaxSize()
        )
        if (meta?.posterUrl != null) {
            val context = LocalContext.current
            val request = remember(meta.posterUrl, context) {
                ImageRequest.Builder(context).data(meta.posterUrl).crossfade(true).build()
            }
            AsyncImage(
                model = request,
                contentDescription = meta.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        // Quality badge (top-right) for movies / single files.
        val badge = if (group.kind == MediaKind.SHOW) null else group.files.first().info.resolution
        if (badge != null) {
            Surface(
                color = Color.Black.copy(alpha = 0.72f),
                contentColor = Color.White,
                shape = CircleShape,
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)
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

private val ARROW_KEYS = setOf(Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight)

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
    removeLabel: String = "",
    /** Shows only: followed right now? Null hides the button (movies, other files). */
    following: Boolean? = null,
    onToggleFollow: () -> Unit = {},
    /** Saved positions by item id: progress bars and "Watched". */
    playback: Map<String, com.abhinavxt.debforge.data.playback.PlaybackEntity> = emptyMap(),
    /** Watched on Trakt ([com.abhinavxt.debforge.domain.TraktMatch] keys). */
    traktWatched: Set<String> = emptySet()
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

    val sheetState = androidx.compose.material3.rememberModalBottomSheetState()
    val sheetScope = androidx.compose.runtime.rememberCoroutineScope()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        LazyColumn(
            contentPadding = PaddingValues(bottom = 32.dp),
            // D-pad: at half height the rows below the fold are laid out but off screen, so focus would vanish. Open the sheet fully on the first arrow key.
            modifier = Modifier.onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.key in ARROW_KEYS && sheetState.currentValue == SheetValue.PartiallyExpanded) {
                    sheetScope.launch { sheetState.expand() }
                }
                false
            }
        ) {
            item(key = "fixed:header") {
                // Large font on a narrow phone: details go under the poster, so the buttons keep their width.
                val stacked = LocalConfiguration.current.screenWidthDp / LocalDensity.current.fontScale < 320
                val headerTop: @Composable () -> Unit = {
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
                }
                val headerBottom: @Composable () -> Unit = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { onDownload(pending) },
                            enabled = pending.isNotEmpty(),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                if (pending.isEmpty()) Icons.Rounded.Check else Icons.Rounded.Download,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(
                                    when {
                                        pending.isEmpty() -> stringResource(R.string.sheet_all_queued)
                                        pending.size == 1 -> stringResource(R.string.action_download)
                                        else -> stringResource(R.string.sheet_download_all, pending.size)
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (pending.size > 1) {
                                    Text(
                                        formatSize(pending.sumOf { it.filesize }),
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                        if (following != null) {
                            val followCd = stringResource(if (following) R.string.follow_following else R.string.follow_action)
                            val followIcon: @Composable () -> Unit = {
                                Icon(
                                    if (following) Icons.Rounded.NotificationsActive else Icons.Rounded.NotificationAdd,
                                    contentDescription = followCd
                                )
                            }
                            if (following) {
                                FilledTonalIconButton(onClick = onToggleFollow, modifier = Modifier.size(48.dp), content = followIcon)
                            } else {
                                androidx.compose.material3.OutlinedIconButton(onClick = onToggleFollow, modifier = Modifier.size(48.dp), content = followIcon)
                            }
                        }
                    }
                }
                if (stacked) {
                    Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 12.dp)) {
                        Poster(group = group, meta = meta, modifier = Modifier.padding(bottom = 12.dp).width(104.dp))
                        headerTop()
                        Spacer(Modifier.height(12.dp))
                        headerBottom()
                    }
                } else {
                    // Poster-height column: title and meta on top, the main button on the poster's bottom edge.
                    Row(Modifier.padding(horizontal = 20.dp).padding(bottom = 12.dp).height(IntrinsicSize.Min)) {
                        Poster(group = group, meta = meta, modifier = Modifier.padding(end = 16.dp).width(104.dp))
                        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
                            Column { headerTop() }
                            headerBottom()
                        }
                    }
                }
                Column(Modifier.padding(horizontal = 20.dp)) {
                    if (zips.size == 1) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (zips.size == 1) {
                                val zip = zips.first()
                                val zipState = downloadStates[zip.id]
                                if (zipState != null) {
                                    Box(Modifier.weight(1f).height(40.dp), contentAlignment = Alignment.Center) {
                                        DownloadStatePill(zipState)
                                    }
                                } else {
                                    OutlinedButton(onClick = { onDownload(listOf(zip)) }, modifier = Modifier.weight(1f)) {
                                        Icon(Icons.Rounded.FolderZip, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            stringResource(R.string.sheet_zip_download),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                    // Several archives: one named line each, with its own action or state.
                    if (zips.size >= 2) {
                        zips.forEach { zip ->
                            val zipState = downloadStates[zip.id]
                            Row(
                                Modifier.fillMaxWidth().padding(top = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(
                                    Icons.Rounded.FolderZip,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    zip.filename,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                if (zipState != null) {
                                    DownloadStatePill(zipState)
                                } else {
                                    OutlinedButton(onClick = { onDownload(listOf(zip)) }) {
                                        Text(stringResource(R.string.sheet_zip_download), maxLines = 1)
                                    }
                                }
                            }
                        }
                    }
                    if (following == true) {
                        Text(
                            stringResource(R.string.follow_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 12.dp)
                        )
                    }
                }
            }
            meta?.overview?.let { overview ->
                item(key = "fixed:overview") { Overview(overview) }
            }
            item(key = "fixed:divider") { Spacer(Modifier.height(4.dp)) }

            // Several files for one episode (different releases): number them so they can be told apart.
            val versionsByEpisode = files
                .filter { it.info.episodeLabel != null }
                .groupBy { it.info.season to it.info.episodeLabel }
                .filterValues { it.size > 1 }
            var lastSeason: Int? = null
            files.forEach { f ->
                val season = f.info.season
                if (group.kind == MediaKind.SHOW && season != null && season != lastSeason) {
                    lastSeason = season
                    val seasonFiles = files.filter { it.info.season == season }
                    val seasonPending = seasonFiles.filter { downloadStates[it.item.id] == null }.map { it.item }
                    item(key = "season:$season") {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
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
                item(key = "file:" + f.item.id) {
                    val versions = versionsByEpisode[f.info.season to f.info.episodeLabel]
                    FileRow(
                        file = f,
                        version = versions?.let { it.indexOf(f) + 1 to it.size },
                        state = downloadStates[f.item.id],
                        onDownload = { onDownload(listOf(f.item)) },
                        onPlay = { onPlay(f) },
                        playback = playback[f.item.id],
                        watchedOnTrakt = traktWatched.isNotEmpty() &&
                            com.abhinavxt.debforge.domain.TraktMatch.keyOf(f.info) in traktWatched
                    )
                }
            }
            if (onRemove != null) {
                item(key = "fixed:remove") {
                    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp)) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        TextButton(
                            onClick = onRemove,
                            contentPadding = PaddingValues(end = 12.dp),
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) {
                            Icon(
                                Icons.Rounded.DeleteOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(removeLabel, color = MaterialTheme.colorScheme.error)
                        }
                    }
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
    /** (n, of) when this episode has several files. */
    version: Pair<Int, Int>? = null,
    state: DownloadState?,
    onDownload: () -> Unit,
    onPlay: () -> Unit,
    playback: com.abhinavxt.debforge.data.playback.PlaybackEntity? = null,
    watchedOnTrakt: Boolean = false
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
            val label = file.info.episodeLabel
            if (label != null) {
                Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            }
            Text(
                listOfNotNull(
                    version?.let { stringResource(R.string.sheet_version_of, it.first, it.second) },
                    file.info.qualityLabel.ifEmpty { null },
                    formatSize(file.item.filesize)
                ).joinToString(" · "),
                style = if (label != null) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
                color = if (label != null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
            )
            Text(
                file.item.filename,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (version != null) 1f else 0.75f),
                maxLines = if (version != null) 2 else 1,
                overflow = TextOverflow.Ellipsis
            )
            // Watched (in DebForge's player or on Trakt), or how far along.
            when {
                playback?.finished == true ||
                    (watchedOnTrakt && (playback == null || playback.positionMs < com.abhinavxt.debforge.domain.Resume.MIN_RESUME_MS)) -> Text(
                    stringResource(R.string.watched),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                playback == null -> Unit
                playback.progress != null && playback.positionMs >= com.abhinavxt.debforge.domain.Resume.MIN_RESUME_MS ->
                    LinearProgressIndicator(
                        progress = { playback.progress ?: 0f },
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp, end = 8.dp).height(3.dp)
                    )
            }
        }
        val what = file.info.episodeLabel ?: file.info.title
        if (file.info.isVideo) {
            // Stream straight from the service — no download needed.
            FilledTonalIconButton(onClick = onPlay, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = stringResource(R.string.sheet_play_cd, what))
            }
        }
        if (state != null) {
            DownloadStatePill(state, Modifier.padding(start = 4.dp, end = 6.dp))
        } else {
            FilledIconButton(onClick = onDownload, modifier = Modifier.padding(start = 4.dp).size(48.dp)) {
                Icon(Icons.Rounded.Download, contentDescription = stringResource(R.string.sheet_download_cd, what))
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
fun TitleGroup.subtitleText(withResolution: Boolean = true): String = when (kind) {
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
        if (files.size > 1) pluralRes(R.plurals.n_versions, files.size, files.size) else if (withResolution) files.first().info.resolution else null
    ).joinToString(" · ")
    MediaKind.OTHER -> formatSize(totalSize)
}
