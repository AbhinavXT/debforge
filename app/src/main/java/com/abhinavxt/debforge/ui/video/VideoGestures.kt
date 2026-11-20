package com.abhinavxt.debforge.ui.video

import kotlin.math.abs

/** How far down (of the way to the mini bar) a drag must go to minimise on release. */
internal const val MINIMISE_FRACTION = 0.3f
/** A downward fling faster than this (px/s) minimises whatever the distance. */
internal const val MINIMISE_FLING = 1500f

/** Pure: does releasing a drag that moved [offset] px (down is positive) of [travel] px at [velocity] px/s minimise? Otherwise it springs back. */
internal fun shouldMinimise(offset: Float, velocity: Float, travel: Float): Boolean {
    if (travel <= 0f || !travel.isFinite() || !offset.isFinite() || !velocity.isFinite()) return false
    if (velocity <= -MINIMISE_FLING) return false
    if (velocity >= MINIMISE_FLING) return offset > 0f
    return offset / travel >= MINIMISE_FRACTION
}

/** What a swipe on the mini bar does. */
internal enum class MiniSwipe { NONE, CLOSE_LEFT, CLOSE_RIGHT, CLOSE_DOWN, EXPAND }

/**
 * Pure: the result of a swipe on the mini bar. Sideways (past 37.5% of the [width] or a fling) closes, up
 * expands, down closes. [distance] is the least a vertical swipe must travel, [fling] the speed (px/s) that counts as a fling.
 */
internal fun miniSwipe(dx: Float, dy: Float, vx: Float, vy: Float, width: Float, distance: Float, fling: Float): MiniSwipe {
    if (!dx.isFinite() || !dy.isFinite() || !vx.isFinite() || !vy.isFinite()) return MiniSwipe.NONE
    val horizontal = abs(dx) >= abs(dy)
    if (horizontal) {
        val dir = com.abhinavxt.debforge.ui.player.miniPlayerDismissDirection(dx, vx, width, fling)
        return when (dir) { -1 -> MiniSwipe.CLOSE_LEFT; 1 -> MiniSwipe.CLOSE_RIGHT; else -> MiniSwipe.NONE }
    }
    return when {
        dy < 0f && (-dy >= distance || vy <= -fling) -> MiniSwipe.EXPAND
        dy > 0f && (dy >= distance || vy >= fling) -> MiniSwipe.CLOSE_DOWN
        else -> MiniSwipe.NONE
    }
}
