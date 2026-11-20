package com.abhinavxt.debforge.player.session

import com.abhinavxt.debforge.R
import com.abhinavxt.debforge.domain.IntroSkip
import com.abhinavxt.debforge.player.controls.SKIP_INTRO_SECONDS

/**
 * Skip intro / recap / credits (from the file's chapters, or where the user skipped this show's intro
 * before) and SponsorBlock segments of YouTube videos, for [VideoSession]'s tick.
 */
internal class VideoSkips(private val s: VideoSession) {

    /** This file's chapters (Matroska only); intro, recap and credits come from their names. */
    var chapters: List<com.abhinavxt.debforge.domain.MkvChapters.Chapter> = emptyList()
    private var segments: List<IntroSkip.Segment> = emptyList()
    private var segmentsFor = -1L
    /** Where the intro was skipped in an earlier episode of this show. */
    var learntIntroAt: Long? = null
    /** Start of each segment already skipped or passed on in this file ([LEARNT_SKIP] for the learnt one). */
    private val doneSkips = HashSet<Long>()
    var autoSkipIntro = false
    /** SponsorBlock segments of the playing YouTube video (merged, clamped), skipped by [update]. */
    private var sponsorSegments: List<com.abhinavxt.debforge.domain.SponsorSegment> = emptyList()
    var sponsorRaw: List<com.abhinavxt.debforge.domain.SponsorSegment> = emptyList()
    private var sponsorFor = -1L
    /** Start of each sponsor segment already skipped in this file: skipped once, not again after a seek back. */
    private val sponsorDone = HashSet<Long>()

    /** A new file. */
    fun reset() {
        chapters = emptyList()
        segmentsFor = -1L
        learntIntroAt = null
        doneSkips.clear()
        sponsorDone.clear()
        sponsorRaw = emptyList()
        sponsorSegments = emptyList()
        sponsorFor = -1L
    }

    /**
     * Once a second: offer to skip the intro / recap / credits playing now
     * (from the file's chapters, or where the user skipped this show's intro
     * before), or skip it by itself when Settings say so.
     */
    fun update() {
        val p = s.player
        val duration = s.durationOrNull()
        if (p == null || duration == null || s.episodes.switching || s.episodes.cardShownAt != null) {
            hideSkip()
            return
        }
        if (segmentsFor != duration && chapters.isNotEmpty()) {
            segments = IntroSkip.fromChapters(chapters, duration)
            segmentsFor = duration
        }
        val position = p.currentPosition
        if (sponsorRaw.isNotEmpty()) {
            if (sponsorFor != duration) {
                sponsorSegments = com.abhinavxt.debforge.domain.SponsorSkip.normalise(sponsorRaw, duration)
                sponsorFor = duration
            }
            val sponsor = com.abhinavxt.debforge.domain.SponsorSkip.autoSkip(sponsorSegments, position, sponsorDone)
            if (sponsor != null) {
                sponsorDone += sponsor.startMs
                hideSkip()
                skipSegment(IntroSkip.Segment(IntroSkip.Kind.SPONSOR, sponsor.startMs, sponsor.endMs))
                s.showMessage(s.string(R.string.player_skipped_sponsor))
                return
            }
        }
        // Sponsor skipping above also runs in picture-in-picture; the skip button and intro skips do not.
        if (s.host?.inPictureInPicture == true) {
            hideSkip()
            return
        }
        val segment = IntroSkip.at(segments, position)?.takeIf { it.startMs !in doneSkips }
        if (segment != null) {
            if (autoSkipIntro && (segment.kind == IntroSkip.Kind.INTRO || segment.kind == IntroSkip.Kind.RECAP)) {
                doneSkips += segment.startMs
                hideSkip()
                skipSegment(segment)
                s.showMessage(s.string(if (segment.kind == IntroSkip.Kind.INTRO) R.string.player_skipped_intro else R.string.player_skipped_recap))
                return
            }
            showSkip(
                when (segment.kind) {
                    IntroSkip.Kind.INTRO -> R.string.player_skip_intro
                    IntroSkip.Kind.RECAP -> R.string.player_skip_recap
                    IntroSkip.Kind.CREDITS -> R.string.player_skip_credits
                    IntroSkip.Kind.PREVIEW -> R.string.player_skip_preview
                    IntroSkip.Kind.SPONSOR -> R.string.player_skip_sponsor
                    IntroSkip.Kind.SELF_PROMO -> R.string.player_skip_self_promo
                    IntroSkip.Kind.INTERACTION -> R.string.player_skip_interaction
                }
            ) {
                doneSkips += segment.startMs
                skipSegment(segment)
            }
            return
        }
        // No intro chapter: offer it where this show's intro was skipped before.
        val learnt = learntIntroAt
        if (learnt != null && LEARNT_SKIP !in doneSkips && segments.none { it.kind == IntroSkip.Kind.INTRO } &&
            IntroSkip.inLearntWindow(learnt, position)
        ) {
            showSkip(R.string.player_skip_intro) {
                val at = s.player?.currentPosition ?: return@showSkip
                rememberIntro(at)
                s.player?.seekTo((at + SKIP_INTRO_SECONDS * 1000L).coerceAtMost(duration))
            }
            return
        }
        hideSkip()
    }

    /** Jumps past [segment]; credits running to the end go straight to the next episode. */
    private fun skipSegment(segment: IntroSkip.Segment) {
        val duration = s.durationOrNull() ?: return
        if (com.abhinavxt.debforge.domain.SponsorSkip.endsVideo(segment.endMs, duration, END_SLACK_MS, s.hasNext)) s.playNext()
        else s.player?.seekTo(segment.endMs.coerceAtMost(duration))
    }

    /** The intro of this show is around [positionMs]: offer the skip there in the next episodes. */
    fun rememberIntro(positionMs: Long) {
        doneSkips += LEARNT_SKIP
        if (s.identity?.showKey == null || !IntroSkip.isLikelyIntro(positionMs, s.durationOrNull() ?: 0)) return
        learntIntroAt = positionMs
        s.remember { it.copy(introAtMs = positionMs) }
    }

    private fun showSkip(textRes: Int, onSkip: () -> Unit) {
        s.host?.showSkip(s.string(textRes), onSkip)
    }

    private fun hideSkip() {
        s.host?.hideSkip()
    }

    private companion object {
        /** [doneSkips] entry for the learnt intro (chapter segments use their start). */
        const val LEARNT_SKIP = -1L
        /** Credits ending this close to the end of the file count as "to the end". */
        const val END_SLACK_MS = 3_000L
    }
}
