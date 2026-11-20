package com.abhinavxt.debforge.ui.video

import androidx.compose.ui.geometry.Rect
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

/** How far up (dp) a swipe on the watch page's picture must go to enter full screen on release. */
internal const val ENTER_FULL_DP = 64f
/** An upward fling faster than this (px/s) enters full screen whatever the distance. */
internal const val ENTER_FULL_FLING = 1200f

/** Pure: does releasing an upward drag of [up] px (up is positive) at [velocity] px/s (down is positive) enter full screen? */
internal fun shouldEnterFull(up: Float, velocity: Float, minDistance: Float): Boolean {
    if (!up.isFinite() || !velocity.isFinite() || !minDistance.isFinite() || up <= 0f) return false
    return up >= minDistance || velocity <= -ENTER_FULL_FLING
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

/** How much of an upward drag the mini bar follows (it resists: up only expands). */
internal const val MINI_UP_RESISTANCE = 0.35f

/** Pure: the bar's vertical offset for a finger that moved [dy] px (down positive): 1:1 down, resisted up. Non-finite is 0. */
internal fun miniDragY(dy: Float): Float = when {
    !dy.isFinite() -> 0f
    dy < 0f -> dy * MINI_UP_RESISTANCE
    else -> dy
}

/** Pure: true when a drag that moved [dx], [dy] runs sideways (decided once, after the touch slop). */
internal fun miniSwipeHorizontal(dx: Float, dy: Float): Boolean = abs(dx) >= abs(dy)

/** Pure: a drag's distance past the touch [slop] along one axis, keeping its sign (so the bar does not jump by the slop). */
internal fun pastSlop(delta: Float, slop: Float): Float {
    if (!delta.isFinite() || !slop.isFinite()) return 0f
    val past = (abs(delta) - slop).coerceAtLeast(0f)
    return if (delta < 0f) -past else past
}

/** How far down (in bar heights) the bar goes before it is fully faded and gone. */
internal const val MINI_DOWN_FADE_HEIGHTS = 1.5f

/** Pure: the bar's opacity at offset [x], [y] (down positive): fades to 0 at a full width sideways or [MINI_DOWN_FADE_HEIGHTS] heights down; up does not fade. */
internal fun miniSwipeAlpha(x: Float, y: Float, width: Float, height: Float): Float {
    if (!x.isFinite() || !y.isFinite() || width <= 0f || height <= 0f) return 1f
    val away = maxOf(abs(x) / width, y / (height * MINI_DOWN_FADE_HEIGHTS))
    return (1f - away).coerceIn(0f, 1f)
}

/** Pure: where a committed close slides to: (x, y) off-screen in the swipe's direction, fully faded there. */
internal fun miniExitTarget(swipe: MiniSwipe, width: Float, height: Float): Pair<Float, Float> = when (swipe) {
    MiniSwipe.CLOSE_LEFT -> -width to 0f
    MiniSwipe.CLOSE_RIGHT -> width to 0f
    MiniSwipe.CLOSE_DOWN -> 0f to height * MINI_DOWN_FADE_HEIGHTS
    else -> 0f to 0f
}

/** Pure: the release velocity [v] carried into a slide of [distance] px to go: only when it points the same way, else 0 (no pull-back). */
internal fun velocityToward(v: Float, distance: Float): Float = if (v.isFinite() && distance.isFinite() && v * distance > 0f) v else 0f

/** A View transform (pivot at the top left) that makes a view laid out at one rectangle look like it is at another. */
internal data class PictureLayer(val scaleX: Float, val scaleY: Float, val translationX: Float, val translationY: Float) {
    companion object { val Identity = PictureLayer(1f, 1f, 0f, 0f) }
}

/**
 * Pure: the transform that shows a picture laid out at [layout] at [visual] instead, so it moves and shrinks without
 * being measured again each frame. Identity when they are equal or [layout] has no size.
 */
internal fun layerFor(layout: Rect, visual: Rect): PictureLayer {
    val values = floatArrayOf(layout.left, layout.top, layout.width, layout.height, visual.left, visual.top, visual.width, visual.height)
    if (values.any { !it.isFinite() } || layout.width <= 0f || layout.height <= 0f) return PictureLayer.Identity
    return PictureLayer(visual.width / layout.width, visual.height / layout.height, visual.left - layout.left, visual.top - layout.top)
}

/**
 * Pure: does the picture's rectangle jump to its new place rather than animate? Yes with animations off, and when only
 * the size changed (a rotation, a two-step configuration change, a moving slot) with no mode change and nothing animating.
 */
internal fun shouldSnapRect(modeChanged: Boolean, animating: Boolean, animationsOff: Boolean): Boolean =
    animationsOff || (!modeChanged && !animating)

/** Pure: the release velocity (px/s, down positive) as the spring's start speed toward its destination; speed against it is dropped. */
internal fun settleVelocity(velocityY: Float, towardsMini: Boolean): Float {
    if (!velocityY.isFinite()) return 0f
    return if (towardsMini) velocityY.coerceAtLeast(0f) else velocityY.coerceAtMost(0f)
}
