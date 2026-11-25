package com.abhinavxt.debforge.ui.homepage

import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.background
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import coil.compose.AsyncImage
import com.abhinavxt.debforge.R
import com.abhinavxt.debforge.data.history.HistoryEntity
import com.abhinavxt.debforge.data.youtube.decodeEntities
import com.abhinavxt.debforge.domain.DownloadState
import com.abhinavxt.debforge.ui.active.ActiveDownloadsViewModel
import com.abhinavxt.debforge.ui.active.ActiveRow
import com.abhinavxt.debforge.ui.browse.BrowseViewModel
import com.abhinavxt.debforge.ui.browse.ClipboardOfferEffect
import com.abhinavxt.debforge.ui.browse.ContinueWatchingRow
import com.abhinavxt.debforge.ui.browse.TitleGroup
import kotlinx.coroutines.launch
import com.abhinavxt.debforge.ui.browse.UpcomingRow
import com.abhinavxt.debforge.ui.browse.playExternally
import com.abhinavxt.debforge.ui.components.GeneratedArt
import com.abhinavxt.debforge.ui.components.HeaderNavActions
import com.abhinavxt.debforge.ui.components.MessageState
import com.abhinavxt.debforge.ui.components.ScreenHeader
import com.abhinavxt.debforge.ui.history.Artwork
import com.abhinavxt.debforge.ui.history.HistoryEvent
import com.abhinavxt.debforge.ui.history.HistoryViewModel
import com.abhinavxt.debforge.ui.player.focusRing

/** A chip on Home that jumps to a tab which is not in the bottom bar. */
class HomeShortcut(@StringRes val label: Int, val icon: ImageVector, val onClick: () -> Unit)

/** Active downloads shown on Home before "and N more". */
private const val ACTIVE_SHOWN = 3

/**
 * The Home tab: rows of what to pick up next. Every row hides itself when empty or switched off in Settings.
 * [browse] is the Library's view-model (shared, see HomeScreen), so Home reads its data and shows the same
 * list the Library does instead of loading it again.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomePageScreen(
    browse: BrowseViewModel,
    shortcuts: List<HomeShortcut>,
    onAdd: () -> Unit,
    onAddText: (String) -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTitle: (String) -> Unit,
    activeDownloads: Int,
    viewModel: HomePageViewModel = hiltViewModel(),
    history: HistoryViewModel = hiltViewModel(),
    active: ActiveDownloadsViewModel = hiltViewModel()
) {
    val hidden by viewModel.hiddenRows.collectAsStateWithLifecycle()
    val state by browse.state.collectAsStateWithLifecycle()
    val meta by browse.meta.collectAsStateWithLifecycle()
    val continueWatching by browse.continueWatching.collectAsStateWithLifecycle()
    val comingUp by browse.comingUp.collectAsStateWithLifecycle()
    val listening by history.continueListeningItems.collectAsStateWithLifecycle()
    val activeState by active.state.collectAsStateWithLifecycle()

    val downloading = remember(activeState) {
        activeState.rows.filter { it.entity.state == DownloadState.DOWNLOADING || it.entity.state == DownloadState.QUEUED }
            .sortedBy { it.entity.state != DownloadState.DOWNLOADING }
    }
    val recent = remember(state.items) { recentTitles(state.items) }
    // First load in flight: hold the row's place so it doesn't pop in when the list arrives (gone if it ends empty).
    val loadingRecent = HomeRow.RECENT !in hidden && state.isInitialLoad
    val rows = visibleHomeRows(
        hidden,
        mapOf(
            HomeRow.CONTINUE_WATCHING to continueWatching.size,
            HomeRow.CONTINUE_LISTENING to listening.size,
            HomeRow.RECENT to if (loadingRecent) 1 else recent.size,
            HomeRow.DOWNLOADS to downloading.size,
            HomeRow.NEW_EPISODES to comingUp.size
        )
    )
    // As the Library does: poll processing jobs only while the screen is on show.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) { browse.pollWhileVisible() }
    }
    // The side rail (width >= 600dp, as in HomeScreen) already lists every tab.
    val railShown = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 600
    val showShortcuts = HomeRow.SHORTCUTS !in hidden && shortcuts.isNotEmpty() && !railShown

    val snackbar = remember { SnackbarHostState() }
    val clipMessage = stringResource(R.string.clipboard_offer)
    val clipAction = stringResource(R.string.action_add)
    val clipScope = androidx.compose.runtime.rememberCoroutineScope()
    ClipboardOfferEffect(onOffer = { links ->
        clipScope.launch {
            val result = snackbar.showSnackbar(
                message = clipMessage, actionLabel = clipAction, withDismissAction = true,
                duration = androidx.compose.material3.SnackbarDuration.Long
            )
            if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) onAddText(links)
        }
    })
    // Same hand-off as the Library: a tapped Continue watching entry opens in the player.
    val context = LocalContext.current
    val videoLauncher = com.abhinavxt.debforge.player.rememberVideoLauncher()
    val msgNoPlayer = stringResource(R.string.msg_no_player)
    LaunchedEffect(Unit) {
        browse.playRequests.collect { req ->
            if (browse.useInternalPlayer.value) videoLauncher.open(context, req)
            else if (!context.playExternally(req)) snackbar.showSnackbar(msgNoPlayer)
        }
    }
    LaunchedEffect(Unit) { browse.events.collect { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(it) } }
    LaunchedEffect(Unit) {
        history.events.collect { ev ->
            when (ev) {
                is HistoryEvent.Unplayable -> { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(ev.text) }
                is HistoryEvent.Play ->
                    if (history.useInternalPlayer.value) videoLauncher.open(context, ev.request)
                    else if (!context.playExternally(ev.request)) snackbar.showSnackbar(msgNoPlayer)
                is HistoryEvent.OpenYouTube -> Unit
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(title = stringResource(R.string.tab_home)) {
            HeaderNavActions(onOpenSettings, onOpenDownloads = onOpenDownloads, activeCount = activeDownloads)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            PullToRefreshBox(
                isRefreshing = state.isRefreshing,
                onRefresh = browse::pullRefresh,
                modifier = Modifier.fillMaxSize()
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    if (showShortcuts) item(key = "shortcuts") {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(shortcuts, key = { it.label }) { s ->
                                AssistChip(
                                    onClick = s.onClick,
                                    label = { Text(stringResource(s.label)) },
                                    leadingIcon = { Icon(s.icon, contentDescription = null) }
                                )
                            }
                        }
                    }
                    rows.forEach { row ->
                        item(key = row.key) {
                            when (row) {
                                HomeRow.CONTINUE_WATCHING -> ContinueWatchingRow(
                                    entries = continueWatching,
                                    meta = meta,
                                    onRequestMeta = browse::requestMeta,
                                    onPlay = browse::playSaved,
                                    onRemove = browse::forgetPlayback
                                )
                                HomeRow.CONTINUE_LISTENING -> ContinueListeningRow(listening, history::replay, history::hideContinue)
                                HomeRow.RECENT -> RecentRow(recent, loadingRecent, meta, browse::requestMeta, onOpenTitle)
                                HomeRow.DOWNLOADS -> DownloadingRow(downloading, onOpenDownloads)
                                HomeRow.NEW_EPISODES -> UpcomingRow(comingUp, meta, browse::requestMeta)
                                HomeRow.SHORTCUTS -> Unit
                            }
                        }
                    }
                    // Not while the first read is still on its way: it would flash before the rows arrive.
                    if (rows.isEmpty() && !state.isInitialLoad) item(key = "empty") {
                        Box(Modifier.fillParentMaxSize()) {
                            MessageState(
                                icon = Icons.Rounded.Home,
                                title = stringResource(R.string.home_empty_title),
                                body = stringResource(R.string.home_empty_body),
                                action = { Button(onClick = onAdd) { Text(stringResource(R.string.home_empty_add)) } }
                            )
                        }
                    }
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
        }
    }
}

@Composable
private fun RowTitle(@StringRes title: Int) = Text(
    stringResource(title),
    style = MaterialTheme.typography.titleMedium,
    modifier = Modifier.padding(bottom = 8.dp)
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContinueListeningRow(
    items: List<HistoryEntity>,
    onPlay: (HistoryEntity) -> Unit,
    onHide: (HistoryEntity) -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        RowTitle(R.string.home_continue_listening)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(items, key = { it.itemId }) { e ->
                var menu by remember { mutableStateOf(false) }
                val source = remember { MutableInteractionSource() }
                Box {
                    Column(
                        Modifier
                            .width(128.dp)
                            .clip(MaterialTheme.shapes.medium)
                            .focusRing(source, MaterialTheme.shapes.medium)
                            .combinedClickable(
                                interactionSource = source,
                                indication = androidx.compose.foundation.LocalIndication.current,
                                onClick = { onPlay(e) },
                                onLongClick = { menu = true }
                            )
                    ) {
                        Artwork(e, Modifier.fillMaxWidth().aspectRatio(1f).clip(MaterialTheme.shapes.medium))
                        Text(
                            decodeEntities(e.title),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 6.dp, start = 2.dp)
                        )
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.continue_remove)) },
                            leadingIcon = { Icon(Icons.Rounded.DeleteOutline, contentDescription = null) },
                            onClick = { menu = false; onHide(e) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentRow(
    titles: List<TitleGroup>,
    loading: Boolean,
    meta: Map<String, com.abhinavxt.debforge.data.metadata.MediaMeta?>,
    onRequestMeta: (com.abhinavxt.debforge.domain.ReleaseInfo) -> Unit,
    onOpen: (String) -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        RowTitle(R.string.home_recently_added)
        if (loading) {
            val pulse by androidx.compose.animation.core.rememberInfiniteTransition(label = "recent").animateFloat(
                initialValue = 0.35f, targetValue = 0.8f,
                animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                    androidx.compose.animation.core.tween(900), androidx.compose.animation.core.RepeatMode.Reverse
                ),
                label = "pulse"
            )
            Row(Modifier.graphicsLayer { alpha = pulse }, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                repeat(3) {
                    Column(Modifier.width(112.dp)) {
                        Box(
                            Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(MaterialTheme.shapes.medium)
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        )
                        // Where the caption goes, so the row is as tall as the real one.
                        Spacer(Modifier.height(6.dp + 20.dp))
                    }
                }
            }
            return@Column
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(titles, key = { it.key }) { group ->
                LaunchedEffect(group.info.groupKey) { onRequestMeta(group.info) }
                val source = remember { MutableInteractionSource() }
                val found = meta[group.info.groupKey]
                Column(
                    Modifier
                        .width(112.dp)
                        .clip(MaterialTheme.shapes.medium)
                        .focusRing(source, MaterialTheme.shapes.medium)
                        .clickable(interactionSource = source, indication = androidx.compose.foundation.LocalIndication.current) { onOpen(group.key) }
                ) {
                    val art = Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(MaterialTheme.shapes.medium)
                    if (found?.posterUrl != null) AsyncImage(found.posterUrl, null, art, contentScale = ContentScale.Crop)
                    else GeneratedArt(title = group.title, tag = "", modifier = art, showTitle = false)
                    // The Library's caption: the looked-up title, else the parsed one.
                    Text(
                        found?.title ?: group.title,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp, start = 2.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun DownloadingRow(rows: List<ActiveRow>, onOpen: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        RowTitle(R.string.home_downloading)
        Surface(
            onClick = onOpen,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                rows.take(ACTIVE_SHOWN).forEach { row ->
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                row.entity.title ?: row.entity.filename,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            if (row.entity.filesize > 0) Text(
                                "${(row.fraction * 100).toInt()}%",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                        val bar = Modifier.fillMaxWidth().padding(top = 6.dp)
                        // Queued (or size unknown): no honest fraction to show.
                        if (row.entity.state == DownloadState.DOWNLOADING && row.entity.filesize > 0)
                            LinearProgressIndicator(progress = { row.fraction }, modifier = bar)
                        else LinearProgressIndicator(modifier = bar)
                    }
                }
                if (rows.size > ACTIVE_SHOWN) Text(
                    stringResource(R.string.home_more_downloads, rows.size - ACTIVE_SHOWN),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
