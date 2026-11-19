package com.abhinavxt.debforge.player

import com.abhinavxt.debforge.domain.SponsorSegment
import com.abhinavxt.debforge.domain.SponsorSkip

/** What the audio player's SponsorBlock check should do at one tick. Pure: unit-tested in AudioSponsorSkipTest. */
sealed interface AudioSkip {
    data object None : AudioSkip
    /** Seek to [toMs], inside the track. */
    data class Seek(val toMs: Long, val startMs: Long) : AudioSkip
    /** The segment runs to the end of the track: finish it instead of seeking, once. */
    data class EndTrack(val startMs: Long) : AudioSkip
}

object AudioSponsorSkip {
    /** Within this of the end a segment counts as running to the end. */
    const val END_SLACK_MS = 750L

    fun decide(segments: List<SponsorSegment>, positionMs: Long, durationMs: Long, done: Set<Long>): AudioSkip {
        val s = SponsorSkip.autoSkip(segments, positionMs, done) ?: return AudioSkip.None
        val atEnd = durationMs > 0 && s.endMs >= durationMs - END_SLACK_MS
        return if (atEnd) AudioSkip.EndTrack(s.startMs) else AudioSkip.Seek(s.endMs, s.startMs)
    }

    /** A finished load still applies only if the track it was for is still current. */
    fun loadStillCurrent(loadedFor: String?, currentId: String?): Boolean = loadedFor != null && loadedFor == currentId
}
