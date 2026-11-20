package com.abhinavxt.debforge.ui.video

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import com.abhinavxt.debforge.ui.player.DISMISS_FLING_DP
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.abhinavxt.debforge.R
import com.abhinavxt.debforge.ui.player.focusRing

/** The mini player's video slot: its size (16:9 at the bar's height). The picture itself is the host's one surface, drawn over it. */
internal val MiniVideoWidth = 114.dp
internal val MiniBarHeight = 64.dp

/**
 * The small bar over the tabs (beside the rail on a tablet): the video, its title, play / pause and close.
 * The video is not drawn here: the bar only leaves a slot and tells [onVideoSlot] where it is (in the root's
 * coordinates) so the host's single surface can sit on it. A tap anywhere on the bar opens the watch page.
 */
@Composable
fun VideoMiniBar(
    title: String,
    /** Read only inside the bar (the play icon, the progress line), so its 500 ms updates recompose nothing else. */
    pulse: State<VideoPulse>,
    onToggle: () -> Unit,
    onClose: () -> Unit,
    onExpand: () -> Unit,
    onVideoSlot: (Rect) -> Unit,
    modifier: Modifier = Modifier,
    toggleFocus: FocusRequester = remember { FocusRequester() },
    /** The bar's sideways swipe, shared with the picture drawn over the bar's slot (a swipe may start on either). */
    swipe: MiniBarSwipe = remember { MiniBarSwipe() },
) {
    val shape = RoundedCornerShape(16.dp)
    val closeNow by rememberUpdatedState(onClose)
    val expandNow by rememberUpdatedState(onExpand)
    SideEffect { swipe.onClose = { closeNow() }; swipe.onExpand = { expandNow() } }
    // The swipe outlives the bar: a bar shown again starts in place, not where the last one was thrown.
    androidx.compose.runtime.DisposableEffect(swipe) {
        swipe.settling?.cancel()
        swipe.dragX = 0f
        onDispose { swipe.settling?.cancel(); swipe.dragX = 0f }
    }
    val barSource = remember { MutableInteractionSource() }
    val expandLabel = stringResource(R.string.video_mini_expand)
    val playing by remember(pulse) { derivedStateOf { pulse.value.playing } }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .onSizeChanged { swipe.widthPx = it.width.toFloat() }
            // Before the offset: the touch's coordinates must not move with the bar.
            .miniBarSwipe(swipe)
            .offset { IntOffset(swipe.dragX.roundToInt(), 0) }
            .padding(horizontal = 8.dp, vertical = 4.dp),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Box(Modifier.focusRing(barSource, shape)) {
            Row(
                Modifier.fillMaxWidth().height(MiniBarHeight)
                    .clickable(interactionSource = barSource, indication = androidx.compose.material3.ripple(), onClickLabel = expandLabel, onClick = onExpand),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.width(MiniVideoWidth).height(MiniBarHeight).onGloballyPositioned { onVideoSlot(it.boundsInRoot()) })
                Text(
                    title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                )
                IconButton(onClick = onToggle, modifier = Modifier.focusRequester(toggleFocus)) {
                    Icon(
                        if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        stringResource(if (playing) R.string.action_pause else R.string.action_play),
                    )
                }
                IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, stringResource(R.string.action_close)) }
            }
            LinearProgressIndicator(
                progress = { pulse.value.progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(2.dp).align(Alignment.BottomStart),
                trackColor = androidx.compose.ui.graphics.Color.Transparent,
            )
        }
    }
}

/**
 * The mini bar's sideways swipe: the bar follows the finger ([dragX], a layout offset, not a layer, so the host sees
 * the slot move and carries the picture along) and closes past a distance or a fling; up opens, down closes. Held
 * outside the bar so the picture over the bar's slot (the host's, on top of the bar) runs the same swipe.
 */
class MiniBarSwipe {
    var dragX by mutableFloatStateOf(0f)
    internal var widthPx by mutableFloatStateOf(0f)
    internal var onClose: () -> Unit = {}
    internal var onExpand: () -> Unit = {}
    internal var settling: Job? = null
}

/** The [MiniBarSwipe] gesture. Put it on a node that does not move with [MiniBarSwipe.dragX], or the touch would move with it. */
@Composable
fun Modifier.miniBarSwipe(swipe: MiniBarSwipe): Modifier {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    return pointerInput(swipe) {
        var total = Offset.Zero
        val tracker = VelocityTracker()
        fun settle(to: Float, then: () -> Unit = {}, spec: androidx.compose.animation.core.AnimationSpec<Float> = androidx.compose.animation.core.spring()) {
            swipe.settling = scope.launch { animate(swipe.dragX, to, animationSpec = spec) { x, _ -> swipe.dragX = x }; then() }
        }
        detectDragGestures(
            onDragStart = { swipe.settling?.cancel(); total = Offset.Zero; tracker.resetTracking() },
            onDrag = { change, amount ->
                change.consume()
                tracker.addPosition(change.uptimeMillis, change.position)
                total += amount
                swipe.dragX += amount.x
            },
            onDragCancel = { settle(0f) },
            onDragEnd = {
                val v = tracker.calculateVelocity()
                val fling = DISMISS_FLING_DP * density.density
                when (miniSwipe(total.x, total.y, v.x, v.y, swipe.widthPx, 48f * density.density, fling)) {
                    MiniSwipe.CLOSE_LEFT, MiniSwipe.CLOSE_RIGHT -> {
                        val to = if (swipe.dragX >= 0f) swipe.widthPx else -swipe.widthPx
                        settle(to, swipe.onClose, tween(180))
                    }
                    MiniSwipe.CLOSE_DOWN -> settle(0f, swipe.onClose)
                    MiniSwipe.EXPAND -> { settle(0f); swipe.onExpand() }
                    MiniSwipe.NONE -> settle(0f)
                }
            },
        )
    }
}

/** What the mini bar shows of the engine, polled while a video is shown. */
data class VideoPulse(val playing: Boolean = false, val progress: Float = 0f)

/** Polls the engine for the mini bar while it is composed (it only is while a video is shown). */
@Composable
fun rememberVideoPulse(session: com.abhinavxt.debforge.player.session.VideoSession): State<VideoPulse> {
    val pulse = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(VideoPulse()) }
    androidx.compose.runtime.LaunchedEffect(session) {
        while (true) {
            val p = session.player
            val duration = p?.duration?.takeIf { it > 0 && it != androidx.media3.common.C.TIME_UNSET }
            val next = VideoPulse(p?.playWhenReady == true && p.playbackState != androidx.media3.common.Player.STATE_ENDED, if (p != null && duration != null) p.currentPosition.toFloat() / duration else 0f)
            if (next != pulse.value) pulse.value = next
            kotlinx.coroutines.delay(500)
        }
    }
    return pulse
}
