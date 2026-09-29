package com.abhinavxt.debforge.player.gestures

import kotlin.math.abs
import kotlin.math.hypot

/**
 * The player's touch gestures as a pure state machine: fed with touch events
 * and the clock, it tells [Callbacks] what the user is doing. No Android
 * types, so it's unit-tested in GestureEngineTest; the activity turns the
 * callbacks into seeks, brightness, volume, speed and zoom.
 *
 *  - Tap: toggle the controls (after [Config.doubleTapMs], to rule out a
 *    double tap).
 *  - Double tap on the left / right [Config.sideFraction] of the screen:
 *    seek back / forward [Config.seekStepSec]. Further single taps on the
 *    same side within [Config.continueMs] keep adding ("+10", "+20", "+30").
 *  - Double tap in the middle: play / pause.
 *  - Vertical drag: left half brightness, right half volume.
 *  - Horizontal drag: scrub through the video.
 *  - Long press ([Config.longPressMs]): fast playback while held; sliding
 *    sideways meanwhile picks another speed, one step per [Config.speedStepDp].
 *  - Two fingers: pinch to zoom.
 *
 * Drags that start in the outer [Config.edgeDp] of the screen are left alone:
 * that's where Android's own back / notification / home gestures start.
 */
class GestureEngine(config: Config, private val cb: Callbacks) {

    data class Config(
        val width: Float,
        val height: Float,
        /** Pixels per dp. */
        val density: Float,
        val sideFraction: Float = 0.35f,
        val seekStepSec: Int = 10,
        val doubleTapMs: Long = 250,
        val continueMs: Long = 650,
        val longPressMs: Long = 500,
        val slopDp: Float = 10f,
        val edgeDp: Float = 40f,
        val speedStepDp: Float = 40f,
        /** A second tap further than this from the first is a new tap, not a double tap. */
        val doubleTapDistanceDp: Float = 100f
    )

    enum class Side { LEFT, RIGHT }

    interface Callbacks {
        fun onSingleTap()
        fun onCenterDoubleTap()
        /** [seconds]: the total of the current run of taps on one side. */
        fun onSeekTaps(forward: Boolean, seconds: Int)
        fun onSeekTapsEnd()
        fun onVerticalStart(side: Side)
        /** [delta]: fraction of the screen height moved since the last call, up = positive. */
        fun onVertical(side: Side, delta: Float)
        fun onVerticalEnd(side: Side)
        fun onScrubStart()
        /** [fraction]: horizontal movement since the start, as a fraction of the width (right = positive). */
        fun onScrub(fraction: Float)
        fun onScrubEnd()
        /** The scrub was interrupted (second finger, cancelled touch): go back. */
        fun onScrubCancel()
        /** Return false to decline (e.g. nothing playing): the press then does nothing. */
        fun onLongPressStart(): Boolean
        /** [steps]: speed steps slid since the press began (right = faster). */
        fun onLongPressSlide(steps: Int)
        fun onLongPressEnd()
        fun onPinchStart()
        /** [scale]: finger distance now / at the start. */
        fun onPinch(scale: Float)
        fun onPinchEnd()
    }

    private enum class Mode { IDLE, PRESSED, VERTICAL, SCRUB, LONG_PRESS, PINCH, IGNORED }

    var config: Config = config
        private set

    private var mode = Mode.IDLE
    private var downX = 0f
    private var downY = 0f
    private var downT = 0L
    private var lastY = 0f
    private var verticalSide = Side.LEFT
    private var slideSteps = 0
    private var pinchStartSpan = 0f

    /** First tap, waiting to see if a second one follows. */
    private var pendingTap: Tap? = null
    /** A run of seek taps in progress. */
    private var streak: Streak? = null

    private data class Tap(val x: Float, val y: Float, val upT: Long)
    private data class Streak(val side: Side, val seconds: Int, val lastT: Long)

    /** Screen size changed (rotation, PiP). */
    fun resize(width: Float, height: Float) {
        config = config.copy(width = width, height = height)
    }

    fun setSeekStep(seconds: Int) {
        config = config.copy(seekStepSec = seconds)
    }

    /**
     * When the host should call [timer] next (clock of the events), or null.
     * Re-read after every event.
     */
    val deadline: Long?
        get() = listOfNotNull(
            pendingTap?.let { it.upT + config.doubleTapMs },
            streak?.let { it.lastT + config.continueMs },
            if (mode == Mode.PRESSED) downT + config.longPressMs else null
        ).minOrNull()

    private fun px(dp: Float) = dp * config.density

    fun down(x: Float, y: Float, t: Long) {
        mode = Mode.PRESSED
        downX = x; downY = y; downT = t; lastY = y
        slideSteps = 0
    }

    fun pointerDown(pointers: Int, span: Float, t: Long) {
        if (pointers < 2) return
        when (mode) {
            Mode.SCRUB -> cb.onScrubCancel()
            Mode.VERTICAL -> cb.onVerticalEnd(verticalSide)
            Mode.LONG_PRESS -> cb.onLongPressEnd()
            Mode.PINCH -> return
            else -> Unit
        }
        pendingTap = null
        endStreak()
        if (span <= 0f) {
            mode = Mode.IGNORED
            return
        }
        mode = Mode.PINCH
        pinchStartSpan = span
        cb.onPinchStart()
    }

    fun pointerUp(remaining: Int, t: Long) {
        if (mode == Mode.PINCH && remaining < 2) {
            cb.onPinchEnd()
            mode = Mode.IGNORED // the last finger lifting ends it; no tap
        }
    }

    fun move(x: Float, y: Float, t: Long, pointers: Int = 1, span: Float = 0f) {
        when (mode) {
            Mode.PINCH -> if (pointers >= 2 && span > 0f && pinchStartSpan > 0f) cb.onPinch(span / pinchStartSpan)
            Mode.PRESSED -> decide(x, y)
            Mode.VERTICAL -> {
                cb.onVertical(verticalSide, (lastY - y) / config.height)
                lastY = y
            }
            Mode.SCRUB -> cb.onScrub((x - downX) / config.width)
            Mode.LONG_PRESS -> {
                val steps = ((x - downX) / px(config.speedStepDp)).toInt()
                if (steps != slideSteps) {
                    slideSteps = steps
                    cb.onLongPressSlide(steps)
                }
            }
            Mode.IDLE, Mode.IGNORED -> Unit
        }
    }

    /** Still pressed: has the finger moved enough to be a drag, and which way? */
    private fun decide(x: Float, y: Float) {
        val dx = x - downX
        val dy = y - downY
        if (hypot(dx, dy) <= px(config.slopDp)) return
        pendingTap = null
        endStreak()
        val e = px(config.edgeDp)
        if (downX < e || downX > config.width - e || downY < e || downY > config.height - e) {
            mode = Mode.IGNORED
            return
        }
        when {
            abs(dy) > 1.5f * abs(dx) -> {
                mode = Mode.VERTICAL
                verticalSide = if (downX < config.width / 2) Side.LEFT else Side.RIGHT
                lastY = y
                cb.onVerticalStart(verticalSide)
                cb.onVertical(verticalSide, (downY - y) / config.height)
            }
            abs(dx) > 1.5f * abs(dy) -> {
                mode = Mode.SCRUB
                cb.onScrubStart()
                cb.onScrub(dx / config.width)
            }
            // Diagonal: wait for a clearer direction, but not forever.
            hypot(dx, dy) > 3 * px(config.slopDp) -> mode = Mode.IGNORED
        }
    }

    fun up(x: Float, y: Float, t: Long) {
        when (mode) {
            Mode.PRESSED -> tap(x, y, t)
            Mode.VERTICAL -> cb.onVerticalEnd(verticalSide)
            Mode.SCRUB -> cb.onScrubEnd()
            Mode.LONG_PRESS -> cb.onLongPressEnd()
            Mode.PINCH -> cb.onPinchEnd()
            Mode.IDLE, Mode.IGNORED -> Unit
        }
        mode = Mode.IDLE
    }

    fun cancel(t: Long) {
        when (mode) {
            Mode.VERTICAL -> cb.onVerticalEnd(verticalSide)
            Mode.SCRUB -> cb.onScrubCancel()
            Mode.LONG_PRESS -> cb.onLongPressEnd()
            Mode.PINCH -> cb.onPinchEnd()
            else -> Unit
        }
        mode = Mode.IDLE
        pendingTap = null
    }

    private fun sideOf(x: Float): Side? = when {
        x < config.width * config.sideFraction -> Side.LEFT
        x > config.width * (1 - config.sideFraction) -> Side.RIGHT
        else -> null
    }

    private fun tap(x: Float, y: Float, t: Long) {
        val side = sideOf(x)
        // A run of seek taps continues with single taps on the same side.
        streak?.let { s ->
            if (t - s.lastT <= config.continueMs && side != null) {
                val seconds = if (side == s.side) s.seconds + config.seekStepSec else config.seekStepSec
                streak = Streak(side, seconds, t)
                cb.onSeekTaps(side == Side.RIGHT, seconds)
                return
            }
            endStreak()
        }
        val first = pendingTap
        if (first != null && downT - first.upT <= config.doubleTapMs &&
            hypot(x - first.x, y - first.y) <= px(config.doubleTapDistanceDp)
        ) {
            pendingTap = null
            if (side == null) {
                cb.onCenterDoubleTap()
            } else {
                streak = Streak(side, config.seekStepSec, t)
                cb.onSeekTaps(side == Side.RIGHT, config.seekStepSec)
            }
            return
        }
        // A new tap (a stale first tap has already fired or is dropped).
        if (first != null) cb.onSingleTap()
        pendingTap = Tap(x, y, t)
    }

    private fun endStreak() {
        if (streak != null) {
            streak = null
            cb.onSeekTapsEnd()
        }
    }

    /** Call at (or after) [deadline]. */
    fun timer(t: Long) {
        pendingTap?.let { p ->
            // A second press is under way within the window: wait for its end.
            val secondUnderWay = mode == Mode.PRESSED && downT - p.upT <= config.doubleTapMs
            if (t >= p.upT + config.doubleTapMs && !secondUnderWay) {
                pendingTap = null
                cb.onSingleTap()
            }
        }
        streak?.let { s -> if (t >= s.lastT + config.continueMs) endStreak() }
        if (mode == Mode.PRESSED && t >= downT + config.longPressMs) {
            pendingTap = null
            endStreak()
            mode = if (cb.onLongPressStart()) Mode.LONG_PRESS else Mode.IGNORED
        }
    }
}
