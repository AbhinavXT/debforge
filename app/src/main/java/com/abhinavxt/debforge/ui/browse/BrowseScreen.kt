package com.abhinavxt.debforge.ui.browse

import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.abhinavxt.debforge.domain.DownloadItem
import com.abhinavxt.debforge.domain.DownloadState
import com.abhinavxt.debforge.domain.SortOrder

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseScreen(viewModel: BrowseViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val downloadStates by viewModel.downloadStates.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    var pendingPermissionItem by remember { mutableStateOf<DownloadItem?>(null) }

    // Collect the events Channel for the lifetime of this composition. Channel
    // buffers, so a burst of rapid enqueues renders sequentially without loss.
    LaunchedEffect(Unit) {
        viewModel.events.collect { message ->
            snackbar.showSnackbar(message)
        }
    }

    fun handleEnqueueTap(item: DownloadItem) {
        if (Environment.isExternalStorageManager()) {
            viewModel.enqueue(item)
        } else {
            pendingPermissionItem = item
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Downloads") },
                actions = {
                    IconButton(onClick = viewModel::refresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            ControlBar(
                query = state.query,
                onQuery = viewModel::onQueryChange,
                sort = state.sort,
                onSort = viewModel::onSortChange,
                grouped = state.grouped,
                onToggleGroup = viewModel::onToggleGroup
            )
            HorizontalDivider()

            // Pure derivation: query/sort/group transformations. Recomputes
            // only when one of the inputs changes — sub-ms for a few hundred items.
            val sections = remember(state.items, state.query, state.sort, state.grouped) {
                buildSections(state.items, state.query, state.sort, state.grouped)
            }
            val flatItemCount = remember(sections) { sections.sumOf { it.items.size } }

            BrowseBody(
                state = state,
                sections = sections,
                flatItemCount = flatItemCount,
                downloadStates = downloadStates,
                onEnqueue = ::handleEnqueueTap,
                onLoadMore = viewModel::loadMore,
                onRetry = { viewModel.clearError(); viewModel.refresh() }
            )
        }
    }

    pendingPermissionItem?.let { item ->
        AllFilesAccessDialog(
            onGrant = {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
                context.startActivity(intent)
                pendingPermissionItem = null
            },
            onDismiss = { pendingPermissionItem = null }
        )
    }
}

// --- main body switcher -------------------------------------------------------

@Composable
private fun BrowseBody(
    state: BrowseUiState,
    sections: List<BrowseSection>,
    flatItemCount: Int,
    downloadStates: Map<String, DownloadState>,
    onEnqueue: (DownloadItem) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit
) {
    when {
        state.isInitialLoad -> CenteredSpinner()
        state.error != null && state.items.isEmpty() -> ErrorState(state.error, onRetry)
        state.items.isEmpty() -> EmptyState()
        flatItemCount == 0 -> Box(Modifier.fillMaxSize(), Alignment.Center) {
            Text(
                "No matches for \"${state.query}\"",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        else -> SectionList(
            sections = sections,
            grouped = state.grouped,
            isAppending = state.isAppending,
            endReached = state.endReached,
            downloadStates = downloadStates,
            onEnqueue = onEnqueue,
            onLoadMore = onLoadMore
        )
    }
}

// --- top control row ----------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ControlBar(
    query: String,
    onQuery: (String) -> Unit,
    sort: SortOrder,
    onSort: (SortOrder) -> Unit,
    grouped: Boolean,
    onToggleGroup: () -> Unit
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search filename or host") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQuery("") }) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear")
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
                selected = grouped,
                onClick = onToggleGroup,
                label = { Text("Group as series") }
            )
        }
    }
}

@Composable
private fun SortPicker(sort: SortOrder, onSort: (SortOrder) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) {
            Text("Sort: ${sort.label()}")
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

private fun SortOrder.label(): String = when (this) {
    SortOrder.DATE_DESC -> "Newest"
    SortOrder.DATE_ASC -> "Oldest"
    SortOrder.NAME_ASC -> "Name A-Z"
    SortOrder.NAME_DESC -> "Name Z-A"
    SortOrder.SIZE_DESC -> "Largest"
    SortOrder.SIZE_ASC -> "Smallest"
}

// --- list rendering -----------------------------------------------------------

@Composable
private fun SectionList(
    sections: List<BrowseSection>,
    grouped: Boolean,
    isAppending: Boolean,
    endReached: Boolean,
    downloadStates: Map<String, DownloadState>,
    onEnqueue: (DownloadItem) -> Unit,
    onLoadMore: () -> Unit
) {
    val listState = rememberLazyListState()

    // Trigger pagination when we get within a few items of the bottom. The
    // ViewModel guards against double-fires, so spurious triggers are safe.
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { lastIndex ->
                val total = listState.layoutInfo.totalItemsCount
                if (total > 0 && lastIndex >= total - 4) onLoadMore()
            }
    }

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(vertical = 8.dp),
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
                        ItemRow(
                            item = ep.item,
                            indent = true,
                            secondaryPrefix = label,
                            downloadState = downloadStates[ep.item.id],
                            onEnqueue = onEnqueue
                        )
                    }
                } else {
                    items(section.items, key = { it.id }) { item ->
                        ItemRow(
                            item = item,
                            indent = false,
                            secondaryPrefix = null,
                            downloadState = downloadStates[item.id],
                            onEnqueue = onEnqueue
                        )
                    }
                }
                is BrowseSection.Loose -> {
                    if (grouped && section.items.isNotEmpty()) {
                        item(key = "loose-header") { SectionHeader("Other") }
                    }
                    items(section.items, key = { it.id }) { item ->
                        ItemRow(
                            item = item,
                            indent = grouped,
                            secondaryPrefix = null,
                            downloadState = downloadStates[item.id],
                            onEnqueue = onEnqueue
                        )
                    }
                }
            }
        }
        if (isAppending) {
            item("appending") {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
                }
            }
        } else if (endReached) {
            item("end") {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "End of list",
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

@Composable
private fun ItemRow(
    item: DownloadItem,
    indent: Boolean,
    secondaryPrefix: String?,
    downloadState: DownloadState?,
    onEnqueue: (DownloadItem) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = if (indent) 24.dp else 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.filename,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            val subtitle = buildString {
                if (secondaryPrefix != null) append(secondaryPrefix).append(" · ")
                append(item.host).append(" · ").append(formatSize(item.filesize))
            }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (downloadState != null) {
            // Item is tracked locally — show status, hide Download button to
            // prevent accidental double-enqueue. User manages it via Active tab.
            Text(
                text = downloadState.badgeLabel(),
                style = MaterialTheme.typography.labelMedium,
                color = downloadState.badgeColor()
            )
        } else {
            TextButton(onClick = { onEnqueue(item) }) {
                Text("Download")
            }
        }
    }
}

@Composable
private fun DownloadState.badgeColor() = when (this) {
    DownloadState.COMPLETED -> MaterialTheme.colorScheme.primary
    DownloadState.FAILED -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun DownloadState.badgeLabel() = when (this) {
    DownloadState.QUEUED -> "Queued"
    DownloadState.DOWNLOADING -> "Downloading"
    DownloadState.PAUSED -> "Paused"
    DownloadState.COMPLETED -> "Done"
    DownloadState.FAILED -> "Failed"
}

// --- empty / loading / error / dialog -----------------------------------------

@Composable
private fun CenteredSpinner() {
    Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
}

@Composable
private fun EmptyState() {
    Box(Modifier.fillMaxSize(), Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("No downloads on your account yet", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Add a magnet or link on real-debrid.com to populate this list.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize(), Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(message, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(8.dp))
            Button(onClick = onRetry) { Text("Retry") }
        }
    }
}

@Composable
private fun AllFilesAccessDialog(onGrant: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("All files access required") },
        text = {
            Text(
                "DebForge needs the all-files access permission to save downloads " +
                    "anywhere on shared storage. Tap Grant to open settings."
            )
        },
        confirmButton = { TextButton(onClick = onGrant) { Text("Grant") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// --- helpers ------------------------------------------------------------------

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024L * 1024 -> "${bytes / 1024} KB"
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    else -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
}

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
    // Show sections: keep map insertion order (which matches the sort order of
    // the first item in each show); episodes within a show by season/ep asc for
    // natural reading.
    val showSections = episodes.map { (key, eps) ->
        BrowseSection.Show(
            showKey = key,
            showDisplay = displayNames[key].orEmpty(),
            episodes = eps.sortedWith(
                compareBy({ it.season ?: Int.MAX_VALUE }, { it.episode })
            )
        )
    }
    return if (loose.isEmpty()) showSections else showSections + BrowseSection.Loose(loose)
}

private fun sortComparator(sort: SortOrder): Comparator<DownloadItem> = when (sort) {
    SortOrder.DATE_DESC -> compareByDescending { it.generated.orEmpty() }
    SortOrder.DATE_ASC -> compareBy { it.generated.orEmpty() }
    SortOrder.NAME_ASC -> compareBy { it.filename.lowercase() }
    SortOrder.NAME_DESC -> compareByDescending { it.filename.lowercase() }
    SortOrder.SIZE_DESC -> compareByDescending { it.filesize }
    SortOrder.SIZE_ASC -> compareBy { it.filesize }
}
