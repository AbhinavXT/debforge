package com.abhinavxt.debforge.ui.media

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Podcasts
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material3.AlertDialog
import com.abhinavxt.debforge.data.podcast.podcastTrackId
import com.abhinavxt.debforge.data.subscriptions.FeedItemEntity
import com.abhinavxt.debforge.domain.DirectLinkParser
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.abhinavxt.debforge.R
import com.abhinavxt.debforge.data.subscriptions.AutoDownload
import com.abhinavxt.debforge.player.formatClock
import com.abhinavxt.debforge.ui.components.GeneratedArt
import com.abhinavxt.debforge.ui.components.MessageState
import com.abhinavxt.debforge.ui.player.focusRing
import java.text.DateFormat
import java.util.Date

/** Discover -> Podcasts: the followed feeds, and inside one the episodes. Back from a feed returns to the list. */
@Composable
internal fun PodcastsPane(
    playingId: String?,
    tell: (String) -> Unit,
    viewModel: PodcastsViewModel = hiltViewModel()
) {
    val podcasts by viewModel.podcasts.collectAsStateWithLifecycle()
    val openId by viewModel.openId.collectAsStateWithLifecycle()
    val detail by viewModel.detail.collectAsStateWithLifecycle()
    val add by viewModel.add.collectAsStateWithLifecycle()
    var showAdd by remember { mutableStateOf(false) }
    val msgQueued = stringResource(R.string.podcasts_msg_queued)
    val msgAlready = stringResource(R.string.podcasts_msg_already)
    val msgFailed = stringResource(R.string.podcasts_msg_failed)
    val msgNoFile = stringResource(R.string.podcasts_msg_no_file)
    val msgInQueue = stringResource(R.string.podcasts_msg_in_queue)
    val msgInQueueAlready = stringResource(R.string.podcasts_msg_in_queue_already)
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        viewModel.bulkQueued.collect { n ->
            tell(context.resources.getQuantityString(R.plurals.podcasts_msg_queued_n, n, n))
        }
    }
    LaunchedEffect(Unit) {
        viewModel.messages.collect {
            tell(
                when (it) {
                    PodcastMessage.QUEUED -> msgQueued
                    PodcastMessage.ALREADY_QUEUED -> msgAlready
                    PodcastMessage.DOWNLOAD_FAILED -> msgFailed
                    PodcastMessage.NO_FILE -> msgNoFile
                    PodcastMessage.ADDED_TO_QUEUE -> msgInQueue
                    PodcastMessage.ALREADY_IN_QUEUE -> msgInQueueAlready
                }
            )
        }
    }
    // Following (or finding it already followed) opens the feed and closes the dialog.
    LaunchedEffect(openId) { if (openId != null) showAdd = false }
    BackHandler(openId != null) { viewModel.close() }

    val d = detail
    if (openId != null && d != null) {
        PodcastDetailView(d, playingId, viewModel)
    } else {
        val list = podcasts
        if (list == null) {
            Spacer(Modifier.fillMaxSize())
        } else if (list.isEmpty()) {
            MessageState(
                icon = Icons.Rounded.Podcasts,
                title = stringResource(R.string.podcasts_empty_title),
                body = stringResource(R.string.podcasts_empty_body),
                action = { Button(onClick = { viewModel.resetAdd(); showAdd = true }) { Text(stringResource(R.string.podcasts_add)) } }
            )
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                item(key = "add") {
                    TextButton(onClick = { viewModel.resetAdd(); showAdd = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Icon(Icons.Rounded.Add, null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.podcasts_add))
                    }
                }
                items(list, key = { it.sub.id }) { row ->
                    PodcastListRow(row) { viewModel.open(row.sub.id) }
                }
            }
        }
    }

    if (showAdd) {
        AddPodcastDialog(add, onFollow = viewModel::follow, onDismiss = { showAdd = false; viewModel.resetAdd() })
    }
}

@Composable
private fun Cover(url: String?, title: String, modifier: Modifier) {
    if (url != null) AsyncImage(url, null, modifier, contentScale = ContentScale.Crop)
    else GeneratedArt(title, "PODCAST", modifier, showTitle = false)
}

@Composable
private fun PodcastListRow(row: PodcastRow, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    val source = remember { MutableInteractionSource() }
    Row(
        Modifier.fillMaxWidth().clip(shape).focusRing(source, shape)
            .clickable(interactionSource = source, indication = androidx.compose.foundation.LocalIndication.current, onClick = onClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Cover(row.sub.artworkUrl, row.sub.title, Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)))
        Spacer(Modifier.width(12.dp))
        Text(row.sub.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (row.unseen > 0) {
            Text(
                stringResource(R.string.podcasts_new_count, row.unseen),
                style = MaterialTheme.typography.labelMedium, color = cs.onPrimary,
                modifier = Modifier.padding(start = 8.dp).background(cs.primary, CircleShape).padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PodcastDetailView(d: PodcastDetail, playingId: String?, vm: PodcastsViewModel) {
    val cs = MaterialTheme.colorScheme
    val sub = d.sub
    var confirmUnfollow by remember { mutableStateOf(false) }
    // Keys of the episodes the open dialog asked about, so what is queued is what was shown. Null: no dialog.
    var confirmKeys by rememberSaveable { mutableStateOf<ArrayList<String>?>(null) }
    val running by vm.bulkRunning.collectAsStateWithLifecycle()
    val toQueue = remember(d.episodes) { episodesToQueue(d.episodes) }
    confirmKeys?.let { keys ->
        AlertDialog(
            onDismissRequest = { confirmKeys = null },
            title = { Text(pluralStringResource(R.plurals.podcasts_download_all_n, keys.size, keys.size)) },
            confirmButton = {
                TextButton(onClick = { confirmKeys = null; vm.downloadAll(sub, episodesByKeys(d.episodes, keys)) }) {
                    Text(stringResource(R.string.podcasts_download))
                }
            },
            dismissButton = { TextButton(onClick = { confirmKeys = null }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }
    if (confirmUnfollow) {
        AlertDialog(
            onDismissRequest = { confirmUnfollow = false },
            title = { Text(stringResource(R.string.podcasts_unfollow_q, sub.title)) },
            confirmButton = {
                TextButton(onClick = { confirmUnfollow = false; vm.unfollow(sub.id) }) {
                    Text(stringResource(R.string.follow_unfollow), color = cs.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmUnfollow = false }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        item(key = "header") {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = vm::close) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.podcasts_back)) }
                    Cover(sub.artworkUrl, sub.title, Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)))
                    Spacer(Modifier.width(12.dp))
                    Text(sub.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.set_following_auto), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                    Spacer(Modifier.width(8.dp))
                    listOf(AutoDownload.OFF to R.string.set_auto_dl_off, AutoDownload.AUDIO to R.string.set_auto_dl_audio).forEach { (mode, label) ->
                        FilterChip(
                            selected = sub.autoDownload == mode,
                            onClick = { vm.setAutoDownload(sub.id, mode) },
                            label = { Text(stringResource(label)) },
                            modifier = Modifier.padding(end = 8.dp).heightIn(min = 48.dp)
                        )
                    }
                }
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (d.unseen > 0) TextButton(onClick = { vm.markAllSeen(sub.id) }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Icon(Icons.Rounded.CheckCircle, null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.podcasts_mark_seen))
                    }
                    TextButton(
                        onClick = {
                            if (needsDownloadAllConfirm(toQueue.size)) confirmKeys = ArrayList(toQueue.map { it.itemKey })
                            else vm.downloadAll(sub, toQueue)
                        },
                        enabled = toQueue.isNotEmpty() && !running,
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Icon(Icons.Rounded.Download, null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (toQueue.isEmpty()) stringResource(R.string.podcasts_download)
                            else stringResource(R.string.sheet_download_all, toQueue.size)
                        )
                    }
                    TextButton(onClick = { confirmUnfollow = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.follow_unfollow), color = cs.error)
                    }
                }
            }
        }
        if (d.episodes.isEmpty()) item(key = "none") {
            Text(
                stringResource(R.string.podcasts_no_episodes), style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurfaceVariant, modifier = Modifier.padding(16.dp)
            )
        }
        items(d.episodes, key = { it.item.itemKey }) { ep ->
            EpisodeRowView(
                sub.title, ep,
                playing = playingId != null && playingId in episodeTrackIds(sub.url, ep.item),
                onPlay = { vm.play(sub, ep.item) },
                onQueue = { vm.addToQueue(sub, ep.item) },
                onDownload = { vm.download(sub, ep.item) }
            )
        }
    }
}

@Composable
private fun EpisodeRowView(
    podcast: String, ep: EpisodeRow, playing: Boolean,
    onPlay: () -> Unit, onQueue: () -> Unit, onDownload: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    val item = ep.item
    val source = remember { MutableInteractionSource() }
    val rowFocus = remember { FocusRequester() }
    val menuFocus = remember { FocusRequester() }
    var menu by remember { mutableStateOf(false) }
    val date = if (item.publishedAt > 0) DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(item.publishedAt)) else null
    val duration = item.durationSec?.takeIf { it > 0 }?.let { formatClock(it * 1000) }
    val state = when (ep.download) {
        EpisodeDownload.DONE -> stringResource(R.string.podcasts_downloaded)
        EpisodeDownload.ACTIVE -> stringResource(R.string.podcasts_downloading)
        EpisodeDownload.NONE, EpisodeDownload.FAILED -> null
    }
    val sub = listOfNotNull(date, duration, state).joinToString(" · ")
    Row(
        Modifier.fillMaxWidth()
            .background(if (playing) cs.primaryContainer.copy(alpha = 0.3f) else androidx.compose.ui.graphics.Color.Transparent, shape)
            .focusRequester(rowFocus)
            .focusProperties { right = menuFocus }
            .clip(shape).focusRing(source, shape)
            .clickable(interactionSource = source, indication = androidx.compose.foundation.LocalIndication.current, onClick = onPlay)
            .padding(start = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
            Cover(item.thumbUrl, podcast, Modifier.fillMaxSize())
            if (playing) Box(Modifier.fillMaxSize().background(cs.primary.copy(alpha = 0.7f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.GraphicEq, stringResource(R.string.media_playing), tint = cs.onPrimary)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.title, style = MaterialTheme.typography.titleMedium, color = if (playing) cs.primary else cs.onSurface,
                maxLines = 2, overflow = TextOverflow.Ellipsis
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (item.isNew) Text(
                    stringResource(R.string.podcasts_new), style = MaterialTheme.typography.labelSmall, color = cs.onPrimary,
                    modifier = Modifier.padding(end = 6.dp).background(cs.primary, CircleShape).padding(horizontal = 8.dp, vertical = 2.dp)
                )
                if (ep.download == EpisodeDownload.DONE) Icon(
                    Icons.Rounded.Download, null, tint = cs.primary, modifier = Modifier.padding(end = 4.dp).size(14.dp)
                )
                if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Box {
            val menuSource = remember { MutableInteractionSource() }
            IconButton(
                onClick = { menu = true },
                modifier = Modifier.focusRequester(menuFocus).focusProperties { left = rowFocus }.focusRing(menuSource, CircleShape),
                interactionSource = menuSource
            ) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.media_more)) }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_play)) },
                    leadingIcon = { Icon(Icons.Rounded.PlayArrow, null) },
                    onClick = { menu = false; onPlay() }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.podcasts_add_queue)) },
                    leadingIcon = { Icon(Icons.Rounded.PlaylistAdd, null) },
                    onClick = { menu = false; onQueue() }
                )
                if (ep.download == EpisodeDownload.NONE || ep.download == EpisodeDownload.FAILED) DropdownMenuItem(
                    text = { Text(stringResource(R.string.podcasts_download)) },
                    leadingIcon = { Icon(Icons.Rounded.Download, null) },
                    onClick = { menu = false; onDownload() }
                )
            }
        }
    }
}

@Composable
private fun AddPodcastDialog(state: AddState, onFollow: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current
    val loading = state == AddState.Loading
    val submit = { if (text.isNotBlank() && !loading) onFollow(text) }
    AlertDialog(
        onDismissRequest = { if (!loading) onDismiss() },
        icon = { Icon(Icons.Rounded.Podcasts, null) },
        title = { Text(stringResource(R.string.podcasts_add_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    label = { Text(stringResource(R.string.podcasts_add_hint)) },
                    singleLine = true, enabled = !loading,
                    isError = state is AddState.Error,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { submit() }),
                    modifier = Modifier.fillMaxWidth()
                )
                if (state is AddState.Error) Text(
                    state.message, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)
                )
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = { clipboard.getText()?.text?.let { text = it.trim() } },
                        enabled = !loading, modifier = Modifier.heightIn(min = 48.dp)
                    ) { Text(stringResource(R.string.podcasts_paste)) }
                    if (loading) {
                        Spacer(Modifier.width(12.dp))
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = submit, enabled = text.isNotBlank() && !loading) { Text(stringResource(R.string.podcasts_follow)) } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !loading) { Text(stringResource(R.string.action_cancel)) } }
    )
}

/** The ids an episode plays under: its stream id, or the download id once it is a file on the device. */
private fun episodeTrackIds(feedUrl: String, item: FeedItemEntity): List<String> =
    listOfNotNull(podcastTrackId(feedUrl, item.itemKey), item.mediaUrl?.let { DirectLinkParser.itemId(it) })
