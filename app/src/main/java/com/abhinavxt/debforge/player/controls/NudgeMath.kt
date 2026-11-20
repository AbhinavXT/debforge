package com.abhinavxt.debforge.player.controls

/** Timing of the sync nudge buttons: step clamping and hold-to-repeat pacing. */
object NudgeMath {
    const val FINE_STEP_MS = 100L
    const val COARSE_STEP_MS = 1_000L
    const val MAX_DELAY_MS = 10_000L
    const val FIRST_REPEAT_MS = 400L
    const val REPEAT_MS = 80L

    /** [current] moved by [by], kept within +-[MAX_DELAY_MS]. */
    fun step(current: Long, by: Long): Long = (current + by).coerceIn(-MAX_DELAY_MS, MAX_DELAY_MS)

    /** Wait before repeat number [index] (0 = the first repeat after the initial press). */
    fun repeatDelayMs(index: Int): Long = if (index <= 0) FIRST_REPEAT_MS else REPEAT_MS
}
