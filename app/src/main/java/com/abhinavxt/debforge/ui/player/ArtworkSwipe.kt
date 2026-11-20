package com.abhinavxt.debforge.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.DisposableEffect
import com.abhinavxt.debforge.player.AudioRepeat
import com.abhinavxt.debforge.player.AudioState
import com.abhinavxt.debforge.player.AudioTrack
import com.abhinavxt.debforge.player.upNextOrder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.platform.LocalViewConfiguration
import kotlin.math.abs
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal const val ARTWORK_SWIPE_FRACTION = 0.25f
internal const val ARTWORK_SWIPE_VELOCITY = 1000f

/** True when a drag of [dx], [dy] px that just passed touch slop is horizontal (the artwork's), not vertical (the sheet's). */
internal fun isHorizontalDrag(dx: Float, dy: Float): Boolean = abs(dx) > abs(dy)

/**
 * -1 = previous, 1 = next, 0 = spring back. Swiping left is next (right in a right-to-left layout).
 * Unmeasured widths and non-finite values never commit.
 */
internal fun artworkSwipeDirection(offset: Float, velocity: Float, width: Float, rtl: Boolean): Int {
    if (width <= 0f || !width.isFinite() || !offset.isFinite() || !velocity.isFinite()) return 0
    val raw = when {
        abs(velocity) >= ARTWORK_SWIPE_VELOCITY -> if (velocity < 0f) 1 else -1
        abs(offset) >= width * ARTWORK_SWIPE_FRACTION -> if (offset < 0f) 1 else -1
        else -> 0
    }
    return if (rtl) -raw else raw
}

/** Past this far into a track, Previous restarts it (Media3's default max seek-to-previous position). */
internal const val PREVIOUS_RESTARTS_AFTER_MS = 3000L

/** A drag toward a side with nothing to bring in moves the artwork this much of the finger (rubber band). */
internal const val ARTWORK_EDGE_RESISTANCE = 0.3f

/** Queue index of the item before the current one in play order, or null when there is none or it is unknown (shuffle without repeat). */
internal fun previousQueueIndex(order: List<Int>, current: Int, repeat: AudioRepeat, queueSize: Int): Int? {
    val pos = order.indexOf(current)
    if (pos < 0) return null
    if (pos > 0) return order[pos - 1]
    // The order starts at the current item: only a full repeating cycle says what came before it.
    return if (repeat == AudioRepeat.ALL && order.size == queueSize && order.size > 1) order.last() else null
}

/** True when a swipe in [dir] (1 next, -1 previous) does something: the same rule as the buttons, minus Previous at the very start. */
internal fun canArtworkSwipe(state: AudioState, dir: Int): Boolean =
    if (dir > 0) state.hasNext else state.positionMs > PREVIOUS_RESTARTS_AFTER_MS || state.hasPrevious

/**
 * The track a swipe in [dir] brings in: 1 = next, -1 = previous (the current track itself when Previous restarts it).
 * Null when the swipe does nothing or the track is not known ahead (previous under shuffle without repeat).
 */
internal fun artworkNeighbour(state: AudioState, dir: Int): AudioTrack? {
    val current = state.track ?: return null
    if (!canArtworkSwipe(state, dir)) return null
    if (dir > 0) return upNextOrder(state.order, state.index, state.repeat).firstOrNull()?.let { state.queue.getOrNull(it) }
    if (state.positionMs > PREVIOUS_RESTARTS_AFTER_MS) return current
    return previousQueueIndex(state.order, state.index, state.repeat, state.queue.size)?.let { state.queue.getOrNull(it) }
}

/** The carousel's live position: [offset] is the finger / settle; a neighbour sits one [span] (width plus gap) beside the centre. */
internal class ArtworkSwipeState {
    var offset by mutableFloatStateOf(0f)
    /** True from a committed swipe until its slide has landed. */
    var sliding by mutableStateOf(false)
    internal var width = 0f
    internal var gap = 0f
    val span: Float get() = width + gap
    internal var job: Job? = null
}

/**
 * Carousel swipe: follows the finger horizontally; past a threshold or on a fling the artwork keeps going out (carrying the
 * fling's speed) while its neighbour, already drawn beside it, arrives in the centre; otherwise it springs back. A side with
 * nothing to bring in ([canSwipe] false) only gives a little (rubber band) and always springs back. The axis locks once the
 * drag passes touch slop, and vertical drags are left untouched so the sheet's swipe-down keeps working. [onSwipe] gets 1 / -1
 * at the start of a committed slide; [onLanded] runs in the same frame the offset returns to 0, so the caller can put the
 * arrived artwork in the centre without a visible jump. [animate] false skips the slide (reduced motion).
 */
internal fun Modifier.artworkSwipe(
    state: ArtworkSwipeState,
    gap: Float,
    canSwipe: (Int) -> Boolean,
    animate: Boolean,
    onSwipe: (Int) -> Unit,
    onLanded: (Int) -> Unit
): Modifier = composed {
    val scope = rememberCoroutineScope()
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val slop = LocalViewConfiguration.current.touchSlop
    val canSwipeNow by rememberUpdatedState(canSwipe)
    val animateNow by rememberUpdatedState(animate)
    val onSwipeNow by rememberUpdatedState(onSwipe)
    val onLandedNow by rememberUpdatedState(onLanded)
    val rtlNow by rememberUpdatedState(rtl)
    state.gap = gap
    // Left the composition mid-slide: land at once so the next sheet does not open displaced.
    DisposableEffect(state) { onDispose { state.job?.cancel(); state.offset = 0f; state.sliding = false } }

    /** The swipe a drag at [offset] heads for: 1 next, -1 previous. */
    fun dirOf(offset: Float): Int = (if (offset < 0f) 1 else -1) * (if (rtlNow) -1 else 1)

    fun settle(velocity: Float) {
        val dir = artworkSwipeDirection(state.offset, velocity, state.width, rtlNow)
        state.job?.cancel()
        if (dir == 0 || !canSwipeNow(dir)) {
            if (!animateNow) { state.offset = 0f; return }
            state.job = scope.launch {
                Animatable(state.offset).animateTo(
                    0f, spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
                    initialVelocity = velocity
                ) { state.offset = value }
            }
            return
        }
        state.sliding = true
        onSwipeNow(dir)
        if (!animateNow) {
            state.offset = 0f
            state.sliding = false
            onLandedNow(dir)
            return
        }
        // Screen direction the artwork leaves in: left for next in LTR, mirrored in RTL.
        val out = (if (dir > 0) -1f else 1f) * (if (rtlNow) -1f else 1f)
        state.job = scope.launch {
            try {
                // Keep moving the way the finger went, carrying its speed; the neighbour rides in beside it.
                Animatable(state.offset).animateTo(
                    out * state.span, spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow, visibilityThreshold = 1f),
                    initialVelocity = if (velocity * out > 0f) velocity else 0f
                ) { state.offset = value }
            } finally {
                // One snapshot: the centre moves on to the arrived artwork exactly where it already is on screen.
                state.offset = 0f
                state.sliding = false
                onLandedNow(dir)
            }
        }
    }

    this
        .onSizeChanged { state.width = it.width.toFloat() }
        .pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                // A committed slide runs to its end; touches meanwhile are left to the children.
                if (state.sliding) return@awaitEachGesture
                state.job?.cancel()
                var total = Offset.Zero
                // Wait for slop, then lock the axis: horizontal is ours, vertical is left to the sheet.
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                    if (change.isConsumed || !change.pressed) return@awaitEachGesture
                    total += change.positionChange()
                    if (total.getDistance() >= slop) {
                        if (!isHorizontalDrag(total.x, total.y)) return@awaitEachGesture
                        change.consume()
                        break
                    }
                }
                val tracker = VelocityTracker()
                tracker.addPosition(down.uptimeMillis, down.position)
                var cancelled = false
                fun moveBy(dx: Float) {
                    val next = state.offset + dx
                    // Toward a side with nothing to bring in the artwork resists instead of following.
                    state.offset = if (next != 0f && !canSwipeNow(dirOf(next)) && abs(next) > abs(state.offset)) state.offset + dx * ARTWORK_EDGE_RESISTANCE else next
                }
                moveBy(total.x)
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id }
                    if (change == null) { cancelled = true; break }
                    tracker.addPosition(change.uptimeMillis, change.position)
                    if (!change.pressed) break
                    val dx = change.positionChange().x
                    change.consume()
                    moveBy(dx)
                }
                val velocity = if (cancelled) 0f else tracker.calculateVelocity().x
                settle(velocity)
            }
        }
}
