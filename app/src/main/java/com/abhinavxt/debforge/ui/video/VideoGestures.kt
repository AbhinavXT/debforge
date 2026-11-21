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

/** Share of the way to full screen a swipe up must reach to commit on release. */
internal const val ENTER_FULL_FRACTION = 0.3f
/** An upward fling faster than this (px/s) enters full screen whatever the distance. */
internal const val ENTER_FULL_FLING = 1200f
/** The finger travels this share of the picture's growth to reach full (the picture grows faster than the finger). */
internal const val ENTER_FULL_TRAVEL = 0.4f

/** Pure: how far (px) the finger goes for the picture to be fully grown, from the watch rect to the full rect. */
internal fun enterFullTravel(watch: Rect, full: Rect): Float =
    ((full.bottom - watch.bottom) * ENTER_FULL_TRAVEL).takeIf { it.isFinite() }?.coerceAtLeast(1f) ?: 1f

/** Pure: the swipe's progress 0..1 for a finger that moved [up] px (up positive) of [travel] px. */
internal fun enterFullProgress(up: Float, travel: Float): Float =
    if (!up.isFinite() || !travel.isFinite() || travel <= 0f) 0f else (up / travel).coerceIn(0f, 1f)

/** Pure: the picture's rectangle at swipe [progress]: the watch rect grown toward the full rect. */
internal fun enterFullRect(watch: Rect, full: Rect, progress: Float): Rect =
    androidx.compose.ui.geometry.lerp(watch, full, progress.coerceIn(0f, 1f))

/** Pure: the watch page's opacity at [progress]: it fades out as the picture grows. */
internal fun enterFullPageAlpha(progress: Float): Float = 1f - progress.coerceIn(0f, 1f)

/** Progress at which the scrim is fully black (before the picture has quite filled the screen). */
internal const val SCRIM_FULL_AT = 0.8f

/** Pure: the black scrim's opacity at [progress]: it fades in as the page fades out, fully black by [SCRIM_FULL_AT]. */
internal fun enterFullScrimAlpha(progress: Float): Float = (progress / SCRIM_FULL_AT).coerceIn(0f, 1f)

/** Pure: does releasing a swipe up at [progress] (0..1) and [velocity] px/s (down is positive) enter full screen? */
internal fun shouldEnterFull(progress: Float, velocity: Float): Boolean {
    if (!progress.isFinite() || !velocity.isFinite() || progress <= 0f) return false
    if (velocity >= ENTER_FULL_FLING) return false
    return progress >= ENTER_FULL_FRACTION || velocity <= -ENTER_FULL_FLING
}

/** Pure: the release velocity (px/s, down positive) as progress per second toward [commit]'s end (1 or 0); speed against it is dropped. */
internal fun enterFullSettleVelocity(velocityY: Float, travel: Float, commit: Boolean): Float {
    if (!velocityY.isFinite() || !travel.isFinite() || travel <= 0f) return 0f
    val perSecond = -velocityY / travel
    return if (commit) perSecond.coerceAtLeast(0f) else perSecond.coerceAtMost(0f)
}

/** Share of the screen height a swipe down in full screen must travel to leave on release. */
internal const val LEAVE_FULL_FRACTION = 0.15f
/** A downward fling faster than this (px/s) leaves full screen whatever the distance. */
internal const val LEAVE_FULL_FLING = 1000f
/** The finger travels this share of the screen height for the picture to be fully shrunk. */
internal const val LEAVE_FULL_TRAVEL = 0.5f
/** How much smaller the picture gets at the end of that travel (0.15 = 85% of its size). */
internal const val LEAVE_FULL_SHRINK = 0.15f

/** Pure: does releasing a swipe down in full screen that moved [dy] px at [velocity] px/s (down positive) of a [height] px screen leave? */
internal fun shouldLeaveFull(dy: Float, velocity: Float, height: Float): Boolean {
    if (!dy.isFinite() || !velocity.isFinite() || !height.isFinite() || height <= 0f || dy <= 0f) return false
    if (velocity <= -LEAVE_FULL_FLING) return false
    if (velocity >= LEAVE_FULL_FLING) return true
    return dy / height >= LEAVE_FULL_FRACTION
}

/** Pure: the picture's rectangle while a swipe down in full screen has moved [dy] px: [full] moved down with the finger, a little smaller. */
internal fun leaveFullRect(full: Rect, dy: Float, height: Float): Rect {
    if (!dy.isFinite() || !height.isFinite() || height <= 0f) return full
    val down = dy.coerceAtLeast(0f)
    val p = (down / (height * LEAVE_FULL_TRAVEL)).coerceIn(0f, 1f)
    val s = 1f - LEAVE_FULL_SHRINK * p
    val w = full.width * s
    val h = full.height * s
    val left = full.left + (full.width - w) / 2f
    return Rect(left, full.top + down, left + w, full.top + down + h)
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
