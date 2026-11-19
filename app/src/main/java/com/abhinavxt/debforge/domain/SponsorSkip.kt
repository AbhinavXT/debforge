package com.abhinavxt.debforge.domain

/** One SponsorBlock segment to skip, in milliseconds. [category] is SponsorBlock's own name ("sponsor", ...). */
@com.squareup.moshi.JsonClass(generateAdapter = true)
data class SponsorSegment(val category: String, val startMs: Long, val endMs: Long, val uuid: String = "")

/**
 * Turns SponsorBlock segments into what a player can act on: overlapping and
 * touching segments merged, clamped to the video, tiny ones dropped. Pure
 * Kotlin: unit-tested in SponsorSkipTest.
 */
object SponsorSkip {

    /** SponsorBlock category to the skip button's [IntroSkip.Kind]; null for categories we don't offer. */
    fun kindOf(category: String): IntroSkip.Kind? = when (category) {
        "sponsor" -> IntroSkip.Kind.SPONSOR
        "selfpromo" -> IntroSkip.Kind.SELF_PROMO
        "interaction" -> IntroSkip.Kind.INTERACTION
        "intro" -> IntroSkip.Kind.INTRO
        "outro" -> IntroSkip.Kind.CREDITS
        "preview" -> IntroSkip.Kind.PREVIEW
        else -> null
    }

    const val MIN_MS = 1_000L

    /**
     * Sorted, non-overlapping segments inside 0..[durationMs] (pass 0 or less
     * when the duration is unknown) and at least [MIN_MS] long. A merged
     * segment keeps the category of the one that started it.
     */
    fun normalise(segments: List<SponsorSegment>, durationMs: Long): List<SponsorSegment> {
        val clamped = segments.mapNotNull {
            val start = it.startMs.coerceAtLeast(0)
            val end = if (durationMs > 0) it.endMs.coerceAtMost(durationMs) else it.endMs
            if (end > start) it.copy(startMs = start, endMs = end) else null
        }.sortedBy { it.startMs }
        val out = ArrayList<SponsorSegment>()
        for (s in clamped) {
            val last = out.lastOrNull()
            if (last != null && s.startMs <= last.endMs) {
                if (s.endMs > last.endMs) out[out.lastIndex] = last.copy(endMs = s.endMs)
            } else {
                out += s
            }
        }
        return out.filter { it.endMs - it.startMs >= MIN_MS }
    }

    /** The segment containing [positionMs] (start inclusive, end exclusive) in a [normalise]d list. */
    fun segmentAt(segments: List<SponsorSegment>, positionMs: Long): SponsorSegment? =
        segments.firstOrNull { positionMs >= it.startMs && positionMs < it.endMs }

    /** The first segment starting after [positionMs], for scheduling the next check. */
    fun nextAfter(segments: List<SponsorSegment>, positionMs: Long): SponsorSegment? =
        segments.firstOrNull { it.startMs > positionMs }

    /** Where to seek when [positionMs] is inside a segment, else null. */
    fun skipTarget(segments: List<SponsorSegment>, positionMs: Long): Long? =
        segmentAt(segments, positionMs)?.endMs

    /**
     * The segment to auto-skip now: the one containing [positionMs] unless its
     * start is in [done]. The caller adds the start to [done] when it skips, so a
     * user who seeks back into it is left alone (once per segment per playback).
     */
    fun autoSkip(segments: List<SponsorSegment>, positionMs: Long, done: Set<Long>): SponsorSegment? =
        segmentAt(segments, positionMs)?.takeIf { it.startMs !in done }
}
