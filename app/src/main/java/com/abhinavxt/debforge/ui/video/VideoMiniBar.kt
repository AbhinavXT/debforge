package com.abhinavxt.debforge.ui.video

import androidx.compose.animation.core.animate
import androidx.compose.foundation.clickable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.platform.LocalContext
import com.abhinavxt.debforge.ui.player.animatorDurationScale
import kotlinx.coroutines.coroutineScope
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.background
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import com.abhinavxt.debforge.ui.player.DISMISS_FLING_DP
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
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
        swipe.reset()
        onDispose { swipe.reset() }
    }
    val barSource = remember { MutableInteractionSource() }
    val expandLabel = stringResource(R.string.video_mini_expand)
    val playing by remember(pulse) { derivedStateOf { pulse.value.playing } }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .onSizeChanged { swipe.widthPx = it.width.toFloat(); swipe.heightPx = it.height.toFloat() }
            // Before the layer: the touch's coordinates must not move with the bar.
            .miniBarSwipe(swipe)
            .graphicsLayer { translationX = swipe.dragX; translationY = swipe.dragY; alpha = swipe.alpha }
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
                Box(Modifier.width(MiniVideoWidth).height(MiniBarHeight).onGloballyPositioned {
                    // The slot at rest: bounds in the root include the bar's layer offset, which the host applies itself.
                    onVideoSlot(it.boundsInRoot().translate(-swipe.dragX, -swipe.dragY))
                }) {
                    // While swiped, the host's live picture (a SurfaceView, which cannot fade with a layer) steps aside and a
                    // still of it is drawn here instead, so it moves and fades with the bar in the same frame.
                    swipe.frame?.let { frame ->
                        androidx.compose.foundation.Image(
                            frame, null,
                            Modifier.matchParentSize().background(androidx.compose.ui.graphics.Color.Black),
                            contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                        )
                    }
                }
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
 * The mini bar's swipe. The bar follows the finger in a graphics layer ([dragX], [dragY], read only in layer lambdas,
 * so no relayout per frame); the host's picture over the bar's slot follows through [onMoved] (a View transform set in
 * the same frame) and, once it has a still of it ([frame]), steps aside so the still moves and fades with the bar. Sideways and down close (sliding out with the fling's speed, then [onClose]), up opens. Held outside the bar
 * so the picture over the bar's slot (the host's, on top of the bar) runs the same swipe.
 */
class MiniBarSwipe {
    var dragX by mutableFloatStateOf(0f)
    var dragY by mutableFloatStateOf(0f)
    internal var widthPx by mutableFloatStateOf(0f)
    internal var heightPx by mutableFloatStateOf(0f)
    internal var onClose: () -> Unit = {}
    internal var onExpand: () -> Unit = {}
    /** Called on every move, before the frame is drawn: the host moves its picture (a View transform) in the same frame. */
    internal var onMoved: () -> Unit = {}
    internal var settling: Job? = null
    /** True from the moment a drag passes the touch slop until the bar is back at rest (or [reset]). */
    var held by mutableStateOf(false)
        internal set
    /** A still of the picture the host draws over the slot, shown in the slot while [held]; set and cleared by the host. */
    var frame by mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)

    /** The bar's opacity for its offset. Read in a layer lambda. */
    val alpha: Float get() = miniSwipeAlpha(dragX, dragY, widthPx, heightPx)

    internal fun moveTo(x: Float, y: Float) {
        if (x == dragX && y == dragY) return
        dragX = x; dragY = y
        onMoved()
    }

    internal fun reset() {
        settling?.cancel()
        dragX = 0f; dragY = 0f
        held = false
        onMoved()
    }

    /** Animates to ([x], [y]) from where it is, carrying the release velocity; [animate] false (system animations off) snaps. */
    internal suspend fun settleTo(x: Float, y: Float, vx: Float, vy: Float, spec: AnimationSpec<Float>, animate: Boolean) {
        if (!animate) { moveTo(x, y); return }
        coroutineScope {
            launch { animate(dragX, x, vx, spec) { v, _ -> moveTo(v, dragY) } }
            launch { animate(dragY, y, vy, spec) { v, _ -> moveTo(dragX, v) } }
        }
    }
}

private class SwipeCoords { var c: LayoutCoordinates? = null }

private val SpringBack = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)
private val SlideOut = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium, visibilityThreshold = 1f)

/** The speed (dp/s) of a flick that closes the video mini bar sideways or down whatever the distance. */
internal const val MINI_BAR_FLING_DP = 450f

/**
 * The [MiniBarSwipe] gesture: one pointer loop, the axis locked after the touch slop. Positions are read in the root's
 * coordinates, so it works on a node that itself moves with the swipe (the host's picture overlay moves with the bar).
 */
@Composable
fun Modifier.miniBarSwipe(swipe: MiniBarSwipe): Modifier {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val context = LocalContext.current
    val coords = remember { SwipeCoords() }
    return onGloballyPositioned { coords.c = it }.pointerInput(swipe) {
        val tracker = VelocityTracker()
        val slop = viewConfiguration.touchSlop
        awaitEachGesture {
            var released = false
            var downT = 0L
            var lastMoveT = 0L
            var liftT = 0L
            var liftTotal = Offset.Zero
            var dragging = false
            try {
            val down = awaitFirstDown(requireUnconsumed = false)
            downT = down.uptimeMillis; lastMoveT = downT; liftT = downT
            fun root(c: PointerInputChange): Offset = coords.c?.takeIf { it.isAttached }?.localToRoot(c.position) ?: c.position
            val start = root(down)
            tracker.resetTracking()
            tracker.addPosition(down.uptimeMillis, start)
            var horizontal: Boolean? = null
            var baseX = 0f
            var baseY = 0f
            var along = 0f
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                if (!change.pressed) {
                    if (horizontal == null) return@awaitEachGesture
                    // Where the finger lifted counts too: a flick can lift well past its last move, and the speed needs that point.
                    val lift = root(change)
                    tracker.addPosition(change.uptimeMillis, lift)
                    val total = lift - start
                    liftT = change.uptimeMillis
                    liftTotal = total
                    along = if (horizontal == true) pastSlop(total.x, slop) else pastSlop(total.y, slop)
                    break
                }
                if (horizontal == null && change.isConsumed) return@awaitEachGesture
                val pos = root(change)
                tracker.addPosition(change.uptimeMillis, pos)
                lastMoveT = change.uptimeMillis
                val total = pos - start
                if (horizontal == null) {
                    if (total.getDistance() < slop) continue
                    horizontal = miniSwipeHorizontal(total.x, total.y)
                    dragging = true
                    swipe.settling?.cancel()
                    swipe.held = true
                    baseX = swipe.dragX
                    baseY = swipe.dragY
                }
                change.consume()
                if (horizontal == true) {
                    along = pastSlop(total.x, slop)
                    swipe.moveTo(baseX + along, baseY)
                } else {
                    along = pastSlop(total.y, slop)
                    swipe.moveTo(baseX, baseY + miniDragY(along))
                }
            }
            released = true
            val isHorizontal = horizontal == true
            var v = tracker.calculateVelocity()
            // Events arriving far apart (a loaded device, a very quick flick) leave the tracker with little or no speed: when
            // the finger was still moving as it lifted, the average speed of the whole swipe stands in if it is greater.
            val ms = liftT - downT
            if (ms > 0 && liftT - lastMoveT <= 80) {
                val ax = liftTotal.x * 1000f / ms
                val ay = liftTotal.y * 1000f / ms
                v = androidx.compose.ui.unit.Velocity(if (kotlin.math.abs(ax) > kotlin.math.abs(v.x)) ax else v.x, if (kotlin.math.abs(ay) > kotlin.math.abs(v.y)) ay else v.y)
            }
            val fling = MINI_BAR_FLING_DP * density.density
            val result = miniSwipe(
                if (isHorizontal) along else 0f, if (isHorizontal) 0f else along,
                if (isHorizontal) v.x else 0f, if (isHorizontal) 0f else v.y,
                swipe.widthPx, 48f * density.density, fling,
            )
            val animate = animatorDurationScale(context) > 0f
            val vy = if (v.y < 0f) v.y * MINI_UP_RESISTANCE else v.y
            swipe.settling = scope.launch {
                when (result) {
                    MiniSwipe.CLOSE_LEFT, MiniSwipe.CLOSE_RIGHT, MiniSwipe.CLOSE_DOWN -> {
                        val (x, y) = miniExitTarget(result, swipe.widthPx, swipe.heightPx)
                        swipe.settleTo(
                            x, y,
                            if (isHorizontal) velocityToward(v.x, x - swipe.dragX) else 0f,
                            if (isHorizontal) 0f else velocityToward(v.y, y - swipe.dragY), SlideOut, animate
                        )
                        swipe.onClose()
                    }
                    MiniSwipe.EXPAND -> {
                        swipe.onExpand()
                        swipe.settleTo(0f, 0f, 0f, vy, SpringBack, animate)
                        swipe.held = false
                    }
                    MiniSwipe.NONE -> {
                        swipe.settleTo(0f, 0f, if (isHorizontal) v.x else 0f, if (isHorizontal) 0f else vy, SpringBack, animate)
                        swipe.held = false
                    }
                }
            }
            } finally {
                // The node left composition (or the gesture was cancelled) mid-drag: do not leave the bar displaced.
                if (dragging && !released) { swipe.settling?.cancel(); swipe.moveTo(0f, 0f); swipe.held = false }
            }
        }
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
