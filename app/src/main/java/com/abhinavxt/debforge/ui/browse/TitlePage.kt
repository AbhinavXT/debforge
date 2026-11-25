package com.abhinavxt.debforge.ui.browse

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.NotificationAdd
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.abhinavxt.debforge.R
import com.abhinavxt.debforge.data.metadata.MediaMeta
import com.abhinavxt.debforge.data.playback.PlaybackEntity
import com.abhinavxt.debforge.domain.DownloadItem
import com.abhinavxt.debforge.domain.DownloadState
import com.abhinavxt.debforge.domain.MediaKind
import com.abhinavxt.debforge.domain.ReleaseInfo
import com.abhinavxt.debforge.domain.Resume
import com.abhinavxt.debforge.domain.TraktMatch
import com.abhinavxt.debforge.ui.components.GeneratedArt
import com.abhinavxt.debforge.ui.pluralRes

/** What the main button plays: [file], continuing where it stopped when [resume]. */
internal data class PlayTarget(val file: ParsedItem, val resume: Boolean)

/** Watched in DebForge's player or on Trakt (same rule as the file rows). */
internal fun isWatched(info: ReleaseInfo, entry: PlaybackEntity?, traktWatched: Set<String>): Boolean =
    entry?.finished == true ||
        (traktWatched.isNotEmpty() && TraktMatch.keyOf(info) in traktWatched &&
            (entry == null || entry.positionMs < Resume.MIN_RESUME_MS))

/**
 * The file the main button plays: the one started and left (latest first), else the first
 * unwatched in watch order, else the first. [files] must be in watch order.
 */
internal fun playTarget(files: List<ParsedItem>, playback: Map<String, PlaybackEntity>, traktWatched: Set<String>): PlayTarget? {
    val videos = files.filter { it.info.isVideo }
    if (videos.isEmpty()) return null
    val resumable = videos.mapNotNull { f ->
        playback[f.item.id]
            ?.takeIf { !it.finished && it.positionMs >= Resume.MIN_RESUME_MS && !Resume.isFinished(it.positionMs, it.durationMs) }
            ?.let { f to it.updatedAt }
    }.maxByOrNull { it.second }
    if (resumable != null) return PlayTarget(resumable.first, true)
    val next = videos.firstOrNull { !isWatched(it.info, playback[it.item.id], traktWatched) } ?: videos.first()
    return PlayTarget(next, false)
}

/**
 * Full page for one title (replaces the old bottom sheet): backdrop, overview, Play / Resume,
 * "Download all", Follow, season chips and every file with its own actions.
 */
@Composable
fun TitlePage(
    group: TitleGroup,
    meta: MediaMeta?,
    downloadStates: Map<String, DownloadState>,
    onDownload: (List<DownloadItem>) -> Unit,
    onPlay: (ParsedItem) -> Unit,
    zipFor: (DownloadItem, Int) -> DownloadItem?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    /** Null when the service can't remove these files. */
    onRemove: (() -> Unit)? = null,
    removeLabel: String = "",
    /** Shows only: followed right now? Null hides the button (movies, other files). */
    following: Boolean? = null,
    onToggleFollow: () -> Unit = {},
    /** Saved positions by item id: progress bars and "Watched". */
    playback: Map<String, PlaybackEntity> = emptyMap(),
    /** Watched on Trakt ([TraktMatch] keys). */
    traktWatched: Set<String> = emptySet()
) {
    BackHandler(onBack = onClose)
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
    val target = remember(files, playback, traktWatched) { playTarget(files, playback, traktWatched) }

    val seasons = remember(files) { files.mapNotNull { it.info.season }.distinct().sorted() }
    var chosenSeason by rememberSaveable(group.key) { mutableStateOf<Int?>(null) }
    val season = if (group.kind == MediaKind.SHOW) chosenSeason?.takeIf { it in seasons } ?: target?.file?.info?.season ?: seasons.firstOrNull() else null
    // Files with no season number always show, after the chosen season's.
    val listed = if (season == null) files else files.filter { it.info.season == season || it.info.season == null }

    // D-pad: land on the main button (touch users get no stray focus highlight).
    val primaryFocus = remember { FocusRequester() }
    val keyboard = LocalInputModeManager.current.inputMode == InputMode.Keyboard
    LaunchedEffect(group.key) { if (keyboard) runCatching { primaryFocus.requestFocus() } }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Box(Modifier.fillMaxSize()) {
            LazyColumn(contentPadding = PaddingValues(bottom = 32.dp), modifier = Modifier.fillMaxSize()) {
                item(key = "fixed:backdrop") { Backdrop(group, meta) }
                item(key = "fixed:header") {
                    Column(Modifier.padding(horizontal = 20.dp)) {
                        Text(meta?.title ?: group.title, style = MaterialTheme.typography.headlineMedium)
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
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(16.dp))
                        if (target != null) {
                            val what = target.file.info.episodeLabel.takeIf { group.kind == MediaKind.SHOW }
                            Button(
                                onClick = { onPlay(target.file) },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).focusRequester(primaryFocus)
                            ) {
                                Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(22.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    when {
                                        what == null -> stringResource(if (target.resume) R.string.title_resume else R.string.action_play)
                                        target.resume -> stringResource(R.string.title_resume_episode, what)
                                        else -> stringResource(R.string.title_play_episode, what)
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val downloadContent: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
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
                                        Text(formatSize(pending.sumOf { it.filesize }), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                                    }
                                }
                            }
                            val downloadModifier = Modifier.weight(1f)
                            if (target != null) {
                                FilledTonalButton(onClick = { onDownload(pending) }, enabled = pending.isNotEmpty(), modifier = downloadModifier, content = downloadContent)
                            } else {
                                Button(
                                    onClick = { onDownload(pending) },
                                    enabled = pending.isNotEmpty(),
                                    modifier = downloadModifier.focusRequester(primaryFocus),
                                    content = downloadContent
                                )
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
                                    OutlinedIconButton(onClick = onToggleFollow, modifier = Modifier.size(48.dp), content = followIcon)
                                }
                            }
                        }
                        if (zips.size == 1) {
                            val zip = zips.first()
                            val zipState = downloadStates[zip.id]
                            if (zipState != null) {
                                Box(Modifier.fillMaxWidth().height(40.dp), contentAlignment = Alignment.Center) { DownloadStatePill(zipState) }
                            } else {
                                OutlinedButton(onClick = { onDownload(listOf(zip)) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                                    Icon(Icons.Rounded.FolderZip, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(stringResource(R.string.sheet_zip_download), maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                                    Text(zip.filename, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                    if (zipState != null) {
                                        DownloadStatePill(zipState)
                                    } else {
                                        OutlinedButton(onClick = { onDownload(listOf(zip)) }) { Text(stringResource(R.string.sheet_zip_download), maxLines = 1) }
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
                meta?.overview?.let { overview -> item(key = "fixed:overview") { Overview(overview) } }
                item(key = "fixed:divider") { Spacer(Modifier.height(4.dp)) }

                if (season != null) {
                    item(key = "fixed:seasons") {
                        if (seasons.size > 1) {
                            Row(
                                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                seasons.forEach { s ->
                                    FilterChip(
                                        selected = s == season,
                                        onClick = { chosenSeason = s },
                                        label = { Text(stringResource(R.string.sheet_season, s)) }
                                    )
                                }
                            }
                        }
                        val seasonPending = files.filter { it.info.season == season && downloadStates[it.item.id] == null }.map { it.item }
                        Row(
                            Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp),
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
                // Several files for one episode (different releases): number them so they can be told apart.
                val versionsByEpisode = files
                    .filter { it.info.episodeLabel != null }
                    .groupBy { it.info.season to it.info.episodeLabel }
                    .filterValues { it.size > 1 }
                items(listed, key = { "file:" + it.item.id }) { f ->
                    val versions = versionsByEpisode[f.info.season to f.info.episodeLabel]
                    FileRow(
                        file = f,
                        version = versions?.let { it.indexOf(f) + 1 to it.size },
                        state = downloadStates[f.item.id],
                        onDownload = { onDownload(listOf(f.item)) },
                        onPlay = { onPlay(f) },
                        playback = playback[f.item.id],
                        watchedOnTrakt = traktWatched.isNotEmpty() && TraktMatch.keyOf(f.info) in traktWatched
                    )
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
            BackButton(onClose, Modifier.align(Alignment.TopStart))
        }
    }
}

@Composable
private fun BackButton(onClose: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(
        onClick = onClose,
        modifier = modifier
            .statusBarsPadding()
            .padding(8.dp)
            .size(48.dp)
            .background(Color.Black.copy(alpha = 0.45f), CircleShape)
    ) {
        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.shell_cd_back), tint = Color.White)
    }
}

/** Meta backdrop; else the poster, blurred; else generated art. Fades into the page. */
@Composable
private fun Backdrop(group: TitleGroup, meta: MediaMeta?) {
    val url = meta?.backdropUrl ?: meta?.posterUrl
    Box(Modifier.fillMaxWidth().height(240.dp).clip(androidx.compose.ui.graphics.RectangleShape)) {
        GeneratedArt(title = group.title, tag = "", showTitle = false, modifier = Modifier.fillMaxSize())
        if (url != null) {
            val context = LocalContext.current
            val request = remember(url, context) { ImageRequest.Builder(context).data(url).crossfade(true).build() }
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                // Blur is a no-op below Android 12, where the cropped poster just shows soft.
                modifier = Modifier.fillMaxSize().then(if (meta?.backdropUrl == null) Modifier.blur(20.dp) else Modifier)
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color.Transparent, MaterialTheme.colorScheme.surface))
            )
        )
    }
}
