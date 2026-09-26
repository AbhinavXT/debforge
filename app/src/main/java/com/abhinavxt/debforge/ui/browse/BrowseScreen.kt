package com.abhinavxt.debforge.ui.browse

import androidx.compose.ui.res.stringResource
import com.abhinavxt.debforge.R
import com.abhinavxt.debforge.ui.update.UpdateBanner
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.abhinavxt.debforge.domain.DownloadItem
import com.abhinavxt.debforge.domain.DownloadState
import com.abhinavxt.debforge.domain.ReleaseInfo
import com.abhinavxt.debforge.domain.ReleaseNameParser
import com.abhinavxt.debforge.domain.SortOrder
import com.abhinavxt.debforge.download.StorageAccess

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseScreen(
    onAdd: () -> Unit = {},
    viewModel: BrowseViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val downloadStates by viewModel.downloadStates.collectAsStateWithLifecycle()
    val meta by viewModel.meta.collectAsStateWithLifecycle()
    val processing by viewModel.processing.collectAsStateWithLifecycle()
    val watchedJobs by viewModel.watchedJobs.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val uriHandler = LocalUriHandler.current
    // Multi-select (list view): ids of selected files. Empty = not selecting.
    var selected by remember { mutableStateOf(setOf<String>()) }
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    var pendingPermission by remember { mutableStateOf<List<DownloadItem>?>(null) }
    var openGroupKey by remember { mutableStateOf<String?>(null) }
    var openFile by remember { mutableStateOf<ParsedItem?>(null) }

    LaunchedEffect(Unit) {
        viewModel.events.collect { message -> snackbar.showSnackbar(message) }
    }
    LaunchedEffect(Unit) {
        viewModel.playRequests.collect { req ->
            if (!context.playExternally(req)) viewModel.reportNoPlayer()
        }
    }
    // Back exits multi-select; switching to posters clears it.
    BackHandler(enabled = selected.isNotEmpty()) { selected = emptySet() }
    LaunchedEffect(state.posterView) { if (state.posterView) selected = emptySet() }

    // Poll processing jobs / auto-downloads only while the screen is visible.
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.pollWhileVisible() }
    }

    val downloadDir by viewModel.downloadDir.collectAsStateWithLifecycle()

    fun handleEnqueue(items: List<DownloadItem>) {
        if (items.isEmpty()) return
        if (StorageAccess.canWrite(context, downloadDir)) {
            viewModel.enqueueAll(items)
        } else {
            pendingPermission = items
        }
    }

    // Parse every filename once per list change (cheap, but not per frame).
    val parsed: Map<String, ReleaseInfo> = remember(state.items) {
        state.items.associate { it.id to ReleaseNameParser.parse(it.filename) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.browse_add_cd))
            }
        },
        topBar = {
            if (selected.isNotEmpty()) {
                // Contextual bar while multi-selecting.
                TopAppBar(
                    title = { Text(stringResource(R.string.browse_selected, selected.size)) },
                    navigationIcon = {
                        IconButton(onClick = { selected = emptySet() }) {
                            Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.browse_cancel_selection))
                        }
                    },
                    actions = {
                        TextButton(onClick = {
                            val picked = state.items.filter { it.id in selected && downloadStates[it.id] == null }
                            handleEnqueue(picked)
                            selected = emptySet()
                        }) { Text(stringResource(R.string.action_download)) }
                    }
                )
            } else {
                TopAppBar(
                    title = {
                        Column {
                            Text(stringResource(R.string.tab_library))
                            if (state.providerName.isNotEmpty()) {
                                Text(
                                    state.providerName,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = viewModel::refresh) {
                            Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.action_refresh))
                        }
                    }
                )
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
            HorizontalDivider()

            state.expiryWarning?.let { warning ->
                ExpiryBanner(
                    text = warning,
                    onRenew = { state.renewUrl?.let(uriHandler::openUri) },
                    onDismiss = viewModel::dismissExpiryWarning
                )
            }

            UpdateBanner()

            ProcessingStrip(
                providerName = state.providerName,
                jobs = processing,
                isWatched = { "${it.provider.name}|${it.ref}" in watchedJobs },
                onToggleWatch = viewModel::toggleWatch
            )

            when {
                state.isInitialLoad -> CenteredSpinner()
                state.error != null && state.items.isEmpty() ->
                    ErrorState(state.error!!) { viewModel.clearError(); viewModel.refresh() }
                state.items.isEmpty() -> EmptyState(
                    providerName = state.providerName,
                    hint = state.emptyHint,
                    onAdd = onAdd
                )
                state.posterView -> {
                    val groups = remember(state.items, parsed, state.query, state.sort) {
                        buildTitleGroups(state.items, parsed, state.query, state.sort)
                    }
                    if (groups.isEmpty()) {
                        NoMatches(state.query)
                    } else {
                        PosterGrid(
                            groups = groups,
                            meta = meta,
                            downloadStates = downloadStates,
                            isAppending = state.isAppending,
                            endReached = state.endReached,
                            onRequestMeta = { viewModel.requestMeta(it.info) },
                            onOpen = { openGroupKey = it.key },
                            onLoadMore = viewModel::loadMore
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
                                onDismiss = { openGroupKey = null }
                            )
                        }
                    }
                }
                else -> {
                    val sections = remember(state.items, state.query, state.sort, state.grouped) {
                        buildSections(state.items, state.query, state.sort, state.grouped)
                    }
                    if (sections.sumOf { it.items.size } == 0) {
                        NoMatches(state.query)
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

    openFile?.let { f ->
        FileInfoDialog(
            file = f,
            state = downloadStates[f.item.id],
            onDownload = { handleEnqueue(listOf(f.item)); openFile = null },
            onPlay = { viewModel.play(f.item, f.info.displayTitle); openFile = null },
            onDismiss = { openFile = null }
        )
    }

    pendingPermission?.let { items ->
        AllFilesAccessDialog(
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
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.browse_search_hint)) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQuery("") }) {
                        Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.action_clear))
                    }
                }
            },
            singleLine = true
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            SortPicker(sort = sort, onSort = onSort)
            Spacer(Modifier.width(8.dp))
            FilterChip(
                selected = posterView,
                onClick = onTogglePosters,
                label = { Text(stringResource(R.string.browse_posters)) }
            )
            if (!posterView) {
                Spacer(Modifier.width(8.dp))
                FilterChip(
                    selected = grouped,
                    onClick = onToggleGroup,
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
        TextButton(onClick = { expanded = true }) {
            Text(sort.label())
            Icon(Icons.Default.KeyboardArrowDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SortOrder.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label()) },
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
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isSelected) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surface
            )
            .combinedClickable(onClick = { onOpen(item) }, onLongClick = { onLongPress(item) })
            .padding(horizontal = if (indent) 24.dp else 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (selectionMode) {
            Checkbox(checked = isSelected, onCheckedChange = { onOpen(item) })
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = info?.displayTitle ?: item.filename,
                style = MaterialTheme.typography.bodyMedium,
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
            Text(
                text = downloadState.badgeLabel(),
                style = MaterialTheme.typography.labelMedium,
                color = when (downloadState) {
                    DownloadState.COMPLETED -> MaterialTheme.colorScheme.primary
                    DownloadState.FAILED -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        } else {
            TextButton(onClick = { onEnqueue(item) }) { Text(stringResource(R.string.action_download)) }
        }
    }
}

@Composable
private fun FileInfoDialog(
    file: ParsedItem,
    state: DownloadState?,
    onDownload: () -> Unit,
    onPlay: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
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
                if (file.info.isVideo) TextButton(onClick = onPlay) { Text(stringResource(R.string.action_play)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
            }
        }
    )
}

// --- empty / loading / error / dialog -----------------------------------------

@Composable
private fun ExpiryBanner(text: String, onRenew: () -> Unit, onDismiss: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onRenew) { Text(stringResource(R.string.action_renew)) }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.action_dismiss))
            }
        }
    }
}

@Composable
private fun CenteredSpinner() {
    Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
}

@Composable
private fun NoMatches(query: String) {
    Box(Modifier.fillMaxSize(), Alignment.Center) {
        Text(
            stringResource(R.string.browse_no_matches, query),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** First-run friendly: says what goes here and offers the obvious next step. */
@Composable
private fun EmptyState(providerName: String, hint: String, onAdd: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(32.dp), Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.browse_empty_title), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(
                    R.string.browse_empty_body,
                    providerName.ifEmpty { stringResource(R.string.browse_your_service) }
                ) + " " + hint,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(20.dp))
            Button(onClick = onAdd) { Text(stringResource(R.string.browse_empty_add)) }
        }
    }
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(24.dp), Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(message, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Button(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
        }
    }
}

@Composable
private fun AllFilesAccessDialog(onGrant: () -> Unit, onUseAppStorage: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.storage_title)) },
        text = {
            Text(stringResource(R.string.storage_body))
        },
        confirmButton = { TextButton(onClick = onGrant) { Text(stringResource(R.string.storage_grant)) } },
        dismissButton = {
            Row {
                TextButton(onClick = onUseAppStorage) { Text(stringResource(R.string.storage_use_app)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        }
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
