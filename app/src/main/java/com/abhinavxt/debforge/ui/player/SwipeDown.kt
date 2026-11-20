package com.abhinavxt.debforge.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.composed
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.layout.onSizeChanged
import kotlin.math.abs
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal const val DISMISS_FRACTION = 0.25f
internal const val DISMISS_VELOCITY = 1500f

/**
 * A downward drag of [offset] px released at [velocity] px/s (down positive) on a [height] px tall sheet closes it when it
 * went a quarter of the sheet or was flung down, unless it was clearly flung back up. A slow release (velocity near 0,
 * or a little upward wobble as the finger lifts) never cancels a long drag. Unmeasured or non-finite values never close.
 */
internal fun shouldDismissSwipe(offset: Float, velocity: Float, height: Float): Boolean {
    if (!offset.isFinite() || !velocity.isFinite() || !height.isFinite() || height <= 0f || offset <= 0f) return false
    if (velocity <= -DISMISS_VELOCITY) return false
    return velocity >= DISMISS_VELOCITY || offset >= height * DISMISS_FRACTION
}

internal const val DISMISS_FRACTION_MINI = 0.375f
internal const val DISMISS_FLING_DP = 1000f

/** -1 / 1 to throw the bar off to the left / right, 0 to spring back. Unmeasured widths never dismiss. */
internal fun miniPlayerDismissDirection(offset: Float, velocity: Float, width: Float, flingVelocity: Float): Int {
    if (width <= 0f || !width.isFinite() || !offset.isFinite() || !velocity.isFinite()) return 0
    if (abs(velocity) >= flingVelocity) return if (velocity > 0f) 1 else -1
    return if (abs(offset) >= width * DISMISS_FRACTION_MINI) (if (offset > 0f) 1 else -1) else 0
}

/** A track change resets the bar unless a committed dismiss is still animating (it must finish and close). */
internal fun shouldResetOnTrackChange(dismissing: Boolean, dismissAnimating: Boolean): Boolean = !dismissing || !dismissAnimating

internal class SwipeDownState(val onDismiss: () -> Unit) {
    var offset by mutableFloatStateOf(0f)
    var height by mutableFloatStateOf(0f)
    /** False: a committed dismiss closes at release, from where the finger left the sheet (a shared element flies from there). */
    var slideOut = true
    internal var job: Job? = null
    /** True from a committed dismiss until the next touch or [reset]; the dismiss is final and fires once. */
    var committed = false
    internal var fired = false
    /** False while the parent is hiding the sheet; a touch then must not bring the sheet back mid-exit. */
    var open = true
    /** True while a dismiss is in flight and cannot be taken back: committed, or fired and the sheet is closing. */
    internal val locked: Boolean get() = committed || (fired && !open)

    /** Fires [onDismiss] once per commit. */
    fun fire() {
        if (fired) return
        fired = true
        committed = false
        onDismiss()
    }

    /** Back to rest: the sheet is shown again (a new touch, or reopened while it was still leaving). */
    fun reset() {
        job?.cancel()
        committed = false
        fired = false
        offset = 0f
    }
}

@Composable
internal fun rememberSwipeDown(onDismiss: () -> Unit, open: Boolean = true, slideOut: Boolean = true): SwipeDownState {
    val latest by rememberUpdatedState(onDismiss)
    val state = remember { SwipeDownState { latest() } }
    state.slideOut = slideOut
    state.open = open
    // Shown again while an earlier dismiss was still fading out: the same composition lives on, so start from the top.
    LaunchedEffect(open) { if (open) state.reset() }
    return state
}

/**
 * Drag the sheet down to minimise it. Drags that start on non-scrolling parts use [draggable]; drags in the
 * queue only start dismissing once the list is at its top (the leftover of the scroll). Horizontal drags (seek bar) never reach it.
 */
internal fun Modifier.swipeDownToDismiss(state: SwipeDownState): Modifier = composed {
    val scope = rememberCoroutineScope()
    val animate = animatorDurationScale(LocalContext.current) > 0f
    val animateNow by rememberUpdatedState(animate)
    fun touch() {
        if (state.locked) return
        state.job?.cancel()
        if (state.fired) state.reset()
    }
    // Leaving mid-slide must still close: the dismiss is final once committed.
    DisposableEffect(state) { onDispose { if (state.committed) state.fire() } }
    fun settle(velocity: Float) {
        if (state.locked) return
        state.job?.cancel()
        val dismiss = shouldDismissSwipe(state.offset, velocity, state.height)
        if (dismiss) {
            state.committed = true
            if (!state.slideOut || !animateNow) {
                if (!animateNow) state.offset = state.height
                state.fire()
                return
            }
            // Keep moving down with the fling, and only then let the parent hide the sheet.
            state.job = scope.launch {
                Animatable(state.offset).animateTo(
                    state.height, spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium),
                    initialVelocity = velocity.coerceAtLeast(0f)
                ) { state.offset = value }
                state.fire()
            }
        } else if (!animateNow) {
            state.offset = 0f
        } else {
            state.job = scope.launch {
                Animatable(state.offset).animateTo(
                    0f, spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
                    initialVelocity = velocity
                ) { state.offset = value }
            }
        }
    }
    val connection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (state.locked) return if (source == NestedScrollSource.UserInput) available else Offset.Zero
                if (source == NestedScrollSource.UserInput) touch()
                if (source != NestedScrollSource.UserInput || state.offset <= 0f || available.y >= 0f) return Offset.Zero
                val next = (state.offset + available.y).coerceAtLeast(0f)
                val used = next - state.offset
                state.offset = next
                return Offset(0f, used)
            }
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (state.locked) return Offset.Zero
                if (source != NestedScrollSource.UserInput || available.y <= 0f) return Offset.Zero
                state.offset += available.y
                return Offset(0f, available.y)
            }
            override suspend fun onPreFling(available: Velocity): Velocity {
                if (state.locked) return available
                if (state.offset <= 0f) return Velocity.Zero
                settle(available.y)
                return available
            }
        }
    }
    val drag = rememberDraggableState { if (!state.locked) state.offset = (state.offset + it).coerceAtLeast(0f) }
    this
        .onSizeChanged { state.height = it.height.toFloat() }
        .nestedScroll(connection)
        .draggable(drag, Orientation.Vertical, onDragStarted = { touch() }, onDragStopped = { settle(it) })
        .graphicsLayer { translationY = state.offset }
}
