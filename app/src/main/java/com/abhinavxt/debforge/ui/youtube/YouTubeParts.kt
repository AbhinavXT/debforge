package com.abhinavxt.debforge.ui.youtube

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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.abhinavxt.debforge.R
import com.abhinavxt.debforge.domain.FormatChoice
import com.abhinavxt.debforge.domain.YouTubeSearchResult
import com.abhinavxt.debforge.domain.YouTubeSubtitle
import com.abhinavxt.debforge.ui.components.channelLink
import com.abhinavxt.debforge.ui.player.focusRing

// Pieces shared by the YouTube preview and the watch page (ui/video/WatchPage.kt).

internal val RowShape = RoundedCornerShape(16.dp)

/** The channel name as a tappable link; plain text when the extractor gave no channel page. */
@Composable
internal fun ChannelName(name: String, url: String?, onChannel: (String, String) -> Unit, modifier: Modifier = Modifier) {
    val link = url?.takeIf { name.isNotBlank() }
    Text(
        name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = modifier.then(if (link != null) Modifier.channelLink(stringResource(R.string.yt_open_channel, name)) { onChannel(name, link) } else Modifier),
    )
}

/**
 * The quality rows: the Video / Audio toggle (when both kinds exist), the rows of the current kind and, for video, the
 * subtitle choice. [selectedModifier] is applied to the selected row (the preview puts its first-focus requester there).
 */
@Composable
internal fun FormatChoicesBlock(
    choices: List<FormatChoice>,
    selected: FormatChoice,
    subtitles: List<YouTubeSubtitle>,
    subtitle: YouTubeSubtitle?,
    onMode: (Boolean) -> Unit,
    onSelect: (FormatChoice) -> Unit,
    onSubtitle: (YouTubeSubtitle?) -> Unit,
    selectedModifier: Modifier = Modifier,
) {
    val audio = selected.isAudio
    val rows = choices.filter { it.isAudio == audio }
    // A video with only audio (or only video) choices has nothing to switch between.
    if (choices.any { it.isAudio } && choices.any { !it.isAudio }) ModeToggle(audio, onMode)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEachIndexed { i, c ->
            FormatRow(c, c == selected, best = i == 0, onClick = { onSelect(c) }, modifier = if (c == selected) selectedModifier else Modifier)
        }
    }
    if (!audio && subtitles.isNotEmpty()) SubtitleRow(subtitles, subtitle, onSubtitle)
}

/**
 * A related video: the body opens its preview. [onAddToQueue] is a separate trailing button, shown only when given
 * (it queues the video for audio playback).
 */
@Composable
internal fun RelatedRow(
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
internal fun ResultThumb(r: YouTubeSearchResult) {
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
internal fun subtitleName(s: YouTubeSubtitle) = if (s.auto) stringResource(R.string.yt_subtitles_auto, s.label) else s.label

/** Video mode only: None, or one language to save beside the video. */
@Composable
internal fun SubtitleRow(options: List<YouTubeSubtitle>, chosen: YouTubeSubtitle?, onChoose: (YouTubeSubtitle?) -> Unit) {
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
internal fun ModeToggle(audio: Boolean, onMode: (Boolean) -> Unit) {
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
internal fun FormatRow(c: FormatChoice, selected: Boolean, best: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
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

/** A channel's round picture; the first letter of [name] shows underneath while it loads or when there is none. */
@Composable
internal fun ChannelAvatar(url: String?, name: String, size: androidx.compose.ui.unit.Dp = 36.dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
        Text(name.firstOrNull()?.uppercase() ?: "", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
        if (url != null) AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

/**
 * A bottom sheet with the quality rows and one confirm button: Download on the watch page, Play when the quality is
 * to be asked before a video starts. The caller owns the selection ([onMode], [onSelect], [onSubtitle]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QualityPickSheet(
    title: String,
    confirmLabel: String,
    confirmIcon: ImageVector,
    choices: List<FormatChoice>,
    selected: FormatChoice,
    subtitles: List<YouTubeSubtitle>,
    subtitle: YouTubeSubtitle?,
    onMode: (Boolean) -> Unit,
    onSelect: (FormatChoice) -> Unit,
    onSubtitle: (YouTubeSubtitle?) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            FormatChoicesBlock(choices, selected, subtitles = subtitles, subtitle = subtitle, onMode = onMode, onSelect = onSelect, onSubtitle = onSubtitle)
            Button(onClick = onConfirm, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Icon(confirmIcon, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.size(8.dp))
                Text(confirmLabel, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** The "ask every time" sheet: pick what to play, then Play. */
@Composable
internal fun PlayQualitySheet(
    choices: List<FormatChoice>,
    selected: FormatChoice,
    onMode: (Boolean) -> Unit,
    onSelect: (FormatChoice) -> Unit,
    onPlay: () -> Unit,
    onDismiss: () -> Unit,
) = QualityPickSheet(
    title = stringResource(R.string.action_play),
    confirmLabel = if (selected.isAudio) stringResource(R.string.yt_listen) else stringResource(R.string.yt_play_quality, choiceLabel(selected)),
    confirmIcon = Icons.Rounded.PlayArrow,
    choices = choices, selected = selected, subtitles = emptyList(), subtitle = null,
    onMode = onMode, onSelect = onSelect, onSubtitle = {}, onConfirm = onPlay, onDismiss = onDismiss,
)
