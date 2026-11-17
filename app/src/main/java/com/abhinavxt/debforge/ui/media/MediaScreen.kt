package com.abhinavxt.debforge.ui.media

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import com.abhinavxt.debforge.ui.player.focusRing
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import com.abhinavxt.debforge.R
import com.abhinavxt.debforge.data.local.DownloadEntity
import com.abhinavxt.debforge.domain.DownloadState
import com.abhinavxt.debforge.domain.FileKind
import com.abhinavxt.debforge.domain.ProviderId
import com.abhinavxt.debforge.domain.SortOrder
import com.abhinavxt.debforge.download.LocalFiles
import com.abhinavxt.debforge.download.artworkFile
import com.abhinavxt.debforge.player.PlayerActivity
import com.abhinavxt.debforge.ui.browse.formatSize
import com.abhinavxt.debforge.ui.components.BannerCard
import com.abhinavxt.debforge.ui.components.GeneratedArt
import com.abhinavxt.debforge.ui.components.HeaderNavActions
import com.abhinavxt.debforge.ui.components.MessageState
import com.abhinavxt.debforge.ui.components.ScreenHeader
import com.abhinavxt.debforge.ui.components.SearchPill
import com.abhinavxt.debforge.ui.theme.DebforgeTheme

/** The Media tab: everything downloaded, split into Video, Audio and Files. */
@Composable
fun MediaScreen(
    playingId: String?,
    onPlayAudio: (queue: List<DownloadEntity>, index: Int) -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenYouTube: () -> Unit,
    viewModel: MediaViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val watch by viewModel.watch.collectAsStateWithLifecycle()
    val internalPlayer by viewModel.useInternalPlayer.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val msgMissing = stringResource(R.string.dl_file_missing)
    val msgNoApp = stringResource(R.string.media_err_no_app)
    val tell: (String) -> Unit = { m -> scope.launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(m) } }
    LaunchedEffect(Unit) {
        viewModel.playRequests.collect { context.startActivity(PlayerActivity.intent(context, it)) }
    }
    LaunchedEffect(Unit) {
        viewModel.messages.collect { tell(msgMissing) }
    }
    val openExternal: (DownloadEntity) -> Unit = { e ->
        val path = e.finalFilePath
        if (path == null || !LocalFiles.exists(path)) tell(msgMissing)
        else {
            val intent = LocalFiles.openIntent(context, path)
            if (intent == null) tell(msgMissing)
            else runCatching { context.startActivity(intent) }.onFailure { tell(msgNoApp) }
        }
    }
    val openVideo: (DownloadEntity) -> Unit = { e -> if (viewModel.useInternalPlayer.value) viewModel.play(e) else openExternal(e) }
    val openFile = openExternal
    Box(Modifier.fillMaxSize()) {
    MediaContent(
        state = state,
        watch = watch,
        internalPlayer = internalPlayer,
        playingId = playingId,
        onKind = viewModel::setKind,
        onQuery = viewModel::setQuery,
        onSort = viewModel::setSort,
        onToggle = viewModel::toggleSelected,
        onSelectAll = viewModel::selectAll,
        onClearSelection = viewModel::clearSelection,
        onOpenVideo = openVideo,
        onOpenFile = openFile,
        onPlayAudio = onPlayAudio,
        onDelete = { ids ->
            viewModel.delete(ids) { n ->
                Toast.makeText(context, context.resources.getQuantityString(R.plurals.msg_delete_failed_n, n, n), Toast.LENGTH_LONG).show()
            }
        },
        onOpenDownloads = onOpenDownloads,
        onOpenSettings = onOpenSettings,
        onOpenYouTube = onOpenYouTube,
        lists = AudioListActions(
            onPick = viewModel::selectAudioList,
            onToggleFavourite = viewModel::toggleFavourite,
            onCreate = { n, ids -> viewModel.createPlaylist(n, ids) },
            onRename = viewModel::renamePlaylist,
            onDelete = viewModel::deletePlaylist,
            onAddTo = viewModel::addToPlaylist,
            onRemoveFrom = viewModel::removeFromPlaylist
        )
    )
    SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MediaContent(
    state: MediaUiState,
    playingId: String?,
    onKind: (FileKind) -> Unit,
    onQuery: (String) -> Unit,
    onSort: (SortOrder) -> Unit,
    onToggle: (String) -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onOpenVideo: (DownloadEntity) -> Unit,
    onOpenFile: (DownloadEntity) -> Unit,
    onPlayAudio: (List<DownloadEntity>, Int) -> Unit,
    onDelete: (List<String>) -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenYouTube: () -> Unit,
    lists: AudioListActions = AudioListActions(),
    watch: Map<String, com.abhinavxt.debforge.data.playback.PlaybackEntity>? = null,
    internalPlayer: Boolean = true
) {
    val cs = MaterialTheme.colorScheme
    val selecting = state.selected.isNotEmpty()
    var confirm by remember { mutableStateOf<List<DownloadEntity>?>(null) }
    BackHandler(selecting, onClearSelection)
    var addTo by remember { mutableStateOf<List<String>?>(null) }
    var nameFor by remember { mutableStateOf<Pair<String?, List<String>>?>(null) } // (playlist id when renaming, tracks to add when creating)
    var deleteList by remember { mutableStateOf<com.abhinavxt.debforge.data.playback.Playlist?>(null) }
    val pickedList = state.lists.playlists.firstOrNull { it.id == state.audioList }

    Column(Modifier.fillMaxSize()) {
        if (selecting) {
            ScreenHeader(stringResource(R.string.media_selected_count, state.selected.size)) {
                IconButton(onClick = onClearSelection) { Icon(Icons.Rounded.Close, stringResource(R.string.media_close_selection)) }
                IconButton(onClick = onSelectAll) { Icon(Icons.Rounded.SelectAll, stringResource(R.string.media_select_all)) }
                if (state.kind == FileKind.Audio) {
                    IconButton(onClick = {
                        val queue = state.current.filter { it.id in state.selected }
                        onClearSelection(); onPlayAudio(queue, 0)
                    }) { Icon(Icons.Rounded.PlayArrow, stringResource(R.string.action_play)) }
                    IconButton(onClick = { addTo = state.current.filter { it.id in state.selected }.map { it.id } }) {
                        Icon(Icons.Rounded.PlaylistAdd, stringResource(R.string.media_add_to_playlist))
                    }
                }
                IconButton(onClick = { confirm = state.current.filter { it.id in state.selected } }) {
                    Icon(Icons.Rounded.Delete, stringResource(R.string.media_delete), tint = cs.error)
                }
            }
        } else {
            ScreenHeader(stringResource(R.string.media_title)) {
                HeaderNavActions(
                    onOpenSettings,
                    onOpenDownloads = onOpenDownloads,
                    activeCount = state.active?.count ?: 0,
                    downloadsDescription = state.active?.let { pluralStringResource(R.plurals.media_cd_downloads_active, it.count, it.count) }
                        ?: stringResource(R.string.media_cd_downloads),
                    settingsDescription = stringResource(R.string.media_cd_settings)
                )
            }
        }

        state.active?.let {
            BannerCard(
                icon = Icons.Rounded.Download,
                text = stringResource(R.string.media_downloading, it.count, it.percent),
                container = cs.secondaryContainer,
                onContainer = cs.onSecondaryContainer,
                modifier = Modifier.clip(MaterialTheme.shapes.large).combinedClickableSimple(onOpenDownloads)
            )
        }

        if (state.totalCount == 0) {
            MessageState(
                icon = Icons.Rounded.Download,
                title = stringResource(R.string.media_empty_title),
                body = stringResource(R.string.media_empty_body),
                action = { Button(onClick = onOpenYouTube) { Text(stringResource(R.string.media_open_youtube)) } }
            )
        } else {
            val tabs = listOf(
                FileKind.Video to R.string.media_tab_video_count,
                FileKind.Audio to R.string.media_tab_audio_count,
                FileKind.File to R.string.media_tab_files_count
            )
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                tabs.forEachIndexed { i, (kind, label) ->
                    SegmentedButton(
                        selected = state.kind == kind,
                        onClick = { onKind(kind) },
                        shape = SegmentedButtonDefaults.itemShape(i, tabs.size),
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) { Text(stringResource(label, state.count(kind)), maxLines = 1) }
                }
            }
            Row(Modifier.padding(start = 16.dp, end = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                SearchPill(
                    query = state.query,
                    onQuery = onQuery,
                    placeholder = stringResource(R.string.media_search_hint),
                    clearDescription = stringResource(R.string.media_search_clear),
                    modifier = Modifier.weight(1f)
                )
                SortMenu(state.sort, onSort)
            }

            if (state.kind == FileKind.Audio) {
                // The options button stays outside the scrolling chips, so a long row cannot push it off screen.
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.weight(1f).horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterChip(state.audioList == null, { lists.onPick(null) }, { Text(stringResource(R.string.search_kind_all)) })
                    FilterChip(state.audioList == "fav", { lists.onPick("fav") }, { Text(stringResource(R.string.media_list_favourites)) })
                    state.lists.playlists.forEach { p ->
                        FilterChip(state.audioList == p.id, { lists.onPick(p.id) }, { Text(p.name, maxLines = 1, overflow = TextOverflow.Ellipsis) })
                    }
                    FilterChip(
                        false, { nameFor = null to emptyList() }, { Text(stringResource(R.string.media_new_playlist)) },
                        leadingIcon = { Icon(Icons.Rounded.Add, null) }
                    )
                }
                    if (pickedList != null) {
                        var menu by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.media_playlist_options)) }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.media_playlist_rename)) },
                                    onClick = { menu = false; nameFor = pickedList.id to emptyList() }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.media_playlist_delete), color = cs.error) },
                                    onClick = { menu = false; deleteList = pickedList }
                                )
                            }
                        }
                    }
                }
            }

            val items = state.current
            if (items.isEmpty() && state.kind == FileKind.Audio && state.audioList != null && state.query.isBlank()) {
                MessageState(
                    icon = Icons.Rounded.MusicNote,
                    title = stringResource(R.string.media_list_empty_title),
                    body = stringResource(R.string.media_list_empty_body)
                )
            } else if (items.isEmpty()) {
                MessageState(
                    icon = Icons.Rounded.Download,
                    title = stringResource(R.string.media_no_match_title),
                    body = stringResource(R.string.media_no_match_body, state.query)
                )
            } else {
                val min = if (state.kind == FileKind.Video) 168.dp else 340.dp
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(min),
                    modifier = Modifier.fillMaxSize(),
                    // Bottom space keeps the last row clear of the 64dp mini player.
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(if (state.kind == FileKind.Video) 16.dp else 4.dp)
                ) {
                    items(items, key = { it.id }) { e ->
                        val selected = e.id in state.selected
                        val m = Modifier.animateItem()
                        when (state.kind) {
                            FileKind.Video -> VideoTile(e, tileProgress(watch != null, internalPlayer, watch?.get(e.id)), selected, m, onClick = { if (selecting) onToggle(e.id) else onOpenVideo(e) }, onLong = { onToggle(e.id) })
                            FileKind.Audio -> MediaRow(
                                e, FileKind.Audio, selected, e.id == playingId, m,
                                onClick = { if (selecting) onToggle(e.id) else onPlayAudio(items, items.indexOf(e)) },
                                onLong = { onToggle(e.id) },
                                onPlay = { onPlayAudio(items, items.indexOf(e)) },
                                onDelete = { confirm = listOf(e) },
                                favourite = e.id in state.lists.favourites,
                                onFavourite = { lists.onToggleFavourite(e.id) },
                                onAddToPlaylist = { addTo = listOf(e.id) },
                                removeFrom = pickedList?.let { p -> p.name to { lists.onRemoveFrom(p.id, e.id) } }
                            )
                            FileKind.File -> MediaRow(
                                e, FileKind.File, selected, false, m,
                                onClick = { if (selecting) onToggle(e.id) else onOpenFile(e) },
                                onLong = { onToggle(e.id) },
                                onPlay = null,
                                onDelete = { confirm = listOf(e) }
                            )
                        }
                    }
                }
            }
        }
    }

    addTo?.let { ids ->
        AddToPlaylistDialog(
            state.lists.playlists,
            onPick = { lists.onAddTo(it.id, ids); onClearSelection() },
            onNew = { nameFor = null to ids },
            onDismiss = { addTo = null }
        )
    }
    nameFor?.let { (renaming, ids) ->
        PlaylistNameDialog(
            title = stringResource(if (renaming == null) R.string.media_new_playlist else R.string.media_playlist_rename),
            initial = state.lists.playlists.firstOrNull { it.id == renaming }?.name.orEmpty(),
            onSave = { n -> if (renaming == null) { lists.onCreate(n, ids); if (ids.isNotEmpty()) onClearSelection() } else lists.onRename(renaming, n) },
            onDismiss = { nameFor = null }
        )
    }
    deleteList?.let { p ->
        AlertDialog(
            onDismissRequest = { deleteList = null },
            icon = { Icon(Icons.Rounded.DeleteOutline, null, tint = cs.error) },
            title = { Text(stringResource(R.string.media_playlist_delete)) },
            text = { Text(stringResource(R.string.media_playlist_delete_body, p.name)) },
            confirmButton = {
                TextButton(onClick = { deleteList = null; lists.onDelete(p.id) }) { Text(stringResource(R.string.media_delete), color = cs.error) }
            },
            dismissButton = { TextButton(onClick = { deleteList = null }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }

    confirm?.let { list ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            icon = { Icon(Icons.Rounded.DeleteOutline, null, tint = cs.error) },
            title = { Text(stringResource(R.string.media_delete_selected_title)) },
            text = {
                Text(
                    if (list.size == 1) stringResource(R.string.media_delete_one_body, list[0].displayTitle(), list[0].formatLabel())
                    else pluralStringResource(R.plurals.media_delete_selected_body, list.size, list.size)
                )
            },
            confirmButton = {
                TextButton(onClick = { confirm = null; onDelete(list.map { it.id }) }) {
                    Text(stringResource(R.string.media_delete), color = cs.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableSimple(onClick: () -> Unit) = this.combinedClickable(onClick = onClick)

@Composable
private fun SortMenu(sort: SortOrder, onSort: (SortOrder) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.AutoMirrored.Rounded.Sort, stringResource(R.string.media_sort)) }
        DropdownMenu(open, { open = false }) {
            listOf(
                SortOrder.DATE_DESC to R.string.sort_newest, SortOrder.DATE_ASC to R.string.sort_oldest,
                SortOrder.NAME_ASC to R.string.sort_name_az, SortOrder.NAME_DESC to R.string.sort_name_za,
                SortOrder.SIZE_DESC to R.string.sort_largest, SortOrder.SIZE_ASC to R.string.sort_smallest
            ).forEach { (o, label) ->
                DropdownMenuItem(
                    text = { Text(stringResource(label), color = if (o == sort) MaterialTheme.colorScheme.primary else Color.Unspecified) },
                    onClick = { open = false; onSort(o) }
                )
            }
        }
    }
}

/** Clickable + long-clickable with a primary border when focused (D-pad) or selected. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Modifier.mediaClickable(
    shape: androidx.compose.ui.graphics.Shape,
    selected: Boolean,
    onClick: () -> Unit,
    onLong: () -> Unit
): Modifier {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    return this
        .clip(shape)
        .then(if (focused || selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape) else Modifier)
        .semantics { this.selected = selected }
        .combinedClickable(interactionSource = source, indication = LocalIndication.current, onClick = onClick, onLongClick = onLong)
}

/** Artwork file if the download has one, else generated art. */
@Composable
private fun Art(e: DownloadEntity, modifier: Modifier, tag: String) {
    val context = LocalContext.current
    val file = remember(e.id) { artworkFile(context, e.id).takeIf { it.exists() } }
    if (file != null) AsyncImage(file, null, modifier, contentScale = ContentScale.Crop)
    else GeneratedArt(e.displayTitle(), tag, modifier, showTitle = false)
}

@Composable
private fun VideoTile(e: DownloadEntity, progress: WatchProgress, selected: Boolean, modifier: Modifier, onClick: () -> Unit, onLong: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    val sub = listOfNotNull(e.formatLabel().takeIf { it.isNotEmpty() }, formatSize(e.filesize).takeIf { e.filesize > 0 }).joinToString(" · ")
    val channel = e.artist?.takeIf { it.isNotBlank() }
    val stateText = when (progress) {
        WatchProgress.Unplayed -> stringResource(R.string.media_unplayed)
        is WatchProgress.InProgress -> stringResource(R.string.media_watched_percent, progress.percent)
        WatchProgress.Finished -> null
    }
    Column(modifier.mediaClickable(shape, selected, onClick, onLong).padding(4.dp).then(if (stateText != null) Modifier.semantics { stateDescription = stateText } else Modifier)) {
        Box {
            Art(e, Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(shape), e.filename.substringAfterLast('.', "").uppercase())
            Icon(
                Icons.Rounded.PlayCircle, null, tint = cs.onSurface.copy(alpha = 0.85f),
                modifier = Modifier.align(Alignment.Center).size(40.dp)
            )
            // Bottom edge for progress, top-end corner for "new": clear of the centre play icon and the top-start checkmark.
            when (progress) {
                is WatchProgress.InProgress -> androidx.compose.material3.LinearProgressIndicator(
                    progress = { progress.fraction },
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp).clip(RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp)).clearAndSetSemantics {},
                    trackColor = cs.surface.copy(alpha = 0.6f),
                    gapSize = 0.dp,
                    drawStopIndicator = {}
                )
                WatchProgress.Unplayed -> Box(
                    Modifier.align(Alignment.TopEnd).padding(8.dp).size(12.dp)
                        .background(cs.primary, androidx.compose.foundation.shape.CircleShape)
                        .border(2.dp, cs.surface, androidx.compose.foundation.shape.CircleShape)
                )
                WatchProgress.Finished -> Unit
            }
            if (selected) Icon(Icons.Rounded.CheckCircle, null, tint = cs.primary, modifier = Modifier.align(Alignment.TopStart).padding(8.dp).size(24.dp).background(cs.surface, androidx.compose.foundation.shape.CircleShape))
        }
        Text(e.displayTitle(), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp))
        if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 4.dp).padding(bottom = if (channel == null) 4.dp else 0.dp))
        if (channel != null) Text(channel, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 4.dp).padding(bottom = 4.dp))
    }
}

@Composable
private fun MediaRow(
    e: DownloadEntity,
    kind: FileKind,
    selected: Boolean,
    playing: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
    onLong: () -> Unit,
    onPlay: (() -> Unit)?,
    onDelete: () -> Unit,
    favourite: Boolean = false,
    onFavourite: (() -> Unit)? = null,
    onAddToPlaylist: (() -> Unit)? = null,
    removeFrom: Pair<String, () -> Unit>? = null
) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    var menu by remember { mutableStateOf(false) }
    val rowFocus = remember { FocusRequester() }
    val menuFocus = remember { FocusRequester() }
    val sub = listOfNotNull(
        e.artist?.takeIf { kind == FileKind.Audio && it.isNotBlank() },
        e.formatLabel().takeIf { kind == FileKind.Audio && it.isNotEmpty() },
        formatSize(e.filesize).takeIf { e.filesize > 0 }
    ).joinToString(" · ")
    Row(
        modifier.fillMaxWidth()
            .background(if (selected || playing) cs.primaryContainer.copy(alpha = if (selected) 0.6f else 0.3f) else Color.Transparent, shape)
            .focusRequester(rowFocus)
            .focusProperties { right = menuFocus }
            .mediaClickable(shape, selected, onClick, onLong)
            .padding(start = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val artShape = RoundedCornerShape(12.dp)
        Box(Modifier.size(56.dp).clip(artShape), contentAlignment = Alignment.Center) {
            if (kind == FileKind.Audio) {
                Art(e, Modifier.fillMaxSize(), "")
            } else {
                Box(Modifier.fillMaxSize().background(cs.surfaceContainerHigh), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.InsertDriveFile, null, tint = cs.onSurfaceVariant)
                }
            }
            if (playing) Box(Modifier.fillMaxSize().background(cs.primary.copy(alpha = 0.7f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.GraphicEq, stringResource(R.string.media_playing), tint = cs.onPrimary)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (kind == FileKind.File) e.filename else e.displayTitle(),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (playing) cs.primary else cs.onSurface,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)
                )
                if (favourite) Icon(
                    Icons.Rounded.Favorite, stringResource(R.string.media_favourite),
                    tint = cs.primary, modifier = Modifier.padding(start = 6.dp).size(16.dp)
                )
            }
            if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box {
            val menuSource = remember { MutableInteractionSource() }
            IconButton(
                onClick = { menu = true },
                modifier = Modifier.focusRequester(menuFocus).focusProperties { left = rowFocus }.focusRing(menuSource, CircleShape),
                interactionSource = menuSource
            ) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.media_more)) }
            DropdownMenu(menu, { menu = false }) {
                if (onPlay != null) DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_play)) },
                    leadingIcon = { Icon(Icons.Rounded.MusicNote, null) },
                    onClick = { menu = false; onPlay() }
                )
                if (onFavourite != null) DropdownMenuItem(
                    text = { Text(stringResource(if (favourite) R.string.media_fav_remove else R.string.media_fav_add)) },
                    leadingIcon = { Icon(if (favourite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, null) },
                    onClick = { menu = false; onFavourite() }
                )
                if (onAddToPlaylist != null) DropdownMenuItem(
                    text = { Text(stringResource(R.string.media_add_to_playlist_menu)) },
                    leadingIcon = { Icon(Icons.Rounded.PlaylistAdd, null) },
                    onClick = { menu = false; onAddToPlaylist() }
                )
                if (removeFrom != null) DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_remove_from, removeFrom.first)) },
                    leadingIcon = { Icon(Icons.Rounded.Close, null) },
                    onClick = { menu = false; removeFrom.second() }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.media_delete), color = cs.error) },
                    leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null, tint = cs.error) },
                    onClick = { menu = false; onDelete() }
                )
            }
        }
    }
}

// --- previews ---------------------------------------------------------------

private fun fake(id: String, name: String, size: Long, title: String? = null, artist: String? = null) = DownloadEntity(
    id = id, provider = ProviderId.REAL_DEBRID, filename = name, sourceRef = "", downloadUrl = "", host = "",
    filesize = size, chunksAllowed = 1, state = DownloadState.COMPLETED, title = title, artist = artist
)

private val previewRows = listOf(
    fake("1", "Sintel.2010.1080p.BluRay.mkv", 145_000_000),
    fake("2", "Big.Buck.Bunny.720p.mp4", 98_000_000),
    fake("3", "a.m4a", 6_400_000, "Night Drive", "Kavinsky"),
    fake("4", "b.opus", 4_100_000, "Slow Morning", "Ambient Club"),
    fake("5", "manual.pdf", 2_300_000)
)

private fun previewState(kind: FileKind, empty: Boolean = false, query: String = "") = MediaUiState(
    shown = if (empty) emptyMap() else previewRows.groupBy { com.abhinavxt.debforge.domain.kindOf(it.filename) },
    totalCount = if (empty) 0 else previewRows.size,
    kind = kind, query = query, active = ActiveSummary(2, 34)
)

@Composable
private fun PreviewHost(s: MediaUiState, playingId: String? = null) = DebforgeTheme {
    MediaContent(s, playingId, {}, {}, {}, {}, {}, {}, {}, {}, { _, _ -> }, {}, {}, {}, {})
}

@Preview(showBackground = true, heightDp = 700) @Composable private fun VideoPreview() = PreviewHost(previewState(FileKind.Video))
@Preview(showBackground = true, heightDp = 700) @Composable private fun AudioPreview() = PreviewHost(previewState(FileKind.Audio), "3")
@Preview(showBackground = true, heightDp = 700) @Composable private fun FilesPreview() = PreviewHost(previewState(FileKind.File))
@Preview(showBackground = true, heightDp = 700) @Composable private fun EmptyPreview() = PreviewHost(previewState(FileKind.Video, empty = true))
