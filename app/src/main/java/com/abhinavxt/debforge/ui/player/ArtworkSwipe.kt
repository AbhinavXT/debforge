package com.abhinavxt.debforge.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
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

/**
 * Carousel swipe: the content follows the finger horizontally; past a threshold or on a fling it slides out and the
 * next (or previous) one slides in, otherwise it springs back. The axis locks once the drag passes touch slop, and
 * vertical drags are left untouched so the sheet's swipe-down keeps working. [onSwipe] gets 1 / -1 and returns whether
 * a track change happened; [animate] false skips the slide (reduced motion). [onSliding] reports a committed slide.
 */
internal fun Modifier.artworkSwipe(
    canSwipe: (Int) -> Boolean,
    animate: Boolean,
    onSliding: (Boolean) -> Unit,
    onSwipe: (Int) -> Unit
): Modifier = composed {
    val scope = rememberCoroutineScope()
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val slop = LocalViewConfiguration.current.touchSlop
    val offset = remember { Animatable(0f) }
    val state = remember { object { var width = 0f; var job: Job? = null } }
    val canSwipeNow by rememberUpdatedState(canSwipe)
    val animateNow by rememberUpdatedState(animate)
    val onSlidingNow by rememberUpdatedState(onSliding)
    val onSwipeNow by rememberUpdatedState(onSwipe)
    val rtlNow by rememberUpdatedState(rtl)

    fun settle(velocity: Float) {
        val dir = artworkSwipeDirection(offset.value, velocity, state.width, rtlNow)
        state.job?.cancel()
        state.job = scope.launch {
            if (dir == 0 || !canSwipeNow(dir)) {
                if (animateNow) offset.animateTo(0f, spring(stiffness = 400f)) else offset.snapTo(0f)
                return@launch
            }
            if (!animateNow) {
                offset.snapTo(0f)
                onSwipeNow(dir)
                return@launch
            }
            // Screen direction the artwork leaves in: left for next in LTR, mirrored in RTL.
            val out = (if (dir > 0) -1f else 1f) * (if (rtlNow) -1f else 1f)
            onSlidingNow(true)
            try {
                offset.animateTo(out * state.width, tween(150))
                onSwipeNow(dir)
                offset.snapTo(-out * state.width)
                offset.animateTo(0f, tween(200))
            } finally {
                onSlidingNow(false)
            }
        }
    }

    this
        .onSizeChanged { state.width = it.width.toFloat() }
        .pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
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
                scope.launch { offset.snapTo(offset.value + total.x) }
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id }
                    if (change == null) { cancelled = true; break }
                    tracker.addPosition(change.uptimeMillis, change.position)
                    if (!change.pressed) break
                    val dx = change.positionChange().x
                    change.consume()
                    scope.launch { offset.snapTo(offset.value + dx) }
                }
                val velocity = if (cancelled) 0f else tracker.calculateVelocity().x
                settle(velocity)
            }
        }
        .graphicsLayer { translationX = offset.value }
}
