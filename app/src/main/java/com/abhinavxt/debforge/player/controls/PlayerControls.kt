@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.abhinavxt.debforge.player.controls

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.material.icons.rounded.Crop
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FitScreen
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import com.abhinavxt.debforge.R
import com.abhinavxt.debforge.player.gestures.GestureMath
import com.abhinavxt.debforge.player.tracks.SubtitleStyle
import com.abhinavxt.debforge.ui.pluralRes
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** What the controls ask the activity to do. */
interface ControlsActions {
    fun snapshot(): PlaybackSnapshot
    fun back()
    fun playPause()
    fun seekBy(ms: Long)
    /** The "+85" button: skip ahead and remember where this show's intro is. */
    fun skipIntro()
    fun scrubStart()
    fun scrubTo(ms: Long)
    fun scrubEnd()
    fun next()
    fun openExternal()
    fun rotate()
    fun setFill(fill: Fill)
    fun enterPip()
    /** Audio ([C.TRACK_TYPE_AUDIO]) or subtitle ([C.TRACK_TYPE_TEXT]) tracks. */
    fun tracks(type: Int): List<TrackChoice>
    /** null = subtitles off. */
    fun selectTrack(type: Int, choice: TrackChoice?)
    /** Look for subtitles on OpenSubtitles: the preferred language, or [anyLanguage]. */
    fun searchOnlineSubtitles(anyLanguage: Boolean)
    /** Download [sub], add it to the file and switch to it. */
    fun pickOnlineSubtitle(sub: com.abhinavxt.debforge.data.subtitles.OnlineSubtitle)
    fun setSpeed(speed: Float)
    fun setShowRemaining(show: Boolean)
    fun setSubtitleStyle(style: SubtitleStyle)
    /** Sleep timer: [minutes] from now, [END_OF_EPISODE], or null = off. */
    fun setSleep(minutes: Int?)
    fun screenshot()
    /** Set A, then B (starts looping), then clear. */
    fun abLoop()
    fun setBackground(on: Boolean)
    /** Show the status / navigation bars with the controls, hide them without. */
    fun onBarsVisible(visible: Boolean)
}

/** [ControlsActions.setSleep]: stop when this episode ends. */
const val END_OF_EPISODE = -1
private val SLEEP_CHOICES = listOf(15, 30, 45, 60)

/** Skip ahead this far with the "+85" button: about one opening. */
const val SKIP_INTRO_SECONDS = 85
private const val AUTO_HIDE_MS = 4_000L
private const val UNLOCK_PROMPT_MS = 3_000L

/**
 * DebForge's player controls: title bar with the track and speed pickers,
 * big play / pause with ±10 s, seek bar with remaining time, and a row of
 * tools (lock, fill, rotate, picture-in-picture, skip 85 s, next episode).
 * They hide 4 s after the last touch while playing.
 */
@Composable
fun PlayerControls(state: ControlsState, actions: ControlsActions) {
    // Keep the position and play state fresh (faster while visible).
    LaunchedEffect(Unit) {
        while (true) {
            state.snapshot = actions.snapshot()
            delay(if (state.visible) 250 else 1_000)
        }
    }
    val snap = state.snapshot
    LaunchedEffect(state.visible, state.lastInteraction, snap.isPlaying, state.panel, state.scrubbing) {
        if (state.visible && snap.isPlaying && state.panel == null && !state.scrubbing) {
            delay(AUTO_HIDE_MS)
            state.visible = false
        }
    }
    LaunchedEffect(state.visible, state.locked) {
        actions.onBarsVisible(state.visible && !state.locked)
    }
    // Back closes an open panel first.
    androidx.activity.compose.BackHandler(enabled = state.panel != null) { state.panel = null }

    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = state.visible && !state.locked,
            enter = fadeIn(), exit = fadeOut()
        ) {
            Box(Modifier.fillMaxSize()) {
                // Scrims so white controls read over bright video.
                Box(
                    Modifier.fillMaxWidth().height(140.dp).align(Alignment.TopCenter)
                        .background(Brush.verticalGradient(listOf(Color(0x99000000), Color.Transparent)))
                )
                Box(
                    Modifier.fillMaxWidth().height(180.dp).align(Alignment.BottomCenter)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xB3000000))))
                )
                TopBar(state, actions, Modifier.align(Alignment.TopCenter))
                CenterButtons(state, actions, Modifier.align(Alignment.Center))
                BottomBar(state, actions, Modifier.align(Alignment.BottomCenter))
            }
        }

        if (state.locked) Locked(state)

        // Side panel: audio, subtitles or speed. Tapping outside closes it.
        val panel = state.panel
        if (panel != null) {
            Box(
                Modifier.fillMaxSize().clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { state.panel = null }
            )
        }
        AnimatedVisibility(
            visible = panel != null,
            enter = slideInHorizontally { it } + fadeIn(),
            exit = slideOutHorizontally { it } + fadeOut(),
            modifier = Modifier.align(Alignment.CenterEnd)
        ) {
            // Keep showing the last panel while it slides out.
            var shown by remember { mutableStateOf(Panel.AUDIO) }
            if (panel != null) shown = panel
            SidePanel(shown, state, actions)
        }
    }
}

/** Records this element's bounds so touches on it skip the gestures. */
@Composable
private fun Modifier.control(state: ControlsState, key: String): Modifier {
    DisposableEffect(key) { onDispose { state.controlBounds.remove(key) } }
    return this.onGloballyPositioned { state.controlBounds[key] = it.boundsInWindow() }
}

@Composable
private fun ControlIcon(
    state: ControlsState,
    key: String,
    icon: ImageVector,
    description: String,
    size: Int = 24,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    IconButton(
        onClick = {
            state.touch()
            onClick()
        },
        enabled = enabled,
        modifier = Modifier.control(state, key)
    ) {
        Icon(icon, contentDescription = description, tint = Color.White, modifier = Modifier.size(size.dp).alpha(if (enabled) 1f else 0.4f))
    }
}

@Composable
private fun TopBar(state: ControlsState, actions: ControlsActions, modifier: Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ControlIcon(state, "back", Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.player_back)) { actions.back() }
        Text(
            state.title,
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 4.dp)
        )
        ControlIcon(state, "audio", Icons.Rounded.Audiotrack, stringResource(R.string.player_audio)) {
            state.panel = Panel.AUDIO
        }
        ControlIcon(state, "subs", Icons.Rounded.ClosedCaption, stringResource(R.string.player_subtitles)) {
            state.panel = Panel.SUBTITLES
        }
        // Speed: shows the value when it isn't normal.
        val speed = state.snapshot.speed
        Box(
            Modifier
                .control(state, "speed")
                .padding(horizontal = 4.dp)
                .background(if (speed != 1f) MaterialTheme.colorScheme.primary else Color(0x33FFFFFF), RoundedCornerShape(16.dp))
                .clickable {
                    state.touch()
                    state.panel = Panel.SPEED
                }
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Text(
                GestureMath.speedLabel(speed),
                color = if (speed != 1f) MaterialTheme.colorScheme.onPrimary else Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
        }
        ControlIcon(state, "external", Icons.AutoMirrored.Rounded.OpenInNew, stringResource(R.string.action_open_other_player)) {
            actions.openExternal()
        }
        ControlIcon(state, "more", Icons.Rounded.MoreVert, stringResource(R.string.player_more)) {
            state.panel = Panel.MORE
        }
    }
}

@Composable
private fun CenterButtons(state: ControlsState, actions: ControlsActions, modifier: Modifier) {
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(40.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ControlIcon(state, "rew", Icons.Rounded.Replay10, pluralRes(R.plurals.player_rewind_n, 10, 10), size = 36) {
            actions.seekBy(-10_000)
        }
        Box(
            Modifier
                .control(state, "play")
                .size(72.dp)
                .background(Color(0x55000000), CircleShape)
                .clickable {
                    state.touch()
                    actions.playPause()
                },
            contentAlignment = Alignment.Center
        ) {
            val snap = state.snapshot
            if (snap.buffering && snap.isPlaying.not()) {
                CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp, modifier = Modifier.size(44.dp))
            } else {
                Icon(
                    if (snap.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = stringResource(if (snap.isPlaying) R.string.action_pause else R.string.action_play),
                    tint = Color.White,
                    modifier = Modifier.size(48.dp)
                )
            }
        }
        ControlIcon(state, "fwd", Icons.Rounded.Forward10, pluralRes(R.plurals.player_forward_n, 10, 10), size = 36) {
            actions.seekBy(10_000)
        }
    }
}

@Composable
private fun BottomBar(state: ControlsState, actions: ControlsActions, modifier: Modifier) {
    val snap = state.snapshot
    Column(
        modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TimeText(GestureMath.time(snap.positionMs))
            SeekBar(
                loopA = state.loopA,
                loopB = state.loopB,
                positionMs = snap.positionMs,
                bufferedMs = snap.bufferedMs,
                durationMs = snap.durationMs,
                onStart = {
                    state.scrubbing = true
                    actions.scrubStart()
                },
                onScrub = actions::scrubTo,
                onEnd = {
                    state.scrubbing = false
                    state.touch()
                    actions.scrubEnd()
                },
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp).control(state, "seekbar")
            )
            // Tap: length ↔ time left.
            val right = if (snap.durationMs <= 0) "--:--"
            else if (state.showRemaining) "−" + GestureMath.time(snap.durationMs - snap.positionMs)
            else GestureMath.time(snap.durationMs)
            TimeText(
                right,
                Modifier.control(state, "time").clickable {
                    state.touch()
                    actions.setShowRemaining(!state.showRemaining)
                }
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            ControlIcon(state, "lock", Icons.Rounded.Lock, stringResource(R.string.player_lock)) {
                state.locked = true
                state.panel = null
                state.unlockPromptAt = android.os.SystemClock.uptimeMillis()
            }
            val (fillIcon, next) = when (state.fill) {
                Fill.FIT -> Icons.Rounded.FitScreen to Fill.CROP
                Fill.CROP -> Icons.Rounded.Crop to Fill.STRETCH
                Fill.STRETCH -> Icons.Rounded.AspectRatio to Fill.FIT
            }
            ControlIcon(state, "fill", fillIcon, stringResource(fillLabel(state.fill))) { actions.setFill(next) }
            ControlIcon(state, "rotate", Icons.Rounded.ScreenRotation, stringResource(R.string.player_rotate)) { actions.rotate() }
            if (state.pipAvailable) {
                ControlIcon(state, "pip", Icons.Rounded.PictureInPictureAlt, stringResource(R.string.set_pip)) { actions.enterPip() }
            }
            Spacer(Modifier.weight(1f))
            // A set (from More): set B / clear right here.
            if (state.loopA != null) {
                Box(
                    Modifier
                        .control(state, "ab")
                        .padding(end = 8.dp)
                        .background(Color(0x66FFB300), RoundedCornerShape(18.dp))
                        .clickable {
                            state.touch()
                            actions.abLoop()
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        if (state.loopB == null) "B" else "A\u2013B \u2715",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            // Skip an opening / recap.
            Row(
                Modifier
                    .control(state, "skip")
                    .background(Color(0x33FFFFFF), RoundedCornerShape(18.dp))
                    .clickable {
                        state.touch()
                        actions.skipIntro()
                    }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Rounded.FastForward,
                    contentDescription = pluralRes(R.plurals.player_forward_n, SKIP_INTRO_SECONDS, SKIP_INTRO_SECONDS),
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text("+$SKIP_INTRO_SECONDS", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
            if (state.hasNext) {
                Spacer(Modifier.width(4.dp))
                ControlIcon(state, "next", Icons.Rounded.SkipNext, stringResource(R.string.player_next_episode)) { actions.next() }
            }
        }
    }
}

fun fillLabel(fill: Fill): Int = when (fill) {
    Fill.FIT -> R.string.player_fit
    Fill.CROP -> R.string.player_crop
    Fill.STRETCH -> R.string.player_stretch
}

@Composable
private fun TimeText(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        color = Color.White,
        fontSize = 13.sp,
        fontFamily = FontFamily.Monospace,
        modifier = modifier.widthIn(min = 52.dp).padding(vertical = 8.dp)
    )
}

/** Thin seek bar with the buffered part; drag or tap to move. */
@Composable
private fun SeekBar(
    loopA: Long?,
    loopB: Long?,
    positionMs: Long,
    bufferedMs: Long,
    durationMs: Long,
    onStart: () -> Unit,
    onScrub: (Long) -> Unit,
    onEnd: () -> Unit,
    modifier: Modifier
) {
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val accent = MaterialTheme.colorScheme.primary
    val enabled = durationMs > 0
    BoxWithConstraints(modifier.height(32.dp)) {
        val widthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        fun at(x: Float) = (x / widthPx).coerceIn(0f, 1f)
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(enabled, durationMs) {
                    if (!enabled) return@pointerInput
                    detectTapGestures { o ->
                        onStart()
                        onScrub((at(o.x) * durationMs).toLong())
                        onEnd()
                    }
                }
                .pointerInput(enabled, durationMs) {
                    if (!enabled) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragStart = { o ->
                            dragFraction = at(o.x)
                            onStart()
                        },
                        onDragEnd = {
                            dragFraction = null
                            onEnd()
                        },
                        onDragCancel = {
                            dragFraction = null
                            onEnd()
                        }
                    ) { change, _ ->
                        val f = at(change.position.x)
                        dragFraction = f
                        onScrub((f * durationMs).toLong())
                    }
                }
        ) {
            val dragging = dragFraction != null
            val played = dragFraction ?: if (enabled) positionMs.toFloat() / durationMs else 0f
            val buffered = if (enabled) (bufferedMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
            val y = size.height / 2
            val stroke = (if (dragging) 6.dp else 4.dp).toPx()
            drawLine(Color(0x4DFFFFFF), Offset(0f, y), Offset(size.width, y), stroke, StrokeCap.Round)
            drawLine(Color(0x80FFFFFF), Offset(0f, y), Offset(size.width * buffered, y), stroke, StrokeCap.Round)
            drawLine(accent, Offset(0f, y), Offset(size.width * played.coerceIn(0f, 1f), y), stroke, StrokeCap.Round)
            // A-B loop: the looped stretch in amber, with its ends marked.
            if (enabled && loopA != null) {
                val amber = Color(0xFFFFB300)
                val a = size.width * (loopA.toFloat() / durationMs).coerceIn(0f, 1f)
                val b = loopB?.let { size.width * (it.toFloat() / durationMs).coerceIn(0f, 1f) }
                if (b != null) drawLine(amber.copy(alpha = 0.6f), Offset(a, y), Offset(b, y), stroke)
                drawLine(amber, Offset(a, y - 8.dp.toPx()), Offset(a, y + 8.dp.toPx()), 2.dp.toPx())
                if (b != null) drawLine(amber, Offset(b, y - 8.dp.toPx()), Offset(b, y + 8.dp.toPx()), 2.dp.toPx())
            }
            drawCircle(accent, radius = (if (dragging) 9.dp else 7.dp).toPx(), center = Offset(size.width * played.coerceIn(0f, 1f), y))
        }
    }
}

/** Everything hidden; a tap shows "Slide to unlock" for a few seconds. */
@Composable
private fun Locked(state: ControlsState) {
    var prompt by remember { mutableStateOf(false) }
    LaunchedEffect(state.unlockPromptAt) {
        if (state.unlockPromptAt == 0L) return@LaunchedEffect
        prompt = true
        delay(UNLOCK_PROMPT_MS)
        prompt = false
    }
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures { state.unlockPromptAt = android.os.SystemClock.uptimeMillis() } }
    ) {
        AnimatedVisibility(
            visible = prompt,
            enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                .padding(bottom = 32.dp)
        ) {
            SlideToUnlock(onUnlock = {
                state.locked = false
                state.visible = true
                state.touch()
            }, onMove = { state.unlockPromptAt = android.os.SystemClock.uptimeMillis() })
        }
    }
}

@Composable
private fun SlideToUnlock(onUnlock: () -> Unit, onMove: () -> Unit) {
    val trackWidth = 220.dp
    val thumb = 48.dp
    val density = LocalDensity.current
    val travel = with(density) { (trackWidth - thumb - 8.dp).toPx() }
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    Box(
        Modifier
            .width(trackWidth)
            .height(56.dp)
            .background(Color(0x99000000), RoundedCornerShape(28.dp))
            .padding(4.dp)
    ) {
        Text(
            stringResource(R.string.player_slide_to_unlock),
            color = Color.White.copy(alpha = 0.8f),
            fontSize = 14.sp,
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 20.dp)
        )
        Box(
            Modifier
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .size(thumb)
                .background(MaterialTheme.colorScheme.primary, CircleShape)
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        scope.launch { offset.snapTo((offset.value + delta).coerceIn(0f, travel)) }
                        onMove()
                    },
                    onDragStopped = {
                        if (offset.value >= travel * 0.85f) {
                            onUnlock()
                            offset.snapTo(0f)
                        } else {
                            offset.animateTo(0f)
                        }
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (offset.value > travel / 2) Icons.Rounded.LockOpen else Icons.Rounded.Lock,
                contentDescription = stringResource(R.string.player_slide_to_unlock),
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

@Composable
private fun SidePanel(panel: Panel, state: ControlsState, actions: ControlsActions) {
    Column(
        Modifier
            .fillMaxHeight()
            .width(320.dp)
            .background(Color(0xF01C1C1E), RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp))
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.End))
            .padding(vertical = 12.dp)
    ) {
        Text(
            stringResource(
                when (panel) {
                    Panel.AUDIO -> R.string.player_audio
                    Panel.SUBTITLES -> R.string.player_subtitles
                    Panel.ONLINE_SUBTITLES -> R.string.player_subs_online
                    Panel.SPEED -> R.string.player_speed
                    Panel.MORE -> R.string.player_more
                }
            ),
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
        )
        when (panel) {
            Panel.AUDIO, Panel.SUBTITLES -> {
                val type = if (panel == Panel.AUDIO) C.TRACK_TYPE_AUDIO else C.TRACK_TYPE_TEXT
                val choices = remember(state.tracksVersion, panel) { actions.tracks(type) }
                LazyColumn {
                    if (panel == Panel.SUBTITLES) {
                        item {
                            ChoiceRow(stringResource(R.string.player_subtitles_off), choices.none { it.selected }) {
                                actions.selectTrack(type, null)
                            }
                        }
                    } else if (choices.isEmpty()) {
                        item {
                            Text(
                                stringResource(R.string.player_no_tracks),
                                color = Color.White.copy(alpha = 0.6f),
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                            )
                        }
                    }
                    items(choices) { c -> ChoiceRow(c.label, c.selected) { actions.selectTrack(type, c) } }
                    if (panel == Panel.SUBTITLES && state.onlineSubsAvailable) {
                        item {
                            MoreRow(Icons.Rounded.Search, stringResource(R.string.player_subs_search_online)) {
                                state.panel = Panel.ONLINE_SUBTITLES
                                if (state.onlineSubs !is OnlineSubs.Results) actions.searchOnlineSubtitles(anyLanguage = false)
                            }
                        }
                    }
                    if (panel == Panel.SUBTITLES) {
                        item { StyleSection(state.subStyle, actions::setSubtitleStyle) }
                    }
                }
            }
            Panel.MORE -> MorePanel(state, actions)
            Panel.ONLINE_SUBTITLES -> OnlineSubtitlesPanel(state.onlineSubs, actions)
            Panel.SPEED -> {
                val current = state.snapshot.speed
                LazyColumn {
                    items(GestureMath.SPEEDS) { s ->
                        val label = if (s == 1f) GestureMath.speedLabel(s) + " · " + stringResource(R.string.player_normal_speed)
                        else GestureMath.speedLabel(s)
                        ChoiceRow(label, s == current) { actions.setSpeed(s) }
                    }
                }
            }
        }
    }
}

/** OpenSubtitles results: exact matches for this file first, then the most downloaded. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun OnlineSubtitlesPanel(online: OnlineSubs, actions: ControlsActions) {
    val anyLanguage = when (online) {
        is OnlineSubs.Results -> online.anyLanguage
        is OnlineSubs.Downloading -> online.anyLanguage
        is OnlineSubs.Failed -> online.anyLanguage
        else -> false
    }
    val subs = when (online) {
        is OnlineSubs.Results -> online.subs
        is OnlineSubs.Downloading -> online.subs
        else -> emptyList()
    }
    val dim = Color.White.copy(alpha = 0.6f)
    LazyColumn {
        item {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StyleChip(selected = !anyLanguage, onClick = { actions.searchOnlineSubtitles(anyLanguage = false) }) {
                    Text(stringResource(R.string.player_subs_my_language), color = Color.White, fontSize = 13.sp)
                }
                StyleChip(selected = anyLanguage, onClick = { actions.searchOnlineSubtitles(anyLanguage = true) }) {
                    Text(stringResource(R.string.player_subs_any_language), color = Color.White, fontSize = 13.sp)
                }
            }
        }
        when (online) {
            OnlineSubs.Idle, OnlineSubs.Searching -> item {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    androidx.compose.material3.CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                }
            }
            is OnlineSubs.Failed -> item {
                Text(stringResource(online.message), color = dim, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
            }
            else -> if (subs.isEmpty()) item {
                Text(stringResource(R.string.player_subs_none_found), color = dim, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
            }
        }
        items(subs, key = { it.fileId }) { sub ->
            val downloading = online is OnlineSubs.Downloading && online.fileId == sub.fileId
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = online !is OnlineSubs.Downloading) { actions.pickOnlineSubtitle(sub) }
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(sub.release, color = Color.White, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val tags = listOfNotNull(
                        sub.language?.let { java.util.Locale.forLanguageTag(it).displayName.ifBlank { it } },
                        if (sub.hashMatch) stringResource(R.string.player_subs_exact) else null,
                        if (sub.hearingImpaired) stringResource(R.string.player_subs_hi) else null,
                        pluralRes(R.plurals.player_subs_downloads_n, sub.downloads, sub.downloads)
                    )
                    Text(
                        tags.joinToString(" · "),
                        color = if (sub.hashMatch) MaterialTheme.colorScheme.primary else dim,
                        fontSize = 12.sp
                    )
                }
                if (downloading) {
                    androidx.compose.material3.CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                }
            }
        }
    }
}

/** Sleep timer, screenshot, A-B loop, background play. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun MorePanel(state: ControlsState, actions: ControlsActions) {
    LazyColumn {
        item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Bedtime, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.player_sleep_timer), color = Color.White, style = MaterialTheme.typography.titleSmall)
                }
                // Re-read with the position a few times a second, so the minutes count down.
                val now = state.snapshot.let { android.os.SystemClock.elapsedRealtime() }
                val left = if (state.sleepEndsAt > 0) {
                    ((state.sleepEndsAt - now) / 60_000 + 1).toInt().coerceAtLeast(1)
                } else 0
                if (left > 0 || state.sleepAtEnd) {
                    Text(
                        if (state.sleepAtEnd) stringResource(R.string.player_sleep_end) else pluralRes(R.plurals.player_sleep_in_n, left, left),
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(start = 32.dp, top = 2.dp)
                    )
                }
                Spacer(Modifier.height(8.dp))
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StyleChip(selected = state.sleepEndsAt == 0L && !state.sleepAtEnd, onClick = { actions.setSleep(null) }) {
                        Text(stringResource(R.string.player_sleep_off), color = Color.White, fontSize = 13.sp)
                    }
                    SLEEP_CHOICES.forEach { m ->
                        StyleChip(selected = false, onClick = { actions.setSleep(m) }) {
                            Text(pluralRes(R.plurals.player_minutes_n, m, m), color = Color.White, fontSize = 13.sp)
                        }
                    }
                    StyleChip(selected = state.sleepAtEnd, onClick = { actions.setSleep(END_OF_EPISODE) }) {
                        Text(stringResource(R.string.player_sleep_end), color = Color.White, fontSize = 13.sp)
                    }
                }
            }
        }
        item {
            MoreRow(Icons.Rounded.PhotoCamera, stringResource(R.string.player_screenshot)) { actions.screenshot() }
        }
        item {
            val a = state.loopA
            val b = state.loopB
            val label = when {
                a == null -> stringResource(R.string.player_ab_set_a)
                b == null -> stringResource(R.string.player_ab_set_b) + "  (A " + GestureMath.time(a) + ")"
                else -> stringResource(R.string.player_ab_clear) + "  (" + GestureMath.time(a) + " \u2013 " + GestureMath.time(b) + ")"
            }
            MoreRow(Icons.Rounded.Repeat, stringResource(R.string.player_ab_loop), label) { actions.abLoop() }
        }
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { actions.setBackground(!state.background) }
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.Headphones, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.player_background), color = Color.White, fontSize = 15.sp)
                    Text(stringResource(R.string.player_background_detail), color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
                }
                androidx.compose.material3.Switch(checked = state.background, onCheckedChange = { actions.setBackground(it) })
            }
        }
    }
}

@Composable
private fun MoreRow(icon: ImageVector, title: String, detail: String? = null, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, color = Color.White, fontSize = 15.sp)
            if (detail != null) Text(detail, color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
        }
    }
}

/** Size, look and height of subtitles, changed live. */
@Composable
private fun StyleSection(style: SubtitleStyle, onChange: (SubtitleStyle) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
        Text(
            stringResource(R.string.player_sub_style),
            color = Color.White,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        StyleLine(stringResource(R.string.player_sub_size)) {
            SubtitleStyle.Size.entries.forEachIndexed { i, size ->
                StyleChip(selected = style.size == size, onClick = { onChange(style.copy(size = size)) }) {
                    Text("A", color = Color.White, fontSize = (12 + i * 3).sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        StyleLine(stringResource(R.string.player_sub_look)) {
            SubtitleStyle.Look.entries.forEach { look ->
                StyleChip(selected = style.look == look, onClick = { onChange(style.copy(look = look)) }) {
                    val (fg, bg) = when (look) {
                        SubtitleStyle.Look.OUTLINE -> Color.White to Color.Transparent
                        SubtitleStyle.Look.BOX -> Color.White to Color.Black
                        SubtitleStyle.Look.YELLOW -> Color(0xFFFFEB3B) to Color.Transparent
                    }
                    Text(
                        "Aa",
                        color = fg,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.background(bg).padding(horizontal = 3.dp)
                    )
                }
            }
        }
        StyleLine(stringResource(R.string.player_sub_position)) {
            SubtitleStyle.Position.entries.forEach { pos ->
                StyleChip(selected = style.position == pos, onClick = { onChange(style.copy(position = pos)) }) {
                    Text(
                        stringResource(
                            when (pos) {
                                SubtitleStyle.Position.LOW -> R.string.player_sub_pos_low
                                SubtitleStyle.Position.MID -> R.string.player_sub_pos_mid
                                SubtitleStyle.Position.HIGH -> R.string.player_sub_pos_high
                            }
                        ),
                        color = Color.White,
                        fontSize = 12.sp
                    )
                }
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { onChange(style.copy(fileStyling = !style.fileStyling)) }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(R.string.player_sub_file_style),
                color = Color.White,
                fontSize = 14.sp,
                modifier = Modifier.weight(1f)
            )
            androidx.compose.material3.Switch(
                checked = style.fileStyling,
                onCheckedChange = { onChange(style.copy(fileStyling = it)) }
            )
        }
    }
}

@Composable
private fun StyleLine(label: String, chips: @Composable () -> Unit) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(label, color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { chips() }
    }
}

@Composable
private fun StyleChip(selected: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier
            .height(36.dp)
            .widthIn(min = 44.dp)
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.35f) else Color(0x22FFFFFF),
                RoundedCornerShape(10.dp)
            )
            .then(
                if (selected) Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp))
                else Modifier
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center
    ) { content() }
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            if (selected) Icon(Icons.Rounded.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.width(12.dp))
        Text(
            label,
            color = Color.White,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}
