package com.abhinavxt.debforge.player

/** The "Fade between tracks" lengths in seconds; 0 = off. */
val FADE_SECONDS = listOf(0, 1, 2, 3, 5, 8, 12)

/** What [fadeEligible] needs to know about a track. */
data class FadeInfo(val stream: Boolean, val albumKey: String?, val trackNo: Int?)

fun AudioTrack.fadeInfo() = FadeInfo(youtube != null || isRemoteStream, albumKey, trackNo)

/**
 * Player volume (0..1) at [posMs] of a [durMs] track playing at [speed]. A fade lasts [fadeMs] of real time, at most a
 * quarter of the track's real length, so a short track never spends its whole length fading.
 */
fun fadeVolume(posMs: Long, durMs: Long, speed: Float, fadeMs: Long, fadeIn: Boolean, fadeOut: Boolean): Float {
    if (durMs <= 0 || speed <= 0f || fadeMs <= 0 || posMs < 0) return 1f
    val realDur = durMs / speed
    val fade = minOf(fadeMs.toFloat(), realDur / 4)
    var v = 1f
    if (fadeIn) v = minOf(v, posMs / speed / fade)
    if (fadeOut) v = minOf(v, (durMs - posMs).coerceAtLeast(0) / speed / fade)
    return v.coerceIn(0f, 1f)
}

/**
 * Whether the end of [cur] fades out (and the start of [next] fades in). [next] is what plays next in play order,
 * null at the queue end. A sleep timer's "end of track" ([sleepEnd]) fades out whatever follows.
 */
fun fadeEligible(cur: FadeInfo, next: FadeInfo?, curDurMs: Long, repeatOne: Boolean, sleepEnd: Boolean = false): Boolean {
    if (cur.stream || curDurMs <= 0 || shouldRemember(curDurMs)) return false
    if (sleepEnd) return true
    if (next == null || next.stream || repeatOne) return false
    // Consecutive album tracks are mastered to run into each other; a missing track number counts as consecutive.
    val sameAlbum = cur.albumKey != null && cur.albumKey == next.albumKey &&
        (cur.trackNo == null || next.trackNo == null || next.trackNo == cur.trackNo + 1)
    return !sameAlbum
}
