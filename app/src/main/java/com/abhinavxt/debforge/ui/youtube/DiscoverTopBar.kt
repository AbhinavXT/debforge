package com.abhinavxt.debforge.ui.youtube

import androidx.compose.runtime.Stable
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

    val connection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (enabled) hidden = hiddenAfterScroll(hidden, blockHeight + extraHeight, available.y)
            return Offset.Zero
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
