package com.abhinavxt.debforge.domain

/**
 * What the player can offer to skip: parts of the file whose chapter is named
 * like an intro, recap, credits or preview, and, for shows without such
 * chapters, the point where the user skipped the intro last time. Pure
 * Kotlin: unit-tested in IntroSkipTest.
 */
object IntroSkip {

    enum class Kind { INTRO, RECAP, CREDITS, PREVIEW }

    data class Segment(val kind: Kind, val startMs: Long, val endMs: Long)

    private val OPT = setOf(RegexOption.IGNORE_CASE)
    private val INTRO = Regex("""^(?:intro(?:duction)?|opening(?:\s+(?:credits|song|theme))?|op(?:\s*\d+)?|theme(?:\s+song)?|title\s+sequence|main\s+titles?|ノンクレジットop|オープニング)$""", OPT)
    private val RECAP = Regex("""^(?:recap|previously(?:\s+on\b.*)?|summary|前回のあらすじ|あらすじ)$""", OPT)
    private val CREDITS = Regex("""^(?:credits|end\s+credits|closing\s+credits|ending(?:\s+(?:credits|song|theme))?|ed(?:\s*\d+)?|outro|エンディング)$""", OPT)
    private val PREVIEW = Regex("""^(?:preview|next\s+episode(?:\s+preview)?|next\s+time|予告)$""", OPT)

    /** Shorter or longer than this, a matching chapter is probably mislabelled. */
    private const val MIN_MS = 5_000L
    private const val MAX_MS = 10 * 60_000L

    fun kindOf(title: String?): Kind? {
        val t = title?.trim()?.trim('-', ':', '.', ' ')?.takeIf { it.isNotEmpty() } ?: return null
        return when {
            INTRO.matches(t) -> Kind.INTRO
            RECAP.matches(t) -> Kind.RECAP
            CREDITS.matches(t) -> Kind.CREDITS
            PREVIEW.matches(t) -> Kind.PREVIEW
            else -> null
        }
    }

    /** Skippable parts from [chapters]; [durationMs] ends the last chapter. */
    fun fromChapters(chapters: List<MkvChapters.Chapter>, durationMs: Long): List<Segment> {
        val sorted = chapters.sortedBy { it.startMs }
        val out = ArrayList<Segment>()
        sorted.forEachIndexed { i, c ->
            val kind = kindOf(c.title) ?: return@forEachIndexed
            val end = c.endMs?.takeIf { it > c.startMs } ?: sorted.getOrNull(i + 1)?.startMs ?: durationMs
            if (end - c.startMs !in MIN_MS..MAX_MS) return@forEachIndexed
            val last = out.lastOrNull()
            // "OP 1" / "OP 2" back to back: one skip.
            if (last != null && last.kind == kind && c.startMs - last.endMs <= 1_000) {
                out[out.lastIndex] = last.copy(endMs = end)
            } else {
                out += Segment(kind, c.startMs, end)
            }
        }
        return out
    }

    /** The segment playing at [positionMs], until a second before its end (so the button doesn't flash at the edge). */
    fun at(segments: List<Segment>, positionMs: Long): Segment? =
        segments.firstOrNull { positionMs >= it.startMs && positionMs < it.endMs - 1_000 }

    /**
     * Learnt from the user: they skipped the intro at [introAtMs] in another
     * episode. Intros move a little (cold opens differ), so the button shows
     * from a bit before until a while after.
     */
    fun inLearntWindow(introAtMs: Long, positionMs: Long): Boolean =
        positionMs >= (introAtMs - LEARNT_BEFORE_MS).coerceAtLeast(0) && positionMs < introAtMs + LEARNT_AFTER_MS

    /** Only an early skip is an intro (later it's skipping a scene). */
    fun isLikelyIntro(positionMs: Long, durationMs: Long): Boolean =
        positionMs < MAX_INTRO_AT_MS && (durationMs <= 0 || positionMs < durationMs / 3)

    private const val LEARNT_BEFORE_MS = 20_000L
    private const val LEARNT_AFTER_MS = 40_000L
    private const val MAX_INTRO_AT_MS = 15 * 60_000L
}
