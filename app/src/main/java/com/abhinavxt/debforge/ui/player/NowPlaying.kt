package com.abhinavxt.debforge.ui.player

import android.provider.Settings
import androidx.compose.animation.Crossfade
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.MarqueeSpacing
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.abhinavxt.debforge.R
import com.abhinavxt.debforge.player.AudioRepeat
import com.abhinavxt.debforge.player.AUDIO_SPEEDS
import com.abhinavxt.debforge.player.AudioState
import com.abhinavxt.debforge.player.SLEEP_END_OF_TRACK
import com.abhinavxt.debforge.player.SLEEP_MINUTES
import com.abhinavxt.debforge.player.sleepMinutesLeft
import com.abhinavxt.debforge.player.speedLabel
import com.abhinavxt.debforge.player.formatClock
import com.abhinavxt.debforge.player.upNextOrder
import com.abhinavxt.debforge.ui.theme.DebforgeTheme

/** Full-screen "now playing": artwork, title, seek bar, transport, shuffle/repeat and the queue. */
@Composable
fun NowPlaying(
    state: AudioState,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onShuffle: (Boolean) -> Unit,
    onRepeat: () -> Unit,
    onSpeed: (Float) -> Unit,
    onSleep: (Int) -> Unit,
    onSkipTo: (Int) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    share: ArtworkShare? = null
) {
    val track = state.track ?: return
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth > maxHeight && maxWidth >= 600.dp
            Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
                val s = remember { MutableInteractionSource() }
                IconButton(onClose, Modifier.size(48.dp).focusRing(s, CircleShape), interactionSource = s) {
                    Icon(Icons.Rounded.KeyboardArrowDown, stringResource(R.string.audio_close))
                }
                if (wide) {
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Artwork(state, share, Modifier.fillMaxWidth(0.7f).widthIn(max = 360.dp))
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                            Controls(state, onToggle, onNext, onPrevious, onSeek, onShuffle, onRepeat, onSpeed, onSleep)
                            Queue(state, onSkipTo, Modifier.weight(1f, fill = false).heightIn(max = 220.dp))
                        }
                    }
                } else {
                    Queue(
                        state, onSkipTo, Modifier.weight(1f),
                        header = {
                            // With nothing up next the player fills the screen, centred, instead of leaving the lower half blank.
                            val alone = upNextOrder(state.order, state.index, state.repeat).none { it in state.queue.indices }
                            Column(
                                if (alone) Modifier.heightIn(min = this@BoxWithConstraints.maxHeight - 48.dp) else Modifier,
                                verticalArrangement = Arrangement.Center,
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Artwork(state, share, Modifier.fillMaxWidth(0.85f))
                                Controls(state, onToggle, onNext, onPrevious, onSeek, onShuffle, onRepeat, onSpeed, onSleep, Modifier.padding(top = 24.dp))
                            }
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun Artwork(state: AudioState, share: ArtworkShare?, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val shape = ArtShape
    // The shadow waits for the flight to land so it never frames an empty slot.
    val enter = share?.visibility?.transition
    val settled = share == null || (enter!!.currentState == EnterExitState.Visible && enter.targetState == EnterExitState.Visible &&
        !share.transition.isTransitionActive)
    val elevation by animateDpAsState(
        if (settled) 16.dp else 0.dp,
        animationSpec = if (settled) tween(200) else snap(),
        label = "artShadow"
    )
    val visible = share?.visibility?.transition?.targetState != EnterExitState.PostExit
    Crossfade(state.track, modifier.aspectRatio(1f), label = "art") { t ->
        if (t != null) TrackArt(
            t,
            Modifier.fillMaxSize().shadow(elevation, shape, ambientColor = cs.surfaceTint, spotColor = cs.surfaceTint)
                .sharedArtwork(share?.transition, t.id, visible).clip(ArtShape)
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Controls(
    state: AudioState,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onShuffle: (Boolean) -> Unit,
    onRepeat: () -> Unit,
    onSpeed: (Float) -> Unit,
    onSleep: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val track = state.track ?: return
    Column(modifier.fillMaxWidth()) {
        Crossfade(track, label = "title") { t ->
            Column(Modifier.fillMaxWidth()) {
                val context = LocalContext.current
                val scroll = remember(t) { titleMarqueeEnabled(animatorDurationScale(context)) }
                Text(
                    t.title,
                    style = MaterialTheme.typography.headlineSmall,
                    maxLines = 1,
                    overflow = if (scroll) TextOverflow.Clip else TextOverflow.Ellipsis,
                    modifier = if (scroll) Modifier.basicMarquee(
                        iterations = Int.MAX_VALUE, initialDelayMillis = 1500, repeatDelayMillis = 1500, spacing = MarqueeSpacing(48.dp)
                    ) else Modifier
                )
                Text(
                    t.artist ?: stringResource(R.string.audio_unknown_artist),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        }
        SeekBar(state.positionMs, state.durationMs, onSeek)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            val cs = MaterialTheme.colorScheme
            ToggleButton(
                Icons.Rounded.Shuffle, state.shuffle,
                stringResource(if (state.shuffle) R.string.audio_shuffle_on else R.string.audio_shuffle_off)
            ) { onShuffle(!state.shuffle) }
            val s1 = remember { MutableInteractionSource() }
            IconButton(onPrevious, Modifier.size(56.dp).focusRing(s1, CircleShape), interactionSource = s1) {
                Icon(Icons.Rounded.SkipPrevious, stringResource(R.string.audio_previous), Modifier.size(32.dp))
            }
            val s2 = remember { MutableInteractionSource() }
            FilledIconButton(
                onToggle, Modifier.size(72.dp).focusRing(s2, CircleShape), interactionSource = s2,
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = cs.primary, contentColor = cs.onPrimary)
            ) { PlayPauseIcon(state.isPlaying, Modifier.size(36.dp)) }
            val s3 = remember { MutableInteractionSource() }
            IconButton(onNext, Modifier.size(56.dp).focusRing(s3, CircleShape), enabled = state.hasNext, interactionSource = s3) {
                Icon(Icons.Rounded.SkipNext, stringResource(R.string.audio_next), Modifier.size(32.dp))
            }
            ToggleButton(
                if (state.repeat == AudioRepeat.ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                state.repeat != AudioRepeat.OFF,
                stringResource(
                    when (state.repeat) {
                        AudioRepeat.OFF -> R.string.audio_repeat_off
                        AudioRepeat.ALL -> R.string.audio_repeat_all
                        AudioRepeat.ONE -> R.string.audio_repeat_one
                    }
                ),
                onRepeat
            )
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            SpeedButton(state.speed, onSpeed)
            SleepButton(state, onSleep)
        }
    }
}

@Composable
private fun SpeedButton(speed: Float, onSpeed: (Float) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val s = remember { MutableInteractionSource() }
    val desc = stringResource(R.string.audio_speed_cd, speedLabel(speed))
    Box {
        TextButton(
            { open = true }, Modifier.heightIn(min = 48.dp).focusRing(s, CircleShape).semantics { contentDescription = desc },
            interactionSource = s
        ) { Text(speedLabel(speed), color = if (speed == 1f) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary) }
        DropdownMenu(open, { open = false }) {
            AUDIO_SPEEDS.forEach { v -> DropdownMenuItem({ Text(speedLabel(v)) }, { open = false; onSpeed(v) }) }
        }
    }
}

@Composable
private fun SleepButton(state: AudioState, onSleep: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val s = remember { MutableInteractionSource() }
    val left = state.sleepLeftMs?.let { sleepMinutesLeft(it) }
    val label = when {
        state.sleepAtEnd -> stringResource(R.string.audio_sleep_end)
        left != null -> pluralStringResource(R.plurals.player_minutes_n, left, left)
        else -> null
    }
    val desc = when {
        state.sleepAtEnd -> stringResource(R.string.audio_sleep_end_cd)
        left != null -> pluralStringResource(R.plurals.audio_sleep_left_cd, left, left)
        else -> stringResource(R.string.audio_sleep_off_cd)
    }
    val tint = if (label != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Box {
        TextButton(
            { open = true }, Modifier.heightIn(min = 48.dp).focusRing(s, CircleShape).semantics { contentDescription = desc },
            interactionSource = s
        ) {
            Icon(Icons.Rounded.Bedtime, null, tint = tint)
            if (label != null) Text(label, Modifier.padding(start = 8.dp), color = tint)
        }
        DropdownMenu(open, { open = false }) {
            DropdownMenuItem({ Text(stringResource(R.string.player_sleep_off)) }, { open = false; onSleep(0) })
            SLEEP_MINUTES.forEach { m ->
                DropdownMenuItem({ Text(pluralStringResource(R.plurals.player_minutes_n, m, m)) }, { open = false; onSleep(m) })
            }
            DropdownMenuItem({ Text(stringResource(R.string.audio_sleep_end)) }, { open = false; onSleep(SLEEP_END_OF_TRACK) })
        }
    }
}

@Composable
private fun ToggleButton(icon: androidx.compose.ui.graphics.vector.ImageVector, on: Boolean, label: String, onClick: () -> Unit) {
    val s = remember { MutableInteractionSource() }
    IconButton(onClick, Modifier.size(48.dp).focusRing(s, CircleShape), interactionSource = s) {
        Icon(
            icon, label,
            tint = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SeekBar(positionMs: Long, durationMs: Long, onSeek: (Long) -> Unit) {
    // While dragging, show the thumb's value; seek once on release.
    var dragging by remember { mutableStateOf<Float?>(null) }
    val duration = durationMs.coerceAtLeast(1)
    val shown = dragging ?: positionMs.coerceIn(0, duration).toFloat()
    val tnum = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum")
    val s = remember { MutableInteractionSource() }
    val dragged by s.collectIsDraggedAsState()
    val desc = stringResource(R.string.audio_position_state, spoken(shown.toLong()), spoken(duration))
    Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Slider(
            value = shown,
            onValueChange = { dragging = it },
            onValueChangeFinished = { dragging?.let { onSeek(it.toLong()) }; dragging = null },
            valueRange = 0f..duration.toFloat(),
            interactionSource = s,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .focusRing(s, RoundedCornerShape(24.dp))
                .semantics { stateDescription = desc },
            thumb = {
                // The slot keeps one size so the track does not shift when the thumb grows on drag.
                val thumb by animateDpAsState(if (dragged) 16.dp else 12.dp, label = "thumb")
                Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
                    SliderDefaults.Thumb(s, thumbSize = DpSize(thumb, thumb))
                }
            },
            track = { state ->
                val cs = MaterialTheme.colorScheme
                val range = state.valueRange
                val fraction = ((state.value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
                Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(cs.surfaceVariant)) {
                    Box(Modifier.fillMaxWidth(fraction).height(4.dp).background(cs.primary))
                }
            }
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatClock(shown.toLong()), style = tnum, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("-" + formatClock(duration - shown.toLong()), style = tnum, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The title scrolls only when the system animations are on; with them off it stays one line with an ellipsis. */
internal fun titleMarqueeEnabled(animatorDurationScale: Float): Boolean = animatorDurationScale > 0f

internal fun animatorDurationScale(context: android.content.Context): Float =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)

@Composable
private fun spoken(ms: Long): String {
    val s = (ms / 1000).toInt()
    val seconds = pluralStringResource(R.plurals.audio_time_seconds, s % 60, s % 60)
    return if (s >= 60) stringResource(R.string.audio_time_minutes_seconds, pluralStringResource(R.plurals.audio_time_minutes, s / 60, s / 60), seconds)
    else seconds
}

/** "Up next" list; [header] scrolls with it so a phone in portrait fits everything. */
@Composable
private fun Queue(state: AudioState, onSkipTo: (Int) -> Unit, modifier: Modifier, header: (@Composable () -> Unit)? = null) {
    val cs = MaterialTheme.colorScheme
    LazyColumn(modifier.fillMaxWidth()) {
        if (header != null) item { header() }
        val upNext = upNextOrder(state.order, state.index, state.repeat).filter { it in state.queue.indices }
        if (upNext.isNotEmpty()) item {
            Text(
                stringResource(R.string.audio_up_next),
                style = MaterialTheme.typography.titleSmall,
                color = cs.onSurfaceVariant,
                modifier = Modifier.padding(top = 24.dp, bottom = 8.dp)
            )
        }
        items(upNext, key = { it }) { i ->
            val t = state.queue[i]
            val s = remember { MutableInteractionSource() }
            Column(
                Modifier.fillMaxWidth().heightIn(min = 56.dp)
                    .clip(MaterialTheme.shapes.small)
                    .focusRing(s, MaterialTheme.shapes.small)
                    .clickable(interactionSource = s, indication = androidx.compose.material3.ripple()) { onSkipTo(i) }
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Text(t.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    t.artist ?: stringResource(R.string.audio_unknown_artist),
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Preview(showBackground = true, heightDp = 780)
@Composable
private fun NowPlayingPreview() = DebforgeTheme {
    NowPlaying(previewState, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
}

@Preview(showBackground = true, widthDp = 900, heightDp = 480)
@Composable
private fun NowPlayingWidePreview() = DebforgeTheme {
    NowPlaying(previewState.copy(shuffle = true, repeat = AudioRepeat.ONE), {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
}
