package com.abhinavxt.debforge.player

/** Lengths offered for the audio player's skip back / forward buttons, in seconds. */
val SKIP_LENGTHS = listOf(5, 10, 15, 30)

const val DEFAULT_SKIP_SECONDS = 10

/** A stored value that is not one of [SKIP_LENGTHS] falls back to the default. */
fun validSkipSeconds(v: Int?): Int = if (v != null && v in SKIP_LENGTHS) v else DEFAULT_SKIP_SECONDS

fun skipMs(seconds: Int): Long = validSkipSeconds(seconds) * 1000L
