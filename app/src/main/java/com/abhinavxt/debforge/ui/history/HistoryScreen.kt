package com.abhinavxt.debforge.ui.history

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.abhinavxt.debforge.R
import com.abhinavxt.debforge.data.history.HistoryEntity
import com.abhinavxt.debforge.data.history.HistoryKind
import com.abhinavxt.debforge.download.artworkFile
import com.abhinavxt.debforge.ui.browse.playExternally
import com.abhinavxt.debforge.ui.components.BannerCard
import com.abhinavxt.debforge.ui.components.MessageState
import com.abhinavxt.debforge.ui.components.ScreenHeader
import com.abhinavxt.debforge.player.PlayerActivity

/**
 * Recently played audio and video, newest first. Tap replays; the trash button removes one row; the menu pauses
 * recording or clears everything. Reached from the Media tab header, so it is not a tab of its own.
 */
@Composable
fun HistoryScreen(
    onOpenYouTube: (String) -> Unit,
    viewModel: HistoryViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val paused by viewModel.paused.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val removeLabel = stringResource(R.string.history_remove)
    val noPlayer = stringResource(R.string.msg_no_player)
    var confirmClear by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val menuFocus = remember { FocusRequester() }
    val keys = LocalInputModeManager.current.inputMode == InputMode.Keyboard

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is HistoryEvent.Play ->
                    if (viewModel.useInternalPlayer.value) runCatching { context.startActivity(PlayerActivity.intent(context, event.request)) }
                    else if (!context.playExternally(event.request)) scope.launch { snackbar.showSnackbar(noPlayer) }
                is HistoryEvent.OpenYouTube -> onOpenYouTube(event.url)
                is HistoryEvent.Unplayable -> {
                    snackbar.currentSnackbarData?.dismiss()
                    // Its own coroutine: showSnackbar suspends until dismissed, and later events must not wait for it.
                    scope.launch {
                        val result = snackbar.showSnackbar(
                            message = event.text,
                            actionLabel = removeLabel,
                            withDismissAction = true,
                            duration = SnackbarDuration.Long
                        )
                        if (result == SnackbarResult.ActionPerformed) viewModel.remove(event.itemId)
                    }
                }
            }
        }
    }

    // The last row was removed while it had focus: the menu button is the next sensible stop.
    LaunchedEffect(state.loaded && state.items.isEmpty()) { if (keys && state.loaded && state.items.isEmpty()) runCatching { menuFocus.requestFocus() } }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader(stringResource(R.string.history_title)) {
                var menu by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.focusRequester(menuFocus)) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.media_more)) }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.history_pause)) },
                            trailingIcon = { Checkbox(checked = paused, onCheckedChange = null) },
                            onClick = { menu = false; viewModel.setPaused(!paused) }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.history_clear)) },
                            enabled = state.items.isNotEmpty(),
                            onClick = { menu = false; confirmClear = true }
                        )
                    }
                }
            }
            if (paused) {
                BannerCard(
                    icon = Icons.Rounded.Pause,
                    text = stringResource(R.string.history_paused_banner),
                    container = MaterialTheme.colorScheme.secondaryContainer,
                    onContainer = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
            when {
                // First read pending: show nothing rather than a wrong "Nothing played yet".
                !state.loaded -> Spacer(Modifier.fillMaxSize())
                state.items.isEmpty() -> MessageState(Icons.Rounded.History, stringResource(R.string.history_empty), null)
                else -> HistoryList(state.items, onReplay = viewModel::replay, onRemove = viewModel::remove)
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp))
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            text = { Text(stringResource(R.string.history_clear_confirm)) },
            confirmButton = {
                TextButton(onClick = { confirmClear = false; viewModel.clear() }) { Text(stringResource(R.string.action_clear)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
}

@Composable
private fun HistoryList(items: List<HistoryEntity>, onReplay: (HistoryEntity) -> Unit, onRemove: (String) -> Unit) {
    // D-pad / keyboard: start on the newest row, so the first arrow key or Select works at once. Not on touch: it would show a focus ring.
    val requesters = remember { HashMap<String, FocusRequester>() }
    fun requesterFor(id: String) = requesters.getOrPut(id) { FocusRequester() }
    val keys = LocalInputModeManager.current.inputMode == InputMode.Keyboard
    LaunchedEffect(Unit) { if (keys) runCatching { requesterFor(items.first().itemId).requestFocus() } }
    // After a row is removed, focus moves to the row that took its place (the previous one when it was last).
    var focusAfter by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(items) {
        val at = focusAfter ?: return@LaunchedEffect
        focusAfter = null
        requesters.keys.retainAll(items.map { it.itemId }.toSet())
        val target = items.getOrNull(at.coerceAtMost(items.lastIndex)) ?: return@LaunchedEffect
        withFrameNanos { }
        if (keys) runCatching { requesterFor(target.itemId).requestFocus() }
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        itemsIndexed(items, key = { _, it -> it.itemId }, contentType = { _, it -> it.kind }) { index, e ->
            HistoryRow(
                e,
                onClick = { onReplay(e) },
                onRemove = { focusAfter = index; onRemove(e.itemId) },
                modifier = Modifier.focusRequester(requesterFor(e.itemId))
            )
        }
    }
}

@Composable
private fun HistoryRow(e: HistoryEntity, onClick: () -> Unit, onRemove: () -> Unit, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val time = remember(e.playedAt) {
        DateUtils.getRelativeTimeSpanString(e.playedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
    }
    val sub = listOfNotNull(e.subtitle?.takeIf { it.isNotBlank() }, stringResource(originLabelRes(e.origin)), time).joinToString(" · ")
    val progress = historyProgress(e.positionMs, e.durationMs)
    // The main area and the delete button are sibling focus targets, so DPAD_RIGHT from the row reaches the button.
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(
            modifier
                .weight(1f)
                .clip(shape)
                .then(if (focused) Modifier.border(2.dp, cs.primary, shape) else Modifier)
                .clickable(interactionSource = source, indication = androidx.compose.material3.ripple(), onClick = onClick)
                .padding(start = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Artwork(e, Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(e.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(sub, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (progress != null && progress > 0f) {
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(3.dp))
                }
            }
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Rounded.Delete, stringResource(R.string.history_remove), tint = cs.onSurfaceVariant)
        }
    }
}

/** Local art (a download's thumbnail) or a web thumbnail; never another scheme. Else an icon for the kind. */
@Composable
private fun Artwork(e: HistoryEntity, modifier: Modifier) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val file = remember(e.itemId) { artworkFile(context, e.itemId).takeIf { it.exists() } }
    val url = e.artworkUrl?.takeIf { isSafeArtworkUrl(it) }
    when {
        file != null -> AsyncImage(file, null, modifier, contentScale = ContentScale.Crop)
        url != null -> AsyncImage(url, null, modifier, contentScale = ContentScale.Crop)
        else -> Box(modifier.background(cs.surfaceContainerHigh), contentAlignment = Alignment.Center) {
            Icon(if (e.kind == HistoryKind.AUDIO) Icons.Rounded.GraphicEq else Icons.Rounded.PlayArrow, null, tint = cs.onSurfaceVariant)
        }
    }
}
