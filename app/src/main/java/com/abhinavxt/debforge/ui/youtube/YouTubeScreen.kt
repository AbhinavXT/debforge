package com.abhinavxt.debforge.ui.youtube

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.rounded.Search
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import com.abhinavxt.debforge.ui.components.channelLink
import com.abhinavxt.debforge.ui.components.dpadEscape
import com.abhinavxt.debforge.ui.components.rememberCursorField
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import com.abhinavxt.debforge.domain.YouTubeSubtitle
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.abhinavxt.debforge.data.subscriptions.NewFeedItem
import kotlinx.coroutines.launch
import com.abhinavxt.debforge.R
import com.abhinavxt.debforge.domain.Container
import com.abhinavxt.debforge.domain.DefaultQuality
import com.abhinavxt.debforge.domain.FormatChoice
import com.abhinavxt.debforge.domain.YouTubeLinks
import com.abhinavxt.debforge.domain.YouTubeSearchResult
import com.abhinavxt.debforge.domain.YouTubeVideo
import com.abhinavxt.debforge.ui.player.focusRing
import com.abhinavxt.debforge.ui.player.rememberSwipeDown
import com.abhinavxt.debforge.ui.player.swipeDownToDismiss
import com.abhinavxt.debforge.ui.components.HeaderNavActions
import com.abhinavxt.debforge.ui.components.MessageState
import com.abhinavxt.debforge.ui.components.ScreenHeader

private val FieldShape = CircleShape
private val RowShape = RoundedCornerShape(16.dp)

@Composable
fun YouTubeScreen(
    onOpenDownloads: () -> Unit,
    onOpenSettings: () -> Unit,
    activeDownloads: Int,
    modifier: Modifier = Modifier,
    viewModel: YouTubeViewModel = hiltViewModel()
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    // Back steps out of a preview (to its search results, if any) or a search before it leaves the tab.
    androidx.activity.compose.BackHandler(ui !is YouTubeUi.Empty, viewModel::reset)
    val input by viewModel.input.collectAsStateWithLifecycle()
    val trending by viewModel.trending.collectAsStateWithLifecycle()
    val recent by viewModel.recent.collectAsStateWithLifecycle()
    val newItems by viewModel.newItems.collectAsStateWithLifecycle()
    val followedUrls by viewModel.followedUrls.collectAsStateWithLifecycle()
    val followBusy by viewModel.followBusy.collectAsStateWithLifecycle()
    val canonicalByPage by viewModel.canonicalByPage.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    // Android only lets the focused window read the clipboard, so read when focus arrives, and again on tapping Paste.
    var clip by remember { mutableStateOf<String?>(null) }
    val focused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(focused) { if (focused) clip = readClipLink(context) }
    val scope = rememberCoroutineScope()
    val folderPrompt by viewModel.folderPrompt.collectAsStateWithLifecycle()
    val pickFolder = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) viewModel.dismissFolderPrompt()
        else {
            runCatching { com.abhinavxt.debforge.download.StorageAccess.rememberPickedFolder(context, uri, null) }
            viewModel.setFolder(uri.toString())
        }
    }
    if (folderPrompt) {
        com.abhinavxt.debforge.ui.browse.AllFilesAccessDialog(
            onPickFolder = { pickFolder.launch(null) },
            onUseAppStorage = { viewModel.setFolder(com.abhinavxt.debforge.download.StorageAccess.appStorageDir(context)) },
            onDismiss = viewModel::dismissFolderPrompt,
        )
    }
    val msgNoLink = stringResource(R.string.yt_paste_none)
    val msgQueued = stringResource(R.string.yt_queued)
    val msgAlready = stringResource(R.string.yt_already_queued)
    val msgFailed = stringResource(R.string.yt_err_queue)
    val msgNoSubtitle = stringResource(R.string.yt_queued_no_subtitle)
    val msgSubtitleAdded = stringResource(R.string.yt_subtitle_added)
    val msgFollowFailed = stringResource(R.string.yt_follow_failed)
    val msgQueueAdded = stringResource(R.string.yt_queue_added)
    val msgQueueAlready = stringResource(R.string.yt_queue_already)
    val viewDownloads = stringResource(R.string.yt_view_downloads)

    LaunchedEffect(Unit) {
        viewModel.playRequests.collect { context.startActivity(com.abhinavxt.debforge.player.PlayerActivity.intent(context, it)) }
    }
    val streamingUrl by viewModel.streamingUrl.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.events.collect { e ->
            val queuedOk = e == YouTubeEvent.QUEUED || e == YouTubeEvent.SUBTITLE_ADDED || (e is YouTubeEvent.BatchDone && e.added > 0)
            snackbar.currentSnackbarData?.dismiss()
            val res = context.resources
            val r = snackbar.showSnackbar(
                message = when (e) {
                    YouTubeEvent.QUEUED -> msgQueued
                    YouTubeEvent.ALREADY_QUEUED -> msgAlready
                    YouTubeEvent.QUEUE_FAILED -> msgFailed
                    YouTubeEvent.QUEUED_NO_SUBTITLE -> msgNoSubtitle
                    YouTubeEvent.SUBTITLE_ADDED -> msgSubtitleAdded
                    YouTubeEvent.FollowFailed -> msgFollowFailed
                    YouTubeEvent.AddedToPlayQueue -> msgQueueAdded
                    YouTubeEvent.AlreadyInPlayQueue -> msgQueueAlready
                    is YouTubeEvent.StreamFailed -> res.getString(errorText(e.kind))
                    is YouTubeEvent.PlaylistAdd -> res.getString(playlistAddMessage(e.result, e.mode), e.listName)
                    is YouTubeEvent.BatchDone -> listOfNotNull(
                        e.added.takeIf { it > 0 }?.let { res.getQuantityString(R.plurals.yt_batch_added, it, it) },
                        e.already.takeIf { it > 0 }?.let { res.getQuantityString(R.plurals.yt_batch_already, it, it) },
                        e.failed.takeIf { it > 0 }?.let { res.getQuantityString(R.plurals.yt_batch_failed, it, it) },
                    ).joinToString(" · ")
                },
                actionLabel = if (e == YouTubeEvent.QUEUE_FAILED || e == YouTubeEvent.FollowFailed || e is YouTubeEvent.StreamFailed || e is YouTubeEvent.PlaylistAdd || e == YouTubeEvent.AddedToPlayQueue || e == YouTubeEvent.AlreadyInPlayQueue) null else viewDownloads,
                duration = if (queuedOk) SnackbarDuration.Long else SnackbarDuration.Short,
            )
            if (r == SnackbarResult.ActionPerformed) onOpenDownloads()
        }
    }

    var addRequest by remember { mutableStateOf<AddToPlaylistRequest?>(null) }
    var addLists by remember { mutableStateOf<List<com.abhinavxt.debforge.data.playback.Playlist>>(emptyList()) }
    val openAdd = remember<(AddToPlaylistRequest) -> Unit> { { req -> scope.launch { addLists = viewModel.playlists(); addRequest = req } } }
    addRequest?.let { req ->
        YouTubeAddToPlaylistFlow(
            req, addLists,
            onAdd = { id, mode -> viewModel.addToPlaylist(req, id, mode); addRequest = null },
            onCreate = { name, mode -> viewModel.addToNewPlaylist(req, name, mode); addRequest = null },
            onDismiss = { addRequest = null },
        )
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalAddToPlaylist provides openAdd) {
    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader(stringResource(R.string.yt_title), subtitle = stringResource(R.string.yt_subtitle)) {
                HeaderNavActions(onOpenSettings, onOpenDownloads = onOpenDownloads, activeCount = activeDownloads)
            }
            Spacer(Modifier.height(8.dp)) // scrolled content is clipped here, clear of the subtitle
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                YouTubeContent(
                    ui = ui,
                    input = input,
                    clipLink = clip,
                    onInput = viewModel::setInput,
                    onSubmit = viewModel::submit,
                    onPaste = {
                        clip = readClipLink(context)
                        val link = clip
                        if (link != null) viewModel.submit(link)
                        else scope.launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(msgNoLink) }
                    },
                    onPick = viewModel::pick,
                    onOpenRelated = viewModel::openRelated,
                    onAddToQueue = viewModel::addToQueue,
                    onClosePreview = viewModel::closePreview,
                    onReset = viewModel::reset,
                    onRetry = viewModel::retry,
                    onMode = viewModel::selectMode,
                    onSelect = viewModel::select,
                    onDownload = viewModel::download,
                    onPlay = viewModel::play,
                    onPlayResult = viewModel::playResult,
                    streamingUrl = streamingUrl,
                    onSubtitle = viewModel::selectSubtitle,
                    onToggle = viewModel::togglePlaylistItem,
                    onSelectAll = viewModel::selectAllPlaylist,
                    onQuality = viewModel::setPlaylistQuality,
                    onDownloadPlaylist = viewModel::downloadPlaylist,
                    onCancelBatch = viewModel::cancelBatch,
                    onLoadMore = viewModel::loadMore,
                    resultsList = viewModel.resultsList,
                    trending = trending,
                    recent = recent,
                    onRemoveRecent = viewModel::removeRecent,
                    onClearRecent = viewModel::clearRecent,
                    onChannel = viewModel::openChannel,
                    onLoadTrending = viewModel::loadTrending,
                    newItems = newItems,
                    onNew = viewModel::openNew,
                    onMarkAllSeen = viewModel::markAllSeen,
                    followedUrls = followedUrls,
                    followBusy = followBusy,
                    canonicalByPage = canonicalByPage,
                    onToggleFollow = viewModel::toggleFollow,
                )
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = if (ui is YouTubeUi.Preview || ui is YouTubeUi.Playlist) 88.dp else 16.dp))
    }
    }
}

private fun readClipLink(context: Context): String? = runCatching {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
        ?.takeIf { looksLikeYouTube(it) }
}.getOrNull()

@Composable
internal fun YouTubeContent(
    ui: YouTubeUi,
    input: String,
    clipLink: String?,
    onInput: (String) -> Unit,
    onSubmit: (String) -> Unit,
    onPaste: () -> Unit,
    onPick: (YouTubeSearchResult) -> Unit,
    onReset: () -> Unit,
    onRetry: () -> Unit,
    onMode: (Boolean) -> Unit,
    onSelect: (FormatChoice) -> Unit,
    onDownload: () -> Unit,
    onSubtitle: (YouTubeSubtitle?) -> Unit,
    onToggle: (String) -> Unit,
    onSelectAll: (Boolean) -> Unit,
    onQuality: (DefaultQuality) -> Unit,
    onDownloadPlaylist: () -> Unit,
    onCancelBatch: () -> Unit,
    onLoadMore: () -> Unit,
    resultsList: LazyListState = rememberLazyListState(),
    trending: List<YouTubeSearchResult> = emptyList(),
    recent: List<String> = emptyList(),
    onRemoveRecent: (String) -> Unit = {},
    onClearRecent: () -> Unit = {},
    onChannel: (name: String, url: String) -> Unit = { _, _ -> },
    onLoadTrending: () -> Unit = {},
    onPlay: () -> Unit = {},
    onPlayResult: (YouTubeSearchResult) -> Unit = {},
    streamingUrl: String? = null,
    newItems: List<NewFeedItem> = emptyList(),
    onNew: (NewFeedItem) -> Unit = {},
    onMarkAllSeen: () -> Unit = {},
    followedUrls: Set<String> = emptySet(),
    followBusy: String? = null,
    canonicalByPage: Map<String, String> = emptyMap(),
    onToggleFollow: (String) -> Unit = {},
    onOpenRelated: (YouTubeSearchResult) -> Unit = {},
    onAddToQueue: (YouTubeSearchResult) -> Unit = {},
    onClosePreview: () -> Unit = {},
) {
    val cfg = androidx.compose.ui.platform.LocalConfiguration.current
    val landscapeWide = cfg.screenWidthDp > cfg.screenHeightDp && cfg.screenWidthDp >= 600
    AnimatedContent(
        ui,
        contentKey = { it::class },
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "youtube-state",
        modifier = Modifier.widthIn(max = when {
            ui is YouTubeUi.Preview && landscapeWide -> 1100.dp // the preview splits into two panes when wide
            (ui is YouTubeUi.Results || ui is YouTubeUi.Empty) && cfg.smallestScreenWidthDp >= 600 -> 1280.dp // tablets: result lists use the width in columns
            else -> 640.dp
        }),
    ) { state ->
        when (state) {
            YouTubeUi.Empty -> EmptyState(input, clipLink, onInput, onSubmit, onPaste, trending, recent, onRemoveRecent, onClearRecent, onPick, onChannel, onLoadTrending, onPlayResult, streamingUrl, newItems, onNew, onMarkAllSeen)
            YouTubeUi.Resolving -> Skeleton()
            is YouTubeUi.Results -> ResultsState(state, input, onInput, onSubmit, onPaste, onPick, onChannel, onLoadMore, resultsList, onPlayResult, streamingUrl, followedUrls, followBusy, canonicalByPage, onToggleFollow)
            is YouTubeUi.Preview -> PreviewState(state, onReset, onMode, onSelect, onSubtitle, onDownload, onPlay, onChannel, onOpenRelated, onAddToQueue, onClosePreview)
            is YouTubeUi.Playlist -> PlaylistState(state, onReset, onToggle, onSelectAll, onQuality, onDownloadPlaylist, onCancelBatch, onChannel)
            is YouTubeUi.Error -> MessageState(
                icon = Icons.Rounded.ErrorOutline,
                title = stringResource(errorTitle(state.kind, state.search, state.channel)),
                body = stringResource(errorText(state.kind)),
                tone = MaterialTheme.colorScheme.error,
                action = {
                    Button(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.yt_retry)) }
                    TextButton(onClick = onReset, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.yt_another_link)) }
                },
            )
        }
    }
}

internal fun errorTitle(k: YouTubeErrorKind, search: Boolean = false, channel: Boolean = false) = when {
    k == YouTubeErrorKind.NOT_LINK -> R.string.yt_err_not_link_title
    k == YouTubeErrorKind.PLAYLIST -> R.string.yt_err_playlist_title
    channel -> R.string.yt_err_channel_title
    search -> R.string.yt_err_search_title
    else -> R.string.yt_err_title
}

private fun errorText(k: YouTubeErrorKind) = when (k) {
    YouTubeErrorKind.NOT_LINK -> R.string.yt_err_not_link
    YouTubeErrorKind.PLAYLIST -> R.string.yt_err_playlist
    YouTubeErrorKind.SIGN_IN -> R.string.yt_err_sign_in
    YouTubeErrorKind.UNAVAILABLE -> R.string.yt_err_unavailable
    YouTubeErrorKind.NETWORK -> R.string.yt_err_network
    YouTubeErrorKind.GENERIC -> R.string.yt_err_generic
}

@Composable
private fun LinkOrSearchField(
    input: String,
    onInput: (String) -> Unit,
    onSubmit: (String) -> Unit,
    onPaste: () -> Unit,
    focus: FocusRequester,
    down: FocusRequester = FocusRequester.Default,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val (field, setField) = rememberCursorField(input)
    TextField(
        value = field,
        onValueChange = { setField(it); if (it.text != input) onInput(it.text) },
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).focusRequester(focus).focusProperties { this.down = down }.dpadEscape(field)
            .then(if (focused) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, FieldShape) else Modifier),
        interactionSource = source,
        placeholder = { Text(stringResource(R.string.yt_field_hint), maxLines = 1) },
        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
        trailingIcon = {
            if (input.isEmpty()) {
                TextButton(onClick = onPaste, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(Icons.Rounded.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text(stringResource(R.string.yt_paste))
                }
            } else {
                IconButton(onClick = { onInput("") }) {
                    Icon(Icons.Rounded.Close, contentDescription = stringResource(if (YouTubeLinks.isSearchQuery(input)) R.string.yt_clear_search else R.string.yt_clear))
                }
            }
        },
        singleLine = true,
        shape = FieldShape,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(onGo = { keyboard?.hide(); if (input.isNotBlank()) onSubmit(input) }),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
    )
}

@Composable
private fun ResultsState(
    r: YouTubeUi.Results,
    input: String,
    onInput: (String) -> Unit,
    onSubmit: (String) -> Unit,
    onPaste: () -> Unit,
    onPick: (YouTubeSearchResult) -> Unit,
    onChannel: (String, String) -> Unit,
    onLoadMore: () -> Unit,
    listState: LazyListState,
    onPlay: (YouTubeSearchResult) -> Unit,
    streamingUrl: String?,
    followedUrls: Set<String>,
    followBusy: String?,
    canonicalByPage: Map<String, String>,
    onToggleFollow: (String) -> Unit,
) {
    val focus = remember { FocusRequester() }
    val followFocus = remember { FocusRequester() }
    // D-pad: the screen it replaced held focus (the Skeleton, the field); keep it on this screen instead of letting it fall to the first tab.
    val keys = androidx.compose.ui.platform.LocalInputModeManager.current.inputMode == androidx.compose.ui.input.InputMode.Keyboard
    LaunchedEffect(Unit) { if (keys) runCatching { focus.requestFocus() } }
    val loadMore by rememberUpdatedState(onLoadMore)
    // Asks while the last rows are on screen. The effect restarts when the item count or the loading flag changes, so
    // it also fires again at the bottom after an empty or all-duplicate page. After a failed page the restart stays quiet
    // (no tight retry loop): the next scroll step near the end is the retry.
    val failed = r.moreFailed
    LaunchedEffect(listState, r.items.size, r.loadingMore, failed) {
        var first = true
        snapshotFlow { listState.layoutInfo.let { it.visibleItemsInfo.lastOrNull()?.index?.minus(it.totalItemsCount) } }
            .collect {
                val restart = first
                first = false
                if (it != null && it >= -5 && !(restart && failed)) loadMore()
            }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val cols = resultColumns(maxWidth - 32.dp)
    // Rows hold `cols` results, so a new column count moves every result to another row: keep the first visible result in view.
    val headers = 1 + (if (r.channelUrl != null) 1 else 0) + (if (r.items.isEmpty()) 1 else 0)
    val firstRow = remember { FocusRequester() }
    var lastCols by rememberSaveable { mutableIntStateOf(cols) }
    val anchor = remember { intArrayOf(-1) }
    if (cols != lastCols) {
        anchor[0] = (listState.firstVisibleItemIndex - headers).coerceAtLeast(0) * lastCols
        lastCols = cols
    }
    LaunchedEffect(cols) {
        if (anchor[0] >= 0) {
            val row = (anchor[0] / cols + headers).coerceAtMost((listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0))
            anchor[0] = -1
            listState.scrollToItem(row)
        }
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { LinkOrSearchField(input, onInput, onSubmit, onPaste, focus, down = if (r.channelUrl != null) followFocus else if (r.items.isEmpty()) FocusRequester.Default else firstRow) }
        if (r.channelUrl != null) {
            item {
                val channelUrl = r.channelUrl
                val canonical = com.abhinavxt.debforge.domain.YouTubeChannelLinks.canonicalUrl(channelUrl) ?: canonicalByPage[channelUrl]
                val following = canonical != null && canonical in followedUrls
                val followBusy = followBusy == channelUrl
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(r.query, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).semantics { heading() })
                    val btnMod = Modifier.heightIn(min = 48.dp).focusRequester(followFocus)
                        .focusProperties { down = if (r.items.isEmpty()) FocusRequester.Default else firstRow }
                    val label: @Composable () -> Unit = {
                        if (followBusy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Text(stringResource(if (following) R.string.yt_following else R.string.yt_follow))
                    }
                    if (following) OutlinedButton(onClick = { onToggleFollow(channelUrl) }, enabled = !followBusy, modifier = btnMod, content = { label() })
                    else Button(onClick = { onToggleFollow(channelUrl) }, enabled = !followBusy, modifier = btnMod, content = { label() })
                }
            }
        }
        if (r.items.isEmpty()) {
            item {
                MessageState(
                    icon = Icons.Rounded.Search,
                    title = stringResource(R.string.yt_no_results_title),
                    body = stringResource(R.string.yt_no_results_body, r.query),
                    modifier = Modifier.heightIn(min = 280.dp),
                )
            }
        }
        // Rows of `cols` results: one column on a phone, more on a tablet. Plain rows keep the list state, paging and focus order as they were.
        items(r.items.chunked(cols), key = { it.first().url }) { chunk ->
            ResultCells(chunk, cols) { it, last -> ResultRow(it, onClick = { onPick(it) }, onChannel = if (r.channelUrl == null) onChannel else null, onPlay = { x -> onPlay(x) }, streaming = streamingUrl == it.url, lastColumn = last, rowFocus = firstRow.takeIf { _ -> it === r.items.first() }) }
        }
        if (r.loadingMore) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp)) }
    }
    }
}

/** How many result cards fit side by side in [width]: at least one, each at least 320dp wide. */
private fun resultColumns(width: androidx.compose.ui.unit.Dp): Int = ((width.value + 8f) / (320f + 8f)).toInt().coerceAtLeast(1)

@Composable
private fun ResultCells(chunk: List<YouTubeSearchResult>, cols: Int, cell: @Composable (YouTubeSearchResult, Boolean) -> Unit) {
    if (cols == 1) { chunk.forEach { cell(it, true) }; return }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        chunk.forEachIndexed { i, x -> Box(Modifier.weight(1f)) { cell(x, i == chunk.lastIndex) } }
        repeat(cols - chunk.size) { Spacer(Modifier.weight(1f)) }
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun ResultRow(
    r: YouTubeSearchResult,
    onClick: () -> Unit,
    onChannel: ((String, String) -> Unit)? = null,
    onPlay: (YouTubeSearchResult) -> Unit,
    streaming: Boolean = false,
    lastColumn: Boolean = false,
    rowFocus: FocusRequester? = null,
) {
    val source = remember { MutableInteractionSource() }
    val channelUrl = r.channelUrl?.takeIf { onChannel != null && !r.channel.isNullOrBlank() }
    // Arrows do not find a small target lying inside the row's bounds, so the row, channel and play button are linked by hand.
    // RIGHT from the last column has nowhere to go; without this it jumps up to a header button.
    val edge = if (lastColumn) FocusRequester.Cancel else FocusRequester.Default
    val rowFocus = rowFocus ?: remember { FocusRequester() }
    val channelFocus = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }
    val addFocus = remember { FocusRequester() }
    val addToPlaylist = LocalAddToPlaylist.current
    val addRequest = remember(r) { r.toAddRequest() }
    val canAdd = addToPlaylist != null && addRequest != null
    val addSource = remember { MutableInteractionSource() }
    val channelSource = remember { MutableInteractionSource() }
    val playSource = remember { MutableInteractionSource() }
    // The row and the channel name are siblings, not nested clickables, so TalkBack and TV focus see both.
    Box(Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RowShape).focusRing(source, RowShape)) {
        Spacer(
            Modifier.matchParentSize().semantics { contentDescription = r.title }
                .focusRequester(rowFocus)
                // Play is disabled while streaming and cannot take focus; leave RIGHT to the default search then.
                .focusProperties { right = if (channelUrl != null) channelFocus else if (!streaming) playFocus else edge }
                .clickable(interactionSource = source, indication = androidx.compose.foundation.LocalIndication.current, role = Role.Button, onClick = onClick),
        )
        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ResultThumb(r)
            Column(Modifier.weight(1f)) {
                Text(r.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (!r.channel.isNullOrBlank()) {
                    val style = MaterialTheme.typography.bodyMedium
                    val color = MaterialTheme.colorScheme.onSurfaceVariant
                    if (channelUrl != null) {
                        val label = stringResource(R.string.yt_open_channel, r.channel)
                        Text(
                            r.channel, style = style, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clip(RoundedCornerShape(8.dp))
                                .focusRequester(channelFocus)
                                .focusProperties { left = rowFocus; right = if (!streaming) playFocus else edge }
                                .focusRing(channelSource, RoundedCornerShape(8.dp))
                                .clickable(interactionSource = channelSource, indication = androidx.compose.foundation.LocalIndication.current, role = Role.Button) { onChannel?.invoke(r.channel, channelUrl) }
                                .semantics { contentDescription = label }.padding(vertical = 6.dp),
                        )
                    } else Text(r.channel, style = style, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (streaming) {
                    Text(stringResource(R.string.yt_streaming), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 2.dp))
                }
            }
            IconButton(
                onClick = { onPlay(r) }, enabled = !streaming,
                modifier = Modifier.focusRequester(playFocus).focusProperties { left = if (channelUrl != null) channelFocus else rowFocus; right = if (canAdd) addFocus else edge }
                    .focusRing(playSource, CircleShape),
                interactionSource = playSource
            ) {
                Icon(Icons.Rounded.PlayCircle, contentDescription = stringResource(R.string.yt_play_cd))
            }
            if (canAdd) IconButton(
                onClick = { addToPlaylist?.invoke(addRequest!!) },
                modifier = Modifier.focusRequester(addFocus).focusProperties { left = playFocus; right = edge }.focusRing(addSource, CircleShape),
                interactionSource = addSource
            ) {
                Icon(Icons.Rounded.PlaylistAdd, contentDescription = stringResource(R.string.media_add_to_playlist))
            }
        }
    }
}

/**
 * A related video: the body opens its preview. [onAddToQueue] is a separate trailing button, shown only when given
 * (it queues the video for audio playback).
 */
@Composable
private fun RelatedRow(
    r: YouTubeSearchResult,
    onClick: () -> Unit,
    onAddToQueue: ((YouTubeSearchResult) -> Unit)? = null,
) {
    val source = remember { MutableInteractionSource() }
    val queueSource = remember { MutableInteractionSource() }
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).clip(RowShape).focusRing(source, RowShape)
                .clickable(interactionSource = source, indication = androidx.compose.foundation.LocalIndication.current, role = Role.Button, onClick = onClick)
                .semantics(mergeDescendants = true) {}
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ResultThumb(r)
            Column(Modifier.weight(1f)) {
                Text(r.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (!r.channel.isNullOrBlank()) {
                    Text(r.channel, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (onAddToQueue != null) {
            IconButton(onClick = { onAddToQueue(r) }, modifier = Modifier.focusRing(queueSource, CircleShape), interactionSource = queueSource) {
                Icon(Icons.Rounded.PlaylistAdd, contentDescription = stringResource(R.string.yt_add_to_queue_cd))
            }
        }
    }
}

@Composable
private fun ResultThumb(r: YouTubeSearchResult) {
    Box(Modifier.width(128.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
        AsyncImage(model = r.thumbnailUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        if (r.durationSec > 0) {
            Surface(
                color = Color.Black.copy(alpha = 0.75f),
                contentColor = Color.White,
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp),
            ) {
                Text(durationText(r.durationSec), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp))
            }
        }
    }
}

@Composable
private fun PlaylistState(
    p: YouTubeUi.Playlist,
    onReset: () -> Unit,
    onToggle: (String) -> Unit,
    onSelectAll: (Boolean) -> Unit,
    onQuality: (DefaultQuality) -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onChannel: (String, String) -> Unit,
) {
    val busy = p.progress != null
    val firstFocus = remember { FocusRequester() }
    val cancelFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } } // TV: land on the first control
    // TV: the Download button turns into Cancel while the batch runs, so focus follows it, and returns to the list when the batch ends.
    var wasBusy by remember { mutableStateOf(false) }
    LaunchedEffect(busy) {
        if (busy) runCatching { cancelFocus.requestFocus() }
        else if (wasBusy) runCatching { firstFocus.requestFocus() }
        wasBusy = busy
    }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(p.title.ifBlank { stringResource(R.string.yt_playlist) }, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(stringResource(R.string.yt_selected_count, p.selected.size, p.items.size), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = onReset) { Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.yt_another_link)) }
                }
            }
            if (p.items.isEmpty()) {
                item {
                    MessageState(
                        icon = Icons.Rounded.PlayCircle,
                        title = stringResource(R.string.yt_playlist_empty_title),
                        body = stringResource(R.string.yt_playlist_empty_body),
                        modifier = Modifier.heightIn(min = 280.dp),
                    )
                }
            } else {
                item {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(DefaultQuality.BEST, DefaultQuality.P1080, DefaultQuality.P720, DefaultQuality.P480, DefaultQuality.AUDIO).forEachIndexed { i, q ->
                            FilterChip(
                                selected = p.quality == q,
                                onClick = { onQuality(q) },
                                enabled = !busy,
                                label = { Text(qualityLabel(q)) },
                                modifier = Modifier.heightIn(min = 48.dp).then(if (i == 0) Modifier.focusRequester(firstFocus) else Modifier),
                            )
                        }
                    }
                }
                item {
                    Row {
                        TextButton(onClick = { onSelectAll(true) }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.yt_select_all)) }
                        TextButton(onClick = { onSelectAll(false) }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.yt_select_none)) }
                    }
                }
            }
            items(p.items, key = { it.url }) { PlaylistRow(it, it.url in p.selected, !busy, onChannel = if (busy) null else onChannel) { onToggle(it.url) } }
        }
        // Pinned like the single-video preview: the list scrolls, the primary action does not.
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 3.dp, shadowElevation = 6.dp, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val progress = p.progress
                if (progress != null) {
                    val (n, total) = progress
                    // Announced by screen readers as it changes.
                    Text(
                        stringResource(R.string.yt_adding, n, total),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    LinearProgressIndicator(progress = { n.toFloat() / total }, modifier = Modifier.fillMaxWidth())
                    OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).focusRequester(cancelFocus)) {
                        Text(stringResource(R.string.action_cancel))
                    }
                } else {
                    Button(onClick = onDownload, enabled = p.selected.isNotEmpty(), modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                        Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.size(8.dp))
                        Text(
                            pluralStringResource(R.plurals.yt_download_n, p.selected.size, p.selected.size),
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun qualityLabel(q: DefaultQuality) = when (q) {
    DefaultQuality.BEST -> stringResource(R.string.set_yt_quality_best)
    DefaultQuality.AUDIO -> stringResource(R.string.set_yt_quality_audio)
    else -> "${q.height}p"
}

@Composable
private fun PlaylistRow(r: YouTubeSearchResult, checked: Boolean, enabled: Boolean, onChannel: ((String, String) -> Unit)? = null, onToggle: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RowShape).focusRing(source, RowShape)
            .toggleable(value = checked, enabled = enabled, interactionSource = source, indication = androidx.compose.foundation.LocalIndication.current, role = Role.Checkbox, onValueChange = { onToggle() })
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        ResultThumb(r)
        Column(Modifier.weight(1f)) {
            Text(r.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!r.channel.isNullOrBlank()) {
                val channelUrl = r.channelUrl?.takeIf { onChannel != null }
                Text(
                    r.channel, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = if (channelUrl != null) Modifier.channelLink(stringResource(R.string.yt_open_channel, r.channel), focusable = false) { onChannel?.invoke(r.channel, channelUrl) } else Modifier,
                )
            }
        }
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun EmptyState(
    input: String,
    clipLink: String?,
    onInput: (String) -> Unit,
    onSubmit: (String) -> Unit,
    onPaste: () -> Unit,
    trending: List<YouTubeSearchResult>,
    recent: List<String>,
    onRemoveRecent: (String) -> Unit,
    onClearRecent: () -> Unit,
    onPick: (YouTubeSearchResult) -> Unit,
    onChannel: (String, String) -> Unit,
    onLoadTrending: () -> Unit,
    onPlayResult: (YouTubeSearchResult) -> Unit,
    streamingUrl: String?,
    newItems: List<NewFeedItem>,
    onNew: (NewFeedItem) -> Unit,
    onMarkAllSeen: () -> Unit,
) {
    LaunchedEffect(Unit) { onLoadTrending() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } } // TV: land on the field
    Column(
        Modifier.fillMaxSize()
            // D-pad: RIGHT at the end of a row (the last remove button) stays put instead of jumping to the header buttons.
            .focusProperties { exit = { dir -> if (dir == androidx.compose.ui.focus.FocusDirection.Right) FocusRequester.Cancel else FocusRequester.Default } }
            .focusGroup()
            .verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        LinkOrSearchField(input, onInput, onSubmit, onPaste, focus)
        if (clipLink != null) {
            AssistChip(
                onClick = { keyboard?.hide(); onSubmit(clipLink) },
                label = { Text(stringResource(R.string.yt_paste_chip, linkHost(clipLink)), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = { Icon(Icons.Rounded.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp)) },
                modifier = Modifier.heightIn(min = 48.dp),
            )
        }
        // New uploads sit above the recent searches, which can fill the screen.
        if (newItems.isNotEmpty()) NewRow(newItems, onNew, onMarkAllSeen)
        val shown = recent.take(RECENT_SHOWN)
        if (shown.isNotEmpty()) {
            // The sentence stays above, shorter: the list below is what this screen is for once there is one.
            MessageState(
                icon = Icons.Rounded.PlayCircle,
                title = stringResource(R.string.yt_empty_title),
                body = stringResource(R.string.yt_empty_body),
                modifier = Modifier.heightIn(min = 0.dp),
            )
            RecentSearches(shown, onRun = { keyboard?.hide(); onSubmit(it) }, onRemove = onRemoveRecent, onClear = onClearRecent)
        } else {
            MessageState(
                icon = Icons.Rounded.PlayCircle,
                title = stringResource(R.string.yt_empty_title),
                body = stringResource(R.string.yt_empty_body),
                modifier = Modifier.heightIn(min = if (trending.isEmpty()) 280.dp else 160.dp),
            )
        }
        if (trending.isNotEmpty()) {
            Text(stringResource(R.string.yt_trending), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val cols = resultColumns(maxWidth)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    trending.chunked(cols).forEach { chunk ->
                        ResultCells(chunk, cols) { it, last -> ResultRow(it, onClick = { onPick(it) }, onChannel = onChannel, onPlay = { x -> onPlayResult(x) }, streaming = streamingUrl == it.url, lastColumn = last) }
                    }
                }
            }
        }
    }
}

private const val RECENT_SHOWN = 6

/** Unseen uploads from followed channels: a header with "Mark all seen" above tiles that scroll sideways. */
@Composable
private fun NewRow(items: List<NewFeedItem>, onOpen: (NewFeedItem) -> Unit, onMarkAllSeen: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.yt_new), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).semantics { heading() })
            TextButton(onClick = onMarkAllSeen, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.yt_new_mark_seen)) }
        }
        androidx.compose.foundation.lazy.LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 4.dp),
            modifier = Modifier.focusGroup(),
        ) {
            items(items, key = { it.subId to it.itemKey }) { item ->
                val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                Column(
                    Modifier.width(200.dp).clip(RoundedCornerShape(12.dp))
                        .focusRing(interaction, RoundedCornerShape(12.dp))
                        .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = { onOpen(item) })
                        .padding(4.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
                        AsyncImage(model = item.thumbUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        val d = item.durationSec ?: 0L
                        if (d > 0) Surface(color = Color.Black.copy(alpha = 0.75f), contentColor = Color.White, shape = RoundedCornerShape(6.dp), modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp)) {
                            Text(durationText(d), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp))
                        }
                    }
                    Text(item.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(item.subTitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** The user's last YouTube searches on the empty screen: tap to run one again, remove one, or clear all. */
@Composable
private fun RecentSearches(recent: List<String>, onRun: (String) -> Unit, onRemove: (String) -> Unit, onClear: () -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.search_recent),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            TextButton(onClick = onClear) { Text(stringResource(R.string.search_clear_recent)) }
        }
        recent.forEach { q ->
            val source = remember { MutableInteractionSource() }
            Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RowShape).focusRing(source, RowShape)) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // The row and the remove button are siblings, not nested clickables, so TalkBack and TV focus see both.
                    Row(
                        Modifier.weight(1f).heightIn(min = 48.dp)
                            .clip(RowShape)
                            .clickable(interactionSource = source, indication = androidx.compose.foundation.LocalIndication.current, role = Role.Button, onClick = { onRun(q) }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.History, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(16.dp))
                        Text(q, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    }
                    IconButton(onClick = { onRemove(q) }) {
                        Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.search_remove_recent, q))
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewState(
    p: YouTubeUi.Preview,
    onReset: () -> Unit,
    onMode: (Boolean) -> Unit,
    onSelect: (FormatChoice) -> Unit,
    onSubtitle: (YouTubeSubtitle?) -> Unit,
    onDownload: () -> Unit,
    onPlay: () -> Unit,
    onChannel: (String, String) -> Unit,
    onOpenRelated: (YouTubeSearchResult) -> Unit = {},
    onAddToQueue: (YouTubeSearchResult) -> Unit = {},
    onClosePreview: () -> Unit = {},
) {
    val audio = p.selected.isAudio
    val rows = p.choices.filter { it.isAudio == audio }
    val firstFocus = remember { FocusRequester() }
    val inputMode = androidx.compose.ui.platform.LocalInputModeManager.current
    // TV / keyboard: land on the default row. Touch: no focus request, it would scroll the list a moment after the preview appears.
    LaunchedEffect(p.video.id) {
        if (inputMode.inputMode == androidx.compose.ui.input.InputMode.Keyboard) runCatching { firstFocus.requestFocus() }
    }
    val thumb: @Composable (Modifier) -> Unit = { mod ->
        Box(mod.aspectRatio(16f / 9f).clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
            AsyncImage(
                model = p.video.thumbnailUrl,
                contentDescription = stringResource(R.string.yt_thumbnail),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (p.video.durationSec > 0) {
                Surface(
                    color = Color.Black.copy(alpha = 0.75f),
                    contentColor = Color.White,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
                ) {
                    Text(durationText(p.video.durationSec), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                }
            }
        }
    }
    val header: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(p.video.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val uploaderUrl = p.video.uploaderUrl?.takeIf { p.video.uploader.isNotBlank() }
                Text(
                    p.video.uploader, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = if (uploaderUrl != null) Modifier.channelLink(stringResource(R.string.yt_open_channel, p.video.uploader)) { onChannel(p.video.uploader, uploaderUrl) } else Modifier,
                )
            }
            IconButton(onClick = onClosePreview) { Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.yt_another_link)) }
        }
    }
    val choices: @Composable () -> Unit = {
        // A video with only audio (or only video) choices has nothing to switch between.
        if (p.choices.any { it.isAudio } && p.choices.any { !it.isAudio }) ModeToggle(audio, onMode)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            rows.forEachIndexed { i, c ->
                FormatRow(c, c == p.selected, best = i == 0, onClick = { onSelect(c) },
                    modifier = if (c == p.selected) Modifier.focusRequester(firstFocus) else Modifier)
            }
        }
        if (!audio && p.video.subtitles.isNotEmpty()) SubtitleRow(p.video.subtitles, p.subtitle, onSubtitle)
    }
    // Part of the preview's own scroll (no nested list), so swipe-down-to-close still only starts at the top.
    val upNext: @Composable () -> Unit = {
        if (p.video.related.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.yt_up_next), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 4.dp))
                p.video.related.forEach { r -> RelatedRow(r, onClick = { onOpenRelated(r) }, onAddToQueue = onAddToQueue) }
            }
        }
    }
    // A different video starts at the top, not where the previous one was scrolled to.
    val scrollMain = remember(p.video.id) { ScrollState(0) }
    val scrollLeft = remember(p.video.id) { ScrollState(0) }
    val scrollRight = remember(p.video.id) { ScrollState(0) }
    // Pinned: the list scrolls, the primary action does not.
    val addToPlaylist = LocalAddToPlaylist.current
    val bar: @Composable (Modifier) -> Unit = { mod ->
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 3.dp, shadowElevation = 6.dp, modifier = mod.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onPlay, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
                    Icon(Icons.Rounded.PlayCircle, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(
                        if (audio) stringResource(R.string.yt_listen) else stringResource(R.string.yt_play_quality, choiceLabel(p.selected)),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                OutlinedButton(onClick = onDownload, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
                    // Two lines when it does not fit beside Play, so quality and size stay readable.
                    Text(
                        stringResource(R.string.yt_download_format, choiceLabel(p.selected), sizeText(p.selected)),
                        maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
                if (addToPlaylist != null) OutlinedButton(onClick = { addToPlaylist(p.video.toAddRequest(audio)) }, modifier = Modifier.heightIn(min = 56.dp)) {
                    Icon(Icons.Rounded.PlaylistAdd, contentDescription = stringResource(R.string.media_add_to_playlist))
                }
            }
        }
    }
    // Drag down to close, like Back / X; scrolled lists only start it once they are at the top.
    val dismiss = rememberSwipeDown(onClosePreview)
    BoxWithConstraints(Modifier.fillMaxSize().swipeDownToDismiss(dismiss)) {
        if (maxWidth > maxHeight && maxWidth >= 600.dp) {
            // Wide: picture and title on the left, choices and the action bar on the right.
            Row(Modifier.fillMaxSize()) {
                Column(
                    Modifier.weight(1f).fillMaxHeight().verticalScroll(scrollLeft)
                        .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    thumb(Modifier.fillMaxWidth())
                    header()
                }
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Column(
                        Modifier.weight(1f).fillMaxWidth().verticalScroll(scrollRight).animateContentSize()
                            .padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) { choices(); upNext() }
                    bar(Modifier.padding(start = 8.dp, end = 16.dp).clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)))
                }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(scrollMain).animateContentSize()
                        .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    thumb(Modifier.fillMaxWidth())
                    header()
                    choices()
                    upNext()
                }
                bar(Modifier)
            }
        }
    }
}

@Composable
private fun subtitleName(s: YouTubeSubtitle) = if (s.auto) stringResource(R.string.yt_subtitles_auto, s.label) else s.label

/** Video mode only: None, or one language to save beside the video. */
@Composable
private fun SubtitleRow(options: List<YouTubeSubtitle>, chosen: YouTubeSubtitle?, onChoose: (YouTubeSubtitle?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.yt_subtitles), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Box {
            TextButton(onClick = { open = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(chosen?.let { subtitleName(it) } ?: stringResource(R.string.yt_subtitles_none), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.yt_subtitles_none)) }, onClick = { open = false; onChoose(null) })
                options.forEach { o -> DropdownMenuItem(text = { Text(subtitleName(o)) }, onClick = { open = false; onChoose(o) }) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModeToggle(audio: Boolean, onMode: (Boolean) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        listOf(false to R.string.yt_mode_video, true to R.string.yt_mode_audio).forEachIndexed { i, (isAudio, label) ->
            SegmentedButton(
                selected = audio == isAudio,
                onClick = { onMode(isAudio) },
                shape = SegmentedButtonDefaults.itemShape(i, 2),
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(label)) }
        }
    }
}

@Composable
private fun FormatRow(c: FormatChoice, selected: Boolean, best: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    Surface(
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RowShape,
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RowShape)
            .then(if (focused) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RowShape) else Modifier)
            .selectable(selected = selected, interactionSource = source, indication = androidx.compose.foundation.LocalIndication.current, role = Role.RadioButton, onClick = onClick),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = null)
            Spacer(Modifier.size(12.dp))
            Text(choiceLabel(c), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (best) {
                Text(stringResource(R.string.yt_best), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.size(12.dp))
            }
            Text(sizeText(c), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Skeleton() {
    val pulse: State<Float> = rememberInfiniteTransition(label = "yt-skeleton").animateFloat(
        initialValue = 0.35f, targetValue = 0.8f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pulse",
    )
    val loading = stringResource(R.string.yt_loading)
    val tone = MaterialTheme.colorScheme.surfaceContainerHigh
    // D-pad: the field that held focus just left; hold it here so it does not fall to the first tab while loading.
    val hold = remember { FocusRequester() }
    val keys = androidx.compose.ui.platform.LocalInputModeManager.current.inputMode == androidx.compose.ui.input.InputMode.Keyboard
    LaunchedEffect(Unit) { if (keys) runCatching { hold.requestFocus() } }
    Column(
        Modifier.fillMaxSize().focusRequester(hold).focusable().padding(horizontal = 16.dp, vertical = 8.dp).graphicsLayer { alpha = pulse.value }
            .semantics { contentDescription = loading },
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(MaterialTheme.shapes.large).background(tone))
        Box(Modifier.fillMaxWidth(0.8f).heightIn(min = 20.dp).clip(RoundedCornerShape(6.dp)).background(tone))
        Box(Modifier.fillMaxWidth(0.4f).heightIn(min = 14.dp).clip(RoundedCornerShape(6.dp)).background(tone))
        repeat(4) { Box(Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RowShape).background(tone)) }
    }
}

// --- previews ---------------------------------------------------------------------------

private val fakeVideo = YouTubeVideo("dQw4w9WgXcQ", "A very long example video title that wraps onto a second line to show truncation", "Example Channel", 213, null, emptyList())
private val fakeChoices = listOf(
    FormatChoice(137, 140, "mp4", 212_000_000, false, 1080, 60, Container.MP4, 128_000, isDefault = true),
    FormatChoice(136, 140, "mp4", 120_000_000, true, 720, 30, Container.MP4, 128_000),
    FormatChoice(null, 140, "m4a", 3_400_000, false, 0, 0, Container.M4A, 128_000),
    FormatChoice(null, 251, "weba", 3_000_000, true, 0, 0, Container.WEBM, 130_000),
)

@Composable
private fun PreviewHost(ui: YouTubeUi, clip: String? = null, input: String = "") {
    MaterialTheme {
        Surface {
            YouTubeContent(ui, input, clip, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
        }
    }
}

@Preview(showBackground = true) @Composable private fun EmptyPreview() = PreviewHost(YouTubeUi.Empty, clip = "https://youtu.be/dQw4w9WgXcQ")
@Preview(showBackground = true) @Composable private fun ResolvingPreview() = PreviewHost(YouTubeUi.Resolving)
@Preview(showBackground = true, heightDp = 900) @Composable private fun VideoPreview() = PreviewHost(YouTubeUi.Preview(fakeVideo, fakeChoices, fakeChoices[0]))
@Preview(showBackground = true, heightDp = 900) @Composable private fun AudioPreview() = PreviewHost(YouTubeUi.Preview(fakeVideo, fakeChoices, fakeChoices[2]))
@Preview(showBackground = true, heightDp = 700) @Composable private fun ResultsPreview() = PreviewHost(YouTubeUi.Results("big buck bunny", listOf(YouTubeSearchResult("u", fakeVideo.title, "Example Channel", 596, null))), input = "big buck bunny")
@Preview(showBackground = true) @Composable private fun ErrorPreview() = PreviewHost(YouTubeUi.Error(YouTubeErrorKind.PLAYLIST))
@Preview(showBackground = true, heightDp = 700) @Composable private fun PlaylistPreview() = PreviewHost(
    YouTubeUi.Playlist("Example playlist", listOf(YouTubeSearchResult("u", fakeVideo.title, "Example Channel", 596, null), YouTubeSearchResult("v", "Second video", null, 60, null)), setOf("u"), DefaultQuality.P720),
)
