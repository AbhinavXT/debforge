package com.abhinavxt.debforge.ui.video

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.NotificationsNone
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButton
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import coil.compose.AsyncImage
import com.abhinavxt.debforge.ui.youtube.durationText
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.verticalScroll
import com.abhinavxt.debforge.R
import com.abhinavxt.debforge.domain.Container
import com.abhinavxt.debforge.domain.FormatChoice
import com.abhinavxt.debforge.domain.YouTubeSearchResult
import com.abhinavxt.debforge.domain.YouTubeVideo
import com.abhinavxt.debforge.ui.player.focusRing
import com.abhinavxt.debforge.ui.youtube.ChannelAvatar
import com.abhinavxt.debforge.ui.youtube.ChannelName
import com.abhinavxt.debforge.ui.youtube.PlayQualitySheet
import com.abhinavxt.debforge.ui.youtube.QualityPickSheet
import com.abhinavxt.debforge.ui.youtube.FormatChoicesBlock
import com.abhinavxt.debforge.ui.youtube.LocalAddToPlaylist
import com.abhinavxt.debforge.ui.youtube.RowShape
import com.abhinavxt.debforge.ui.youtube.choiceLabel
import com.abhinavxt.debforge.ui.youtube.isAudio
import com.abhinavxt.debforge.ui.youtube.sizeText
import com.abhinavxt.debforge.ui.youtube.toAddRequest

/**
 * The content of the watch page: title, source, action chips and the list under or beside the video.
 * The video itself is drawn by the host above (phone) or in its own pane (wide); this is everything else.
 * [wide] puts the details on the left and the list on the right, each scrolling on its own.
 */
@Composable
fun WatchPage(
    state: VideoUiState,
    details: WatchDetails,
    actions: WatchActions,
    wide: Boolean,
    modifier: Modifier = Modifier,
    /** [wide]: room above the left pane's details for the video the host draws there. */
    headerTopPadding: androidx.compose.ui.unit.Dp = 0.dp,
    /** [wide]: room above the right pane's list (the status bar). */
    listTopPadding: androidx.compose.ui.unit.Dp = 0.dp,
) {
    var sheet by rememberSaveable { mutableStateOf(false) }
    val onChip: (WatchChip) -> Unit = rememberChipHandler(state, details, actions) { sheet = true }
    val header: LazyListScope.() -> Unit = { item(key = "header") { WatchHeader(state, details, actions, onChip) } }
    val list: LazyListScope.() -> Unit = { watchList(state, details, actions) }
    if (details.loading) {
        Box(modifier.fillMaxSize()) { WatchSkeleton() }
        return
    }
    if (wide) {
        Row(modifier.fillMaxSize()) {
            LazyColumn(Modifier.weight(1f).fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(top = headerTopPadding)) { header() }
            LazyColumn(Modifier.weight(1f).fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(top = listTopPadding, bottom = 16.dp)) { list() }
        }
    } else {
        LazyColumn(modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp)) { header(); list() }
    }
    val formats = details.formats
    if (sheet && formats != null) DownloadSheet(formats, actions) { sheet = false }
    details.ask?.let { ask ->
        PlayQualitySheet(ask.choices, ask.selected, actions.onAskMode, actions.onAskSelect, actions.onAskPlay, actions.onAskDismiss)
    }
}

@Composable
private fun rememberChipHandler(state: VideoUiState, details: WatchDetails, actions: WatchActions, openSheet: () -> Unit): (WatchChip) -> Unit {
    val addToPlaylist = LocalAddToPlaylist.current
    return { chip ->
        when (chip) {
            WatchChip.DOWNLOAD -> if (state.kind == VideoKind.YOUTUBE) openSheet() else actions.onDownload()
            WatchChip.PLAY_AS_AUDIO -> actions.onPlayAsAudio()
            WatchChip.ADD_TO_PLAYLIST -> details.video?.let { v -> addToPlaylist?.invoke(v.toAddRequest(details.formats?.selected?.isAudio == true)) }
            WatchChip.ADD_TO_QUEUE -> actions.onAddToQueue()
            WatchChip.CAST -> actions.onCast()
            WatchChip.OPEN_EXTERNAL -> actions.onOpenExternal()
            WatchChip.SHARE -> actions.onShare()
            WatchChip.SUBTITLES -> actions.onSubtitles()
        }
    }
}

@Composable
private fun WatchHeader(state: VideoUiState, details: WatchDetails, actions: WatchActions, onChip: (WatchChip) -> Unit) {
    val canAddToPlaylist = LocalAddToPlaylist.current != null
    val downloaded = stringResource(R.string.media_yt_downloaded)
    val info = sourceLine(state, downloaded)
    var expanded by rememberSaveable(state.title) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow, shape = CardShape,
            modifier = Modifier.padding(horizontal = 8.dp).fillMaxWidth().clip(CardShape).clickable(role = Role.Button) { expanded = !expanded },
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(state.title, style = MaterialTheme.typography.titleMedium, maxLines = if (expanded) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis)
                if (state.kind == VideoKind.YOUTUBE) {
                    val views = stringResource(R.string.yt_views, "%1\$s")
                    val meta = details.video?.let { v -> watchMeta(v.viewCount, v.uploadDate, { c -> views.replace("%1\$s", c) }) }.orEmpty()
                    if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (details.channelName.isNotBlank()) Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        ChannelAvatar(details.video?.uploaderAvatarUrl, details.channelName, 28.dp)
                        ChannelName(details.channelName, details.channelUrl, actions.onChannel, Modifier.weight(1f, fill = false))
                        details.video?.durationSec?.takeIf { it > 0 }?.let {
                            Text("· " + durationText(it), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else if (info.isNotEmpty()) {
                    Text(info, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = if (expanded) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        val chips = chipsFor(state, details, canAddToPlaylist)
        val following = details.following
        val showFollow = state.kind == VideoKind.YOUTUBE && following != null && details.channelUrl != null
        if (chips.isNotEmpty() || showFollow) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Round, icon-only first chip: follow the channel (YouTube).
                if (showFollow) {
                    val label = stringResource(if (following == true) R.string.yt_following else R.string.yt_follow)
                    val source = remember { MutableInteractionSource() }
                    FilledTonalIconButton(
                        onClick = actions.onToggleFollow, enabled = !details.followBusy, interactionSource = source,
                        modifier = Modifier.size(48.dp).focusRing(source, CircleShape),
                    ) {
                        Icon(if (following == true) Icons.Rounded.Notifications else Icons.Rounded.NotificationsNone, contentDescription = label)
                    }
                }
                chips.forEach { c -> ActionChip(c, onClick = { onChip(c) }) }
            }
        }
        val description = details.video?.description.orEmpty()
        if (state.kind == VideoKind.YOUTUBE && description.isNotBlank()) DescriptionBox(description)
        if (state.kind != VideoKind.YOUTUBE && info.isNotEmpty() && state.fileName != null) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = CardShape, modifier = Modifier.padding(horizontal = 8.dp).fillMaxWidth()) {
                Text(state.fileName, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
            }
        }
    }
}

/** The video's description: three lines, tap to open it all (and again to fold it). */
@Composable
private fun DescriptionBox(text: String) {
    var open by rememberSaveable(text) { mutableStateOf(false) }
    val overflows = descriptionOverflows(text)
    val label = stringResource(R.string.yt_description)
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow, shape = CardShape,
        modifier = Modifier.padding(horizontal = 8.dp).fillMaxWidth().clip(CardShape).clickable(enabled = overflows, role = Role.Button) { open = !open },
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
            Text(
                text, style = MaterialTheme.typography.bodyMedium,
                maxLines = if (open) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun WatchChip.icon(): ImageVector = when (this) {
    WatchChip.DOWNLOAD -> Icons.Rounded.Download
    WatchChip.PLAY_AS_AUDIO -> Icons.Rounded.Headphones
    WatchChip.ADD_TO_PLAYLIST -> Icons.Rounded.PlaylistAdd
    WatchChip.ADD_TO_QUEUE -> Icons.Rounded.QueueMusic
    WatchChip.CAST -> Icons.Rounded.Cast
    WatchChip.OPEN_EXTERNAL -> Icons.Rounded.OpenInNew
    WatchChip.SHARE -> Icons.Rounded.Share
    WatchChip.SUBTITLES -> Icons.Rounded.Subtitles
}

@Composable
private fun WatchChip.label(): String = stringResource(
    when (this) {
        WatchChip.DOWNLOAD -> R.string.action_download
        WatchChip.PLAY_AS_AUDIO -> R.string.player_play_as_audio
        WatchChip.ADD_TO_PLAYLIST -> R.string.media_add_to_playlist
        WatchChip.ADD_TO_QUEUE -> R.string.yt_add_to_queue_cd
        WatchChip.CAST -> R.string.cast_title
        WatchChip.OPEN_EXTERNAL -> R.string.watch_open_external
        WatchChip.SHARE -> R.string.action_share
        WatchChip.SUBTITLES -> R.string.yt_subtitles
    },
)

@Composable
private fun ActionChip(chip: WatchChip, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    AssistChip(
        onClick = onClick,
        label = { Text(chip.label(), maxLines = 1) },
        leadingIcon = { Icon(chip.icon(), contentDescription = null, modifier = Modifier.size(18.dp)) },
        interactionSource = source,
        modifier = Modifier.focusRing(source, CircleShape).heightIn(min = 48.dp),
    )
}

private fun LazyListScope.watchList(state: VideoUiState, details: WatchDetails, actions: WatchActions) {
    when (listKindFor(state, details)) {
        WatchListKind.RELATED -> {
            item(key = "related-title") { ListTitle(stringResource(R.string.yt_up_next)) }
            items(details.related, key = { it.url }) { r ->
                BigCard(r.thumbnailUrl, r.durationSec, r.title, r.channel.orEmpty(), { actions.onOpenRelated(r) }, { actions.onAddRelatedToQueue(r) }, avatar = r.channelAvatarUrl, views = r.viewCount)
            }
        }
        WatchListKind.EPISODES -> {
            item(key = "episodes-title") { ListTitle(stringResource(R.string.watch_next_episodes)) }
            items(state.episodes, key = { it.itemId }) { e -> BigCard(null, 0, e.title, state.title, { actions.onPlayEpisode(e) }, null) }
        }
        null -> Unit
    }
}

/** YouTube's large card: full-width 16:9 picture, then a round avatar (initial), the title and a channel line. */
@Composable
private fun BigCard(thumb: String?, durationSec: Long, title: String, channel: String, onClick: () -> Unit, onAddToQueue: (() -> Unit)?, avatar: String? = null, views: Long = -1) {
    val source = remember { MutableInteractionSource() }
    Column(
        Modifier.fillMaxWidth().padding(bottom = 16.dp).focusRing(source, RowShape)
            .clickable(interactionSource = source, indication = androidx.compose.foundation.LocalIndication.current, role = Role.Button, onClick = onClick),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
            if (thumb != null) AsyncImage(model = thumb, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.Center).size(48.dp))
            if (durationSec > 0) {
                Surface(color = Color.Black.copy(alpha = 0.75f), contentColor = Color.White, shape = RoundedCornerShape(6.dp), modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)) {
                    Text(durationText(durationSec), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                }
            }
        }
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            ChannelAvatar(avatar, channel)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val viewsWord = stringResource(R.string.yt_views, "%1\$s")
                val line = listOfNotNull(
                    channel.takeIf { it.isNotBlank() },
                    views.takeIf { it >= 0 }?.let { viewsWord.replace("%1\$s", compactCount(it)) },
                    durationText(durationSec).takeIf { durationSec > 0 },
                ).joinToString(" · ")
                if (line.isNotEmpty()) Text(
                    line,
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (onAddToQueue != null) IconButton(onClick = onAddToQueue) {
                Icon(Icons.Rounded.PlaylistAdd, contentDescription = stringResource(R.string.yt_add_to_queue_cd))
            }
        }
    }
}

@Composable
private fun ListTitle(text: String) {
    Text(
        text, style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp).semantics { heading() },
    )
}

/** Grey placeholders shaped like the loaded page: title card, chip row, box, then a big card. */
@Composable
private fun WatchSkeleton() {
    val pulse by rememberInfiniteTransition(label = "watch-skeleton").animateFloat(0.4f, 0.9f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pulse")
    val tone = MaterialTheme.colorScheme.surfaceContainerHigh
    val loading = stringResource(R.string.yt_loading)
    fun Modifier.bar(w: Float, h: Int, shape: Shape = RoundedCornerShape(6.dp)) = fillMaxWidth(w).height(h.dp).clip(shape).background(tone)
    Column(Modifier.fillMaxSize().graphicsLayer { alpha = pulse }.semantics { contentDescription = loading }, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.padding(horizontal = 8.dp).fillMaxWidth().clip(CardShape).background(MaterialTheme.colorScheme.surfaceContainerLow).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.bar(0.9f, 20)); Box(Modifier.bar(0.4f, 20)); Box(Modifier.bar(0.2f, 20))
        }
        Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(48.dp).clip(CircleShape).background(tone))
            listOf(96, 128, 56, 88).forEach { w -> Box(Modifier.width(w.dp).height(48.dp).clip(CircleShape).background(tone)) }
        }
        Box(Modifier.padding(horizontal = 8.dp).bar(1f, 96, CardShape))
        Box(Modifier.bar(1f, 200, RoundedCornerShape(0.dp)))
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(36.dp).clip(CircleShape).background(tone))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) { Box(Modifier.bar(0.9f, 16)); Box(Modifier.bar(0.4f, 16)) }
        }
    }
}

/** The quality rows, in a sheet: pick one, then Download. */
@Composable
private fun DownloadSheet(formats: WatchFormats, actions: WatchActions, onDismiss: () -> Unit) = QualityPickSheet(
    title = stringResource(R.string.action_download),
    confirmLabel = stringResource(R.string.yt_download_format, choiceLabel(formats.selected), sizeText(formats.selected)),
    confirmIcon = Icons.Rounded.Download,
    choices = formats.choices, selected = formats.selected, subtitles = formats.subtitles, subtitle = formats.subtitle,
    onMode = actions.onSelectMode, onSelect = actions.onSelectFormat, onSubtitle = actions.onSelectSubtitle,
    onConfirm = { actions.onDownload(); onDismiss() }, onDismiss = onDismiss,
)

// --- previews ---------------------------------------------------------------------------

private val CardShape = RoundedCornerShape(16.dp)

private val previewVideo = YouTubeVideo(
    "dQw4w9WgXcQ", "A very long example video title that wraps onto a second line to show truncation", "Example Channel", 213, null, emptyList(),
    uploaderUrl = "https://www.youtube.com/channel/UC1",
    related = List(6) { YouTubeSearchResult("https://youtu.be/id$it", "Related video number $it", "Other Channel", 300L + it, null) },
)
private val previewChoice = FormatChoice(137, 140, "mp4", 212_000_000, false, 1080, 60, Container.MP4, 128_000, isDefault = true)
private val previewYouTube = VideoUiState(VideoKind.YOUTUBE, previewVideo.title, canPlayAsAudio = true)
private val previewYtDetails = WatchDetails(
    video = previewVideo, channelName = previewVideo.uploader, channelUrl = previewVideo.uploaderUrl, following = false,
    formats = WatchFormats(listOf(previewChoice), previewChoice),
)
private val previewEpisodes = VideoUiState(
    VideoKind.DOWNLOAD, "Show S01E01", height = 1080, container = "mkv", sizeBytes = 1_400_000_000, canPlayAsAudio = true,
    episodes = List(4) { EpisodeRow("e$it", "Show S01E0${it + 2}") },
)

@Composable
private fun PreviewHost(state: VideoUiState, details: WatchDetails, wide: Boolean) {
    MaterialTheme { Surface { WatchPage(state, details, WatchActions(), wide) } }
}

@Preview(showBackground = true, heightDp = 700) @Composable private fun YouTubePhonePreview() = PreviewHost(previewYouTube, previewYtDetails, false)
@Preview(showBackground = true, heightDp = 700) @Composable private fun DownloadPhonePreview() = PreviewHost(previewEpisodes, WatchDetails(canOpenExternal = true), false)
@Preview(showBackground = true, widthDp = 900, heightDp = 500) @Composable private fun YouTubeWidePreview() = PreviewHost(previewYouTube, previewYtDetails, true)
