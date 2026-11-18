package com.abhinavxt.debforge.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.runtime.Composable
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal const val DISMISS_FRACTION = 0.25f
internal const val DISMISS_VELOCITY = 1500f

/** A downward drag of [offset] px at [velocity] px/s on a [height] px tall sheet completes when it went far or fast enough. */
internal fun shouldDismissSwipe(offset: Float, velocity: Float, height: Float): Boolean =
    offset > 0f && height > 0f && (offset >= height * DISMISS_FRACTION || velocity >= DISMISS_VELOCITY)

internal class SwipeDownState(val onDismiss: () -> Unit) {
    var offset by mutableFloatStateOf(0f)
    var height by mutableFloatStateOf(0f)
}

@Composable
internal fun rememberSwipeDown(onDismiss: () -> Unit): SwipeDownState {
    val latest by rememberUpdatedState(onDismiss)
    return remember { SwipeDownState { latest() } }
}

/**
 * Drag the sheet down to minimise it. Drags that start on non-scrolling parts use [draggable]; drags in the
 * queue only start dismissing once the list is at its top (the leftover of the scroll). Horizontal drags (seek bar) never reach it.
 */
internal fun Modifier.swipeDownToDismiss(state: SwipeDownState): Modifier = composed {
    val scope = rememberCoroutineScope()
    val anim = remember { object { var job: Job? = null } }
    fun settle(velocity: Float) {
        anim.job?.cancel()
        val dismiss = shouldDismissSwipe(state.offset, velocity, state.height)
        // On dismiss the sheet eases back to place while it fades out, so the shared artwork does not fly from a displaced spot.
        anim.job = scope.launch {
            Animatable(state.offset).animateTo(0f, if (dismiss) tween(300) else spring(stiffness = 400f)) { state.offset = value }
        }
        if (dismiss) state.onDismiss()
    }
    val connection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput) anim.job?.cancel()
                if (source != NestedScrollSource.UserInput || state.offset <= 0f || available.y >= 0f) return Offset.Zero
                val next = (state.offset + available.y).coerceAtLeast(0f)
                val used = next - state.offset
                state.offset = next
                return Offset(0f, used)
            }
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput || available.y <= 0f) return Offset.Zero
                state.offset += available.y
                return Offset(0f, available.y)
            }
            override suspend fun onPreFling(available: Velocity): Velocity {
                if (state.offset <= 0f) return Velocity.Zero
                settle(available.y)
                return available
            }
        }
    }
    val drag = rememberDraggableState { state.offset = (state.offset + it).coerceAtLeast(0f) }
    this
        .onSizeChanged { state.height = it.height.toFloat() }
        .nestedScroll(connection)
        .draggable(drag, Orientation.Vertical, onDragStarted = { anim.job?.cancel() }, onDragStopped = { settle(it) })
        .graphicsLayer { translationY = state.offset }
}
