package com.abhinavxt.debforge.ui.browse

import androidx.compose.ui.res.stringResource
import com.abhinavxt.debforge.R
import com.abhinavxt.debforge.ui.update.UpdateBanner
import com.abhinavxt.debforge.ui.pluralRes
import android.content.Intent
import androidx.activity.compose.BackHandler
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.material3.Checkbox
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.RemoveDone
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.abhinavxt.debforge.ui.components.BannerCard
import com.abhinavxt.debforge.ui.posters.PosterSetupSheet
import com.abhinavxt.debforge.domain.MediaKind
import com.abhinavxt.debforge.ui.components.IconBadge
import com.abhinavxt.debforge.ui.components.MessageState
import com.abhinavxt.debforge.ui.components.ScreenHeader
import com.abhinavxt.debforge.ui.components.SearchPill
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import kotlinx.coroutines.launch
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.abhinavxt.debforge.domain.DownloadItem
import com.abhinavxt.debforge.domain.DownloadState
import com.abhinavxt.debforge.domain.ExtraFiles
import com.abhinavxt.debforge.domain.ReleaseInfo
import com.abhinavxt.debforge.domain.ReleaseNameParser
import com.abhinavxt.debforge.domain.SortOrder
import com.abhinavxt.debforge.download.StorageAccess

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseScreen(
    onAdd: () -> Unit = {},
    /** Opens the Add dialog pre-filled (a link offered from the clipboard). */
    onAddText: (String) -> Unit = {},
    viewModel: BrowseViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val downloadStates by viewModel.downloadStates.collectAsStateWithLifecycle()
    val meta by viewModel.meta.collectAsStateWithLifecycle()
    val processing by viewModel.processing.collectAsStateWithLifecycle()
    val watchedJobs by viewModel.watchedJobs.collectAsStateWithLifecycle()
    val showPosterPrompt by viewModel.showPosterPrompt.collectAsStateWithLifecycle()
    val spaceWarning by viewModel.spaceWarning.collectAsStateWithLifecycle()
    val followed by viewModel.followed.collectAsStateWithLifecycle()
    val continueWatching by viewModel.continueWatching.collectAsStateWithLifecycle()
    val playback by viewModel.playback.collectAsStateWithLifecycle()
    val traktWatched by viewModel.traktWatched.collectAsStateWithLifecycle()
    var posterSetupOpen by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val uriHandler = LocalUriHandler.current
    // Multi-select: ids of selected files (posters select a whole title). Empty = not selecting.
    var selected by remember { mutableStateOf(setOf<String>()) }
    var selectionMenu by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val isTv = remember(context) {
        (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK) ==
            android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
    }
    var pendingPermission by remember { mutableStateOf<List<DownloadItem>?>(null) }
    var openGroupKey by remember { mutableStateOf<String?>(null) }
    var openFile by remember { mutableStateOf<ParsedItem?>(null) }
    // Pending "remove from service" confirmation: what to remove and its display name.
    var confirmRemove by remember { mutableStateOf<RemoveRequest?>(null) }

    LaunchedEffect(Unit) {
        viewModel.events.collect { message -> snackbar.showSnackbar(message) }
    }

    // A magnet or .torrent link was just copied: offer to add it.
    val clipScope = rememberCoroutineScope()
    val clipMessage = stringResource(R.string.clipboard_offer)
    val clipAction = stringResource(R.string.action_add)
    ClipboardOfferEffect(onOffer = { links ->
        clipScope.launch {
            val result = snackbar.showSnackbar(
                message = clipMessage,
                actionLabel = clipAction,
                withDismissAction = true,
                duration = SnackbarDuration.Long
            )
            if (result == SnackbarResult.ActionPerformed) onAddText(links)
        }
    })
    LaunchedEffect(Unit) {
        viewModel.playRequests.collect { req ->
            if (viewModel.useInternalPlayer.value) {
                context.startActivity(com.abhinavxt.debforge.player.PlayerActivity.intent(context, req))
            } else if (!context.playExternally(req)) {
                viewModel.reportNoPlayer()
            }
        }
    }
    // Back exits multi-select; switching between posters and the list clears it.
    BackHandler(enabled = selected.isNotEmpty()) { selected = emptySet() }
    LaunchedEffect(state.posterView) { selected = emptySet() }

    // Poll processing jobs / auto-downloads only while the screen is visible.
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.pollWhileVisible() }
    }

    val downloadDir by viewModel.downloadDir.collectAsStateWithLifecycle()

    // "Choose a folder" from the storage dialog: the view-model remembers what
    // was being downloaded (survives rotation) and queues it once picked.
    val pickFolder = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) runCatching { StorageAccess.rememberPickedFolder(context, uri, downloadDir) }
        viewModel.onFolderPicked(uri?.toString())
    }

    fun handleEnqueue(items: List<DownloadItem>) {
        if (items.isEmpty()) return
        if (StorageAccess.canWrite(context, downloadDir)) {
            viewModel.enqueueAll(items)
        } else {
            pendingPermission = items
        }
    }

    // Release-group notes, covers and samples inside torrents are hidden
    // unless the user turned them on in Settings.
    val showExtras by viewModel.showExtraFiles.collectAsStateWithLifecycle()
    val shown: List<DownloadItem> = remember(state.items, showExtras) {
        if (showExtras) state.items else ExtraFiles.hide(state.items)
    }

    // Parse every filename once per list change (cheap, but not per frame).
    val parsed: Map<String, ReleaseInfo> = remember(shown) {
        shown.associate { it.id to ReleaseNameParser.parse(it.filename) }
    }

    val addDescription = stringResource(R.string.browse_add_cd)

    // Everything "Select all" picks: what the current view shows (search included).
    val selectable: List<String> = remember(shown, parsed, state.query, state.sort, state.posterView, state.grouped) {
        if (state.posterView) buildTitleGroups(shown, parsed, state.query, state.sort).flatMap { g -> g.files.map { it.item.id } }
        else buildSections(shown, state.query, state.sort, state.grouped).flatMap { sec -> sec.items.map { it.id } }
    }
    val pickedItems = remember(selected, state.items) { state.items.filter { it.id in selected } }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (selected.isEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = onAdd,
                    icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.action_add)) },
                    modifier = Modifier.semantics { contentDescription = addDescription }
                )
            }
        },
        topBar = {
            if (selected.isNotEmpty()) {
                // Contextual bar while multi-selecting.
                TopAppBar(
                    title = { Text(stringResource(R.string.browse_selected, selected.size)) },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        titleContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        navigationIconContentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    ),
                    navigationIcon = {
                        IconButton(onClick = { selected = emptySet() }) {
                            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.browse_cancel_selection))
                        }
                    },
                    actions = {
                        if (selected.size < selectable.size) {
                            IconButton(onClick = { selected = selectable.toSet() }) {
                                Icon(Icons.Rounded.SelectAll, contentDescription = stringResource(R.string.browse_select_all))
                            }
                        }
                        if (viewModel.canRemove(pickedItems)) {
                            val label = pluralRes(R.plurals.n_files, pickedItems.size, pickedItems.size)
                            IconButton(onClick = { confirmRemove = RemoveRequest(label, items = pickedItems) }) {
                                Icon(Icons.Rounded.DeleteOutline, contentDescription = stringResource(R.string.action_remove))
                            }
                        }
                        Box {
                            IconButton(onClick = { selectionMenu = true }) {
                                Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.browse_more_actions))
                            }
                            DropdownMenu(expanded = selectionMenu, onDismissRequest = { selectionMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.browse_mark_watched)) },
                                    leadingIcon = { Icon(Icons.Rounded.DoneAll, contentDescription = null) },
                                    onClick = {
                                        viewModel.markWatched(pickedItems, watched = true)
                                        selectionMenu = false
                                        selected = emptySet()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.browse_mark_unwatched)) },
                                    leadingIcon = { Icon(Icons.Rounded.RemoveDone, contentDescription = null) },
                                    onClick = {
                                        viewModel.markWatched(pickedItems, watched = false)
                                        selectionMenu = false
                                        selected = emptySet()
                                    }
                                )
                            }
                        }
                        Button(
                            onClick = {
                                val picked = pickedItems.filter { downloadStates[it.id] == null }
                                handleEnqueue(picked)
                                selected = emptySet()
                            },
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.action_download))
                        }
                    }
                )
            } else {
                ScreenHeader(
                    title = stringResource(R.string.tab_library),
                    subtitle = state.providerName.ifEmpty { null }
                ) {
                    // Pull down to refresh everywhere else; a TV remote can't
                    // pull, so it keeps the button.
                    if (isTv) {
                        IconButton(onClick = viewModel::refresh) {
                            Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.action_refresh))
                        }
                    }
                }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            ControlBar(
                query = state.query,
                onQuery = viewModel::onQueryChange,
                sort = state.sort,
                onSort = viewModel::onSortChange,
                posterView = state.posterView,
                onTogglePosters = viewModel::onTogglePosterView,
                grouped = state.grouped,
                onToggleGroup = viewModel::onToggleGroup
            )
            if (state.searchingAll) {
                // Search covers the whole account: show that more is loading.
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.browse_searching_all, state.items.size),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            state.expiryWarning?.let { warning ->
                ExpiryBanner(
                    text = warning,
                    onRenew = { state.renewUrl?.let(uriHandler::openUri) },
                    onDismiss = viewModel::dismissExpiryWarning
                )
            }

            UpdateBanner()

            // Only worth asking when there's a movie or show to put a poster on.
            val hasTitles = remember(parsed) { parsed.values.any { it.kind != MediaKind.OTHER } }
            if (showPosterPrompt && state.posterView && hasTitles) {
                PosterPromptBanner(
                    onSetUp = { posterSetupOpen = true },
                    onDismiss = viewModel::dismissPosterPrompt
                )
            }

            ProcessingStrip(
                providerName = state.providerName,
                jobs = processing,
                isWatched = { "${it.provider.name}|${it.ref}" in watchedJobs },
                onToggleWatch = viewModel::toggleWatch,
                onRemove = { job -> confirmRemove = RemoveRequest(job.name, job = job) }
            )

            // Material3 1.3.0's pull-to-refresh can leave its arrow stuck on
            // screen: a position update from the last drag event can land
            // after the release animation started and cancel it. Once the
            // finger is up and nothing is refreshing, put it away ourselves.
            val pullState = rememberPullToRefreshState()
            var fingerDown by remember { mutableStateOf(false) }
            LaunchedEffect(fingerDown, state.isRefreshing) {
                if (fingerDown || state.isRefreshing) return@LaunchedEffect
                kotlinx.coroutines.delay(PULL_SETTLE_MS)
                if (pullState.distanceFraction > 0f) pullState.animateToHidden()
            }
            PullToRefreshBox(
                isRefreshing = state.isRefreshing,
                onRefresh = viewModel::pullRefresh,
                state = pullState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    // Only watches whether a finger is down; never consumes.
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                fingerDown = event.changes.any { it.pressed }
                            }
                        }
                    }
            ) {
            when {
                state.isInitialLoad -> CenteredSpinner()
                state.error != null && state.items.isEmpty() -> PullableFill {
                    ErrorState(state.error!!) { viewModel.clearError(); viewModel.refresh() }
                }
                state.items.isEmpty() -> PullableFill {
                    EmptyState(
                        providerName = state.providerName,
                        hint = state.emptyHint,
                        onAdd = onAdd
                    )
                }
                state.posterView -> {
                    val groups = remember(shown, parsed, state.query, state.sort) {
                        buildTitleGroups(shown, parsed, state.query, state.sort)
                    }
                    if (groups.isEmpty()) {
                        PullableFill { NoMatches(state.query) }
                    } else {
                        PosterGrid(
                            groups = groups,
                            meta = meta,
                            downloadStates = downloadStates,
                            isAppending = state.isAppending,
                            endReached = state.endReached,
                            onRequestMeta = { viewModel.requestMeta(it.info) },
                            onOpen = { group ->
                                if (selected.isNotEmpty()) {
                                    val ids = group.files.map { it.item.id }
                                    selected = if (ids.all { it in selected }) selected - ids.toSet() else selected + ids
                                } else {
                                    openGroupKey = group.key
                                }
                            },
                            onLoadMore = viewModel::loadMore,
                            selectedKeys = remember(groups, selected) {
                                groups.filter { g -> g.files.isNotEmpty() && g.files.all { it.item.id in selected } }.map { it.key }.toSet()
                            },
                            onLongPress = { group -> selected = selected + group.files.map { it.item.id } },
                            // Not while searching: results come first then.
                            header = if (state.query.isBlank() && continueWatching.isNotEmpty()) {
                                {
                                    ContinueWatchingRow(
                                        entries = continueWatching,
                                        meta = meta,
                                        onRequestMeta = viewModel::requestMeta,
                                        onPlay = viewModel::playSaved,
                                        onRemove = viewModel::forgetPlayback
                                    )
                                }
                            } else null
                        )
                    }
                    // The sheet reads the live group so badges update while open.
                    openGroupKey?.let { key ->
                        // If the group vanished (search changed), just don't show it.
                        val group = groups.firstOrNull { it.key == key }
                        if (group != null) {
                            TitleSheet(
                                group = group,
                                meta = meta[group.info.groupKey],
                                downloadStates = downloadStates,
                                onDownload = ::handleEnqueue,
                                onPlay = { viewModel.play(it.item, it.info.displayTitle) },
                                zipFor = viewModel::zipFor,
                                onDismiss = { openGroupKey = null },
                                onRemove = group.files.map { it.item }.takeIf { viewModel.canRemove(it) }?.let { items ->
                                    { confirmRemove = RemoveRequest(group.title, items = items) }
                                },
                                serviceName = state.providerName,
                                following = group.files.firstOrNull()
                                    ?.takeIf { group.kind == MediaKind.SHOW }
                                    ?.let { "${it.item.provider.name}|${group.info.groupKey}" in followed },
                                onToggleFollow = { viewModel.toggleFollow(group) },
                                playback = playback,
                                traktWatched = traktWatched
                            )
                        }
                    }
                }
                else -> {
                    val sections = remember(shown, state.query, state.sort, state.grouped) {
                        buildSections(shown, state.query, state.sort, state.grouped)
                    }
                    if (sections.sumOf { it.items.size } == 0) {
                        PullableFill { NoMatches(state.query) }
                    } else {
                        SectionList(
                            sections = sections,
                            parsed = parsed,
                            grouped = state.grouped,
                            isAppending = state.isAppending,
                            endReached = state.endReached,
                            downloadStates = downloadStates,
                            onEnqueue = { handleEnqueue(listOf(it)) },
                            selected = selected,
                            onOpen = { item ->
                                if (selected.isNotEmpty()) {
                                    selected = if (item.id in selected) selected - item.id else selected + item.id
                                } else {
                                    parsed[item.id]?.let { openFile = ParsedItem(item, it) }
                                }
                            },
                            onLongPress = { item -> selected = selected + item.id },
                            onLoadMore = viewModel::loadMore
                        )
                    }
                }
            }
            }
        }
    }

    if (posterSetupOpen) {
        PosterSetupSheet(
            onDismiss = { posterSetupOpen = false },
            onSaved = viewModel::onPostersEnabled
        )
    }

    openFile?.let { f ->
        FileInfoDialog(
            file = f,
            state = downloadStates[f.item.id],
            onDownload = { handleEnqueue(listOf(f.item)); openFile = null },
            onPlay = { viewModel.play(f.item, f.info.displayTitle); openFile = null },
            onDismiss = { openFile = null },
            onRemove = listOf(f.item).takeIf { viewModel.canRemove(it) }?.let { items ->
                { confirmRemove = RemoveRequest(f.info.displayTitle, items = items); openFile = null }
            }
        )
    }

    spaceWarning?.let { w ->
        AlertDialog(
            onDismissRequest = viewModel::dismissSpaceWarning,
            icon = { Icon(Icons.Rounded.WarningAmber, contentDescription = null) },
            title = { Text(stringResource(R.string.space_title)) },
            text = {
                Text(stringResource(R.string.space_body, formatSize(w.needed), formatSize(w.available), w.folder))
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmSpaceWarning) { Text(stringResource(R.string.space_queue_anyway)) }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissSpaceWarning) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }

    confirmRemove?.let { req ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            icon = { Icon(Icons.Rounded.DeleteOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(stringResource(R.string.remove_title, state.providerName)) },
            text = { Text(stringResource(R.string.remove_body, req.label, state.providerName)) },
            confirmButton = {
                TextButton(onClick = {
                    req.items?.let(viewModel::removeFromService)
                    req.job?.let(viewModel::removeJob)
                    confirmRemove = null
                    openGroupKey = null
                    if (req.items != null) selected = selected - req.items.map { it.id }.toSet()
                }) { Text(stringResource(R.string.action_remove), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = null }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }

    pendingPermission?.let { items ->
        AllFilesAccessDialog(
            onPickFolder = {
                viewModel.awaitFolderFor(items)
                pendingPermission = null
                pickFolder.launch(null)
            },
            onGrant = {
                if (!StorageAccess.requestAllFiles(context)) {
                    // No such settings screen (e.g. Android TV): use app storage.
                    viewModel.useAppStorage(StorageAccess.appStorageDir(context), items)
                }
                pendingPermission = null
            },
            onUseAppStorage = {
                viewModel.useAppStorage(StorageAccess.appStorageDir(context), items)
                pendingPermission = null
            },
            onDismiss = { pendingPermission = null }
        )
    }
}

// --- top control row ----------------------------------------------------------

@Composable
private fun ControlBar(
    query: String,
    onQuery: (String) -> Unit,
    sort: SortOrder,
    onSort: (SortOrder) -> Unit,
    posterView: Boolean,
    onTogglePosters: () -> Unit,
    grouped: Boolean,
    onToggleGroup: () -> Unit
) {
    Column(modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)) {
        SearchPill(
            query = query,
            onQuery = onQuery,
            placeholder = stringResource(R.string.browse_search_hint),
            clearDescription = stringResource(R.string.action_clear),
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        // Scrolls sideways so long translations never squash the chips.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SortPicker(sort = sort, onSort = onSort)
            FilterChip(
                selected = posterView,
                onClick = onTogglePosters,
                leadingIcon = { Icon(Icons.Rounded.GridView, contentDescription = null, modifier = Modifier.size(18.dp)) },
                label = { Text(stringResource(R.string.browse_posters)) }
            )
            if (!posterView) {
                FilterChip(
                    selected = grouped,
                    onClick = onToggleGroup,
                    leadingIcon = { Icon(Icons.Rounded.Layers, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    label = { Text(stringResource(R.string.browse_group_series)) }
                )
            }
        }
    }
}

@Composable
private fun SortPicker(sort: SortOrder, onSort: (SortOrder) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = false,
            onClick = { expanded = true },
            leadingIcon = { Icon(Icons.AutoMirrored.Rounded.Sort, contentDescription = null, modifier = Modifier.size(18.dp)) },
            trailingIcon = { Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(18.dp)) },
            label = { Text(sort.label()) }
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SortOrder.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label()) },
                    leadingIcon = {
                        if (option == sort) Icon(Icons.Rounded.Check, contentDescription = null)
                    },
                    onClick = { onSort(option); expanded = false }
                )
            }
        }
    }
}

@Composable
private fun SortOrder.label(): String = stringResource(
    when (this) {
        SortOrder.DATE_DESC -> R.string.sort_newest
        SortOrder.DATE_ASC -> R.string.sort_oldest
        SortOrder.NAME_ASC -> R.string.sort_name_az
        SortOrder.NAME_DESC -> R.string.sort_name_za
        SortOrder.SIZE_DESC -> R.string.sort_largest
        SortOrder.SIZE_ASC -> R.string.sort_smallest
    }
)

// --- list view ----------------------------------------------------------------

@Composable
private fun SectionList(
    sections: List<BrowseSection>,
    parsed: Map<String, ReleaseInfo>,
    grouped: Boolean,
    isAppending: Boolean,
    endReached: Boolean,
    downloadStates: Map<String, DownloadState>,
    onEnqueue: (DownloadItem) -> Unit,
    selected: Set<String>,
    onOpen: (DownloadItem) -> Unit,
    onLongPress: (DownloadItem) -> Unit,
    onLoadMore: () -> Unit
) {
    val listState = rememberLazyListState()

    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { lastIndex ->
                val total = listState.layoutInfo.totalItemsCount
                if (total > 0 && lastIndex >= total - 4) onLoadMore()
            }
    }

    @Composable
    fun row(item: DownloadItem, indent: Boolean, prefix: String?) = ItemRow(
        item = item,
        info = parsed[item.id],
        indent = indent,
        secondaryPrefix = prefix,
        downloadState = downloadStates[item.id],
        selectionMode = selected.isNotEmpty(),
        isSelected = item.id in selected,
        onEnqueue = onEnqueue,
        onOpen = onOpen,
        onLongPress = onLongPress
    )

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(top = 8.dp, bottom = 88.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        sections.forEach { section ->
            when (section) {
                is BrowseSection.Show -> if (grouped) {
                    item(key = "show-${section.showKey}") { SectionHeader(section.showDisplay) }
                    items(section.episodes, key = { "${section.showKey}-${it.item.id}" }) { ep ->
                        val label = buildString {
                            if (ep.season != null) append("S").append(ep.season).append(" · ")
                            append("E").append(ep.episode)
                        }
                        row(ep.item, indent = true, prefix = label)
                    }
                } else {
                    items(section.items, key = { it.id }) { item -> row(item, indent = false, prefix = null) }
                }
                is BrowseSection.Loose -> {
                    if (grouped && section.items.isNotEmpty()) {
                        item(key = "loose-header") { SectionHeader(stringResource(R.string.browse_other)) }
                    }
                    items(section.items, key = { it.id }) { item -> row(item, indent = grouped, prefix = null) }
                }
            }
        }
        item("footer") {
            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                when {
                    isAppending -> CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
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
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 16.dp, top = 16.dp, bottom = 6.dp)
    )
}

/**
 * One file. Shows the tidied title ("The Bear · S02E05") and quality instead
 * of the raw release name; tapping opens the raw name + details.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ItemRow(
    item: DownloadItem,
    info: ReleaseInfo?,
    indent: Boolean,
    secondaryPrefix: String?,
    downloadState: DownloadState?,
    selectionMode: Boolean,
    isSelected: Boolean,
    onEnqueue: (DownloadItem) -> Unit,
    onOpen: (DownloadItem) -> Unit,
    onLongPress: (DownloadItem) -> Unit
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = if (indent) 20.dp else 12.dp, end = 12.dp, top = 3.dp, bottom = 3.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(if (isSelected) cs.secondaryContainer else cs.surfaceContainerLow)
            .combinedClickable(onClick = { onOpen(item) }, onLongClick = { onLongPress(item) })
            .padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (selectionMode) {
            Checkbox(checked = isSelected, onCheckedChange = { onOpen(item) })
        } else {
            IconBadge(
                icon = when {
                    secondaryPrefix != null -> Icons.Rounded.Tv
                    info?.isVideo == true -> Icons.Rounded.Movie
                    else -> Icons.Rounded.Description
                },
                container = cs.surfaceContainerHighest,
                tint = cs.primary,
                size = 40.dp
            )
            Spacer(Modifier.width(12.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = info?.displayTitle ?: item.filename,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            val subtitle = listOfNotNull(
                secondaryPrefix,
                info?.qualityLabel?.ifEmpty { null },
                formatSize(item.filesize),
                item.host.ifEmpty { null }
            ).joinToString(" · ")
            Text(
                text = subtitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (selectionMode) {
            // No per-row buttons while selecting.
        } else if (downloadState != null) {
            Spacer(Modifier.width(8.dp))
            DownloadStatePill(downloadState)
        } else {
            FilledTonalIconButton(onClick = { onEnqueue(item) }) {
                Icon(Icons.Rounded.Download, contentDescription = stringResource(R.string.action_download))
            }
        }
    }
}

@Composable
private fun FileInfoDialog(
    file: ParsedItem,
    state: DownloadState?,
    onDownload: () -> Unit,
    onPlay: () -> Unit,
    onDismiss: () -> Unit,
    onRemove: (() -> Unit)? = null
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                if (file.info.isVideo) Icons.Rounded.Movie else Icons.Rounded.Description,
                contentDescription = null
            )
        },
        title = { Text(file.info.displayTitle) },
        text = {
            Column {
                Text(file.item.filename, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    listOfNotNull(
                        file.info.qualityLabel.ifEmpty { null },
                        formatSize(file.item.filesize),
                        file.item.host.ifEmpty { null }
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            if (state == null) {
                TextButton(onClick = onDownload) { Text(stringResource(R.string.action_download)) }
            } else {
                TextButton(onClick = onDismiss, enabled = false) { Text(state.badgeLabel()) }
            }
        },
        dismissButton = {
            Row {
                if (onRemove != null) {
                    TextButton(onClick = onRemove) {
                        Text(stringResource(R.string.action_remove), color = MaterialTheme.colorScheme.error)
                    }
                }
                if (file.info.isVideo) TextButton(onClick = onPlay) { Text(stringResource(R.string.action_play)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
            }
        }
    )
}

// --- empty / loading / error / dialog -----------------------------------------

@Composable
private fun ExpiryBanner(text: String, onRenew: () -> Unit, onDismiss: () -> Unit) {
    val onContainer = MaterialTheme.colorScheme.onErrorContainer
    BannerCard(
        icon = Icons.Rounded.WarningAmber,
        text = text,
        container = MaterialTheme.colorScheme.errorContainer,
        onContainer = onContainer
    ) {
        TextButton(
            onClick = onRenew,
            colors = ButtonDefaults.textButtonColors(contentColor = onContainer)
        ) { Text(stringResource(R.string.action_renew), fontWeight = FontWeight.Bold) }
        IconButton(onClick = onDismiss) {
            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.action_dismiss))
        }
    }
}

/** "Turn on posters" nudge while no TMDB key is set. Closing it is remembered. */
@Composable
private fun PosterPromptBanner(onSetUp: () -> Unit, onDismiss: () -> Unit) {
    val onContainer = MaterialTheme.colorScheme.onSecondaryContainer
    BannerCard(
        icon = Icons.Rounded.Image,
        text = stringResource(R.string.poster_banner),
        container = MaterialTheme.colorScheme.secondaryContainer,
        onContainer = onContainer
    ) {
        TextButton(
            onClick = onSetUp,
            colors = ButtonDefaults.textButtonColors(contentColor = onContainer)
        ) { Text(stringResource(R.string.poster_banner_action), fontWeight = FontWeight.Bold) }
        IconButton(onClick = onDismiss) {
            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.action_dismiss))
        }
    }
}

/**
 * Screens with nothing to scroll (empty, error, no matches) inside a
 * full-height scroll area, so pull to refresh works on them too.
 */
@Composable
private fun PullableFill(content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val height = maxHeight
        Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Box(Modifier.fillMaxWidth().height(height)) { content() }
        }
    }
}

@Composable
private fun CenteredSpinner() {
    Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
}

@Composable
private fun NoMatches(query: String) {
    MessageState(
        icon = Icons.Rounded.SearchOff,
        title = stringResource(R.string.browse_no_matches, query),
        body = null,
        tone = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/** First-run friendly: says what goes here and offers the obvious next step. */
@Composable
private fun EmptyState(providerName: String, hint: String, onAdd: () -> Unit) {
    MessageState(
        icon = Icons.Rounded.VideoLibrary,
        title = stringResource(R.string.browse_empty_title),
        body = stringResource(
            R.string.browse_empty_body,
            providerName.ifEmpty { stringResource(R.string.browse_your_service) }
        ) + " " + hint,
        action = {
            Button(onClick = onAdd) {
                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.browse_empty_add))
            }
        }
    )
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit) {
    MessageState(
        icon = Icons.Rounded.CloudOff,
        title = message,
        body = null,
        tone = MaterialTheme.colorScheme.error,
        action = {
            FilledTonalButton(onClick = onRetry) {
                Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.action_retry))
            }
        }
    )
}

@Composable
private fun AllFilesAccessDialog(
    onPickFolder: () -> Unit,
    onGrant: () -> Unit,
    onUseAppStorage: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.FolderOpen, contentDescription = null) },
        title = { Text(stringResource(R.string.storage_title)) },
        text = {
            Column {
                Text(stringResource(R.string.storage_body))
                Spacer(Modifier.height(16.dp))
                // Three choices, easiest first; buttons stacked so long labels fit.
                Button(onClick = onPickFolder, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.storage_pick_folder))
                }
                OutlinedButton(onClick = onGrant, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.storage_grant))
                }
                OutlinedButton(onClick = onUseAppStorage, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.storage_use_app))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    )
}

// --- list-view grouping (unchanged behaviour) ---------------------------------

/**
 * Filter -> sort -> (optional) group. Pure transform; safe to call from `remember`.
 */
private fun buildSections(
    items: List<DownloadItem>,
    query: String,
    sort: SortOrder,
    grouped: Boolean
): List<BrowseSection> {
    val q = query.trim().lowercase()
    val filtered = if (q.isEmpty()) items else items.filter {
        it.filename.lowercase().contains(q) || it.host.lowercase().contains(q)
    }
    val sorted = filtered.sortedWith(sortComparator(sort))
    if (!grouped) return listOf(BrowseSection.Loose(sorted))

    val episodes = mutableMapOf<String, MutableList<SeriesGrouper.Episode>>()
    val displayNames = mutableMapOf<String, String>()
    val loose = mutableListOf<DownloadItem>()
    sorted.forEach { item ->
        val ep = SeriesGrouper.classify(item)
        if (ep != null) {
            episodes.getOrPut(ep.showKey) { mutableListOf() } += ep
            displayNames.putIfAbsent(ep.showKey, ep.showDisplay)
        } else {
            loose += item
        }
    }
    val showSections = episodes.map { (key, eps) ->
        BrowseSection.Show(
            showKey = key,
            showDisplay = displayNames[key].orEmpty(),
            episodes = eps.sortedWith(compareBy({ it.season ?: Int.MAX_VALUE }, { it.episode }))
        )
    }
    return if (loose.isEmpty()) showSections else showSections + BrowseSection.Loose(loose)
}

private fun sortComparator(sort: SortOrder): Comparator<DownloadItem> = when (sort) {
    SortOrder.DATE_DESC -> compareByDescending { it.addedAt.orEmpty() }
    SortOrder.DATE_ASC -> compareBy { it.addedAt.orEmpty() }
    SortOrder.NAME_ASC -> compareBy { it.filename.lowercase() }
    SortOrder.NAME_DESC -> compareByDescending { it.filename.lowercase() }
    SortOrder.SIZE_DESC -> compareByDescending { it.filesize }
    SortOrder.SIZE_ASC -> compareBy { it.filesize }
}

/** What the "remove from service" dialog is about: library files or a processing job. */
private data class RemoveRequest(
    val label: String,
    val items: List<com.abhinavxt.debforge.domain.DownloadItem>? = null,
    val job: com.abhinavxt.debforge.data.provider.RemoteJob? = null
)

/** After the finger lifts, give the pull indicator this long to settle by itself. */
private const val PULL_SETTLE_MS = 400L
