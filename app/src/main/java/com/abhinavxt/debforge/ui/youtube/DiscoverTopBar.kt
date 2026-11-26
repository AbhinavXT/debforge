package com.abhinavxt.debforge.ui.youtube

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Stable
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import kotlin.math.roundToInt

/** How far the top block is hidden after a scroll of [dy] (negative = content moves up): enter-always, clamped to 0..[limit]. */
internal fun hiddenAfterScroll(hidden: Float, limit: Float, dy: Float): Float =
    (hidden - dy).coerceIn(0f, limit.coerceAtLeast(0f))

/**
 * One offset for Discover's whole top block (header and segment switch, plus the Web address bar):
 * it slides away while the content scrolls down and returns on the first scroll up.
 * Consumes nothing; feed it with [connection] on an ancestor of the scrolling content.
 */
@Stable
class DiscoverTopBar {
    var hidden by mutableFloatStateOf(0f)
    internal var blockHeight by mutableFloatStateOf(0f)
    internal var extraHeight by mutableFloatStateOf(0f)
    /** False with a D-pad or keyboard, where the bar stays put. */
    var enabled = true

    fun reset() { hidden = 0f }
    internal fun extraHeightReset() { extraHeight = 0f; hidden = 0f }

    internal var scope: CoroutineScope? = null
    internal var snapPx = 0f
    private var animating = false
    private var acc = 0f
    private var lastY = 0f

    private fun snapTo(target: Float) {
        val sc = scope ?: return
        animating = true
        sc.launch {
            try { animate(hidden, target, animationSpec = tween(200)) { v, _ -> hidden = v } }
            finally { animating = false; acc = 0f }
        }
    }

    /** The page scrolled to its very top: bar fully shown. */
    fun pageAtTop() { if (enabled && !animating && hidden > 0f) snapTo(0f) }

    fun touchDown(rawY: Float) { lastY = rawY; acc = 0f }

    /**
     * Finger movement on a page that is not a Compose scroller (screen coordinates, so the page resizing as the bar slides
     * cannot disturb it). Finger up past [snapPx] hides the bar (if the page can scroll on), finger down shows it.
     */
    fun touchMove(rawY: Float, canScrollDown: Boolean) {
        val dy = lastY - rawY
        lastY = rawY
        if (animating || !enabled) return
        acc = if (acc * dy < 0f) dy else acc + dy
        val limit = blockHeight + extraHeight
        if (acc > snapPx && canScrollDown && hidden < limit) snapTo(limit) else if (acc < -snapPx && hidden > 0f) snapTo(0f)
    }

    // Direction of the last list scroll: >0 up (reveal), <0 down (hide).
    private var lastDy = 0f

    val connection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (enabled) {
                hidden = hiddenAfterScroll(hidden, blockHeight + extraHeight, available.y)
                if (available.y != 0f) lastDy = available.y
            }
            return Offset.Zero
        }

        // Settle in the direction the user last scrolled (up shows, down hides), so a short scroll up is never undone.
        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            val limit = blockHeight + extraHeight
            if (enabled && hidden > 0f && hidden < limit) snapTo(if (lastDy < 0f) limit else 0f)
            return Velocity.Zero
        }
    }
}

/** Slides this part of the top block away with [bar]; [extra] is the part after the Discover block (the Web address bar). */
internal fun Modifier.slideAway(bar: DiscoverTopBar, extra: Boolean = false): Modifier = this
    .layout { measurable, constraints ->
        val p = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
        val h = p.height.toFloat()
        if (extra) bar.extraHeight = h else bar.blockHeight = h
        val skip = if (extra) bar.blockHeight else 0f
        val gone = (bar.hidden - skip).coerceIn(0f, h).roundToInt()
        layout(p.width, p.height - gone) { p.place(0, -gone) }
    }
    .clipToBounds()
