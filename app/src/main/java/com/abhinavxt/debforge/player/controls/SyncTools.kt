package com.abhinavxt.debforge.player.controls

import com.abhinavxt.debforge.player.subtitles.RawCue
import com.abhinavxt.debforge.player.subtitles.SubtitleTiming
import kotlin.math.abs

/** A subtitle line as listed in the sync tools: where the file has it and where it shows now. */
data class SyncLine(val rawStartMs: Long, val shownStartMs: Long, val shownEndMs: Long, val text: String, val current: Boolean)

/** The subtitle the tools work on: its timing key and the correction in force. */
data class SyncSubtitle(val key: String, val timing: SubtitleTiming)

/** Where the user is in the Sync tools panel. */
enum class SyncStep { MENU, LINE, DRIFT, FIRST_LINE, SECOND_LINE }

/** The first of the two matched lines: the file time of the line and the video time it was heard at. */
data class TwoLinePoint(val key: String, val rawMs: Long, val targetMs: Long)

/** Why a two-line fit was refused. */
enum class FitRejection { TOO_CLOSE, IMPLAUSIBLE }

/** A correction shown as "+2.3 s · 25→23.976": the offset and, when the rate is not 1, a frame-rate pair or a factor. */
data class CorrectionSummary(val offsetMs: Long, val fpsPair: Pair<Double, Double>?, val rate: Double?) {
    val isIdentity: Boolean get() = offsetMs == 0L && rate == null && fpsPair == null
}

/** The calculations behind the sync tools. Pure. */
object SyncMath {
    const val WINDOW_MS = 20_000L

    /** Lines that start within [windowMs] of [positionMs] as shown (correction and [nudgeMs] included). */
    fun lines(
        cues: List<RawCue>,
        timing: SubtitleTiming,
        nudgeMs: Long,
        positionMs: Long,
        windowMs: Long = WINDOW_MS
    ): List<SyncLine> {
        val shown = cues.map {
            SyncLine(it.startMs, timing.transform(it.startMs) + nudgeMs, timing.transform(it.endMs) + nudgeMs, plain(it.text), false)
        }.filter { it.shownStartMs in (positionMs - windowMs)..(positionMs + windowMs) && it.text.isNotBlank() }
            .sortedBy { it.shownStartMs }
        val current = shown.indexOfLast { it.shownStartMs <= positionMs && positionMs < it.shownEndMs }
        return shown.mapIndexed { i, l -> if (i == current) l.copy(current = true) else l }
    }

    /** Index of the line to scroll to: the current one, else the last one before [positionMs], else the first. */
    fun focusIndex(lines: List<SyncLine>, positionMs: Long): Int {
        val current = lines.indexOfFirst { it.current }
        if (current >= 0) return current
        val before = lines.indexOfLast { it.shownStartMs <= positionMs }
        return if (before >= 0) before else 0
    }

    /** Cue text without markup (subtitle tags, ASS overrides) and on one line. */
    fun plain(text: String): String =
        text.replace(Regex("\\{[^}]*\\}"), "").replace(Regex("<[^>]*>"), "")
            .replace("\\N", " ").replace("\\n", " ").replace(Regex("\\s+"), " ").trim()

    /** [timing] with the live nudge folded in: what the subtitle really shows. */
    fun effective(timing: SubtitleTiming, nudgeMs: Long): SubtitleTiming = timing.shiftedBy(nudgeMs)

    /** The user tapped [line] at [positionMs]: the new correction (the nudge is folded in; it restarts at zero). */
    fun tapLine(timing: SubtitleTiming, nudgeMs: Long, line: SyncLine, positionMs: Long): SubtitleTiming =
        effective(timing, nudgeMs).shiftedBy(SubtitleTiming.offsetFromTappedLine(line.shownStartMs, positionMs))

    /** Frame-rate preset [subFps]→[videoFps] anchored at [positionMs]. */
    fun preset(timing: SubtitleTiming, nudgeMs: Long, subFps: Double, videoFps: Double, positionMs: Long): SubtitleTiming =
        SubtitleTiming.fpsPreset(effective(timing, nudgeMs), subFps, videoFps, positionMs)

    /** "25→23.976". */
    fun presetLabel(subFps: Double, videoFps: Double): String = "${fpsText(subFps)}→${fpsText(videoFps)}"

    fun fpsText(fps: Double): String = if (fps == Math.rint(fps)) fps.toLong().toString() else fps.toString()

    fun firstPoint(key: String, line: SyncLine, positionMs: Long): TwoLinePoint =
        TwoLinePoint(key, line.rawStartMs, positionMs - SubtitleTiming.DEFAULT_REACTION_MS)

    /** Smallest distance between the two lines (file time) a fit is trusted at: the reaction error would show as a rate. */
    const val MIN_FIT_GAP_MS = 120_000L

    /** The fit of the two matched lines; the second is [line] heard at [positionMs]. */
    fun secondPoint(first: TwoLinePoint, line: SyncLine, positionMs: Long): Pair<SubtitleTiming?, FitRejection?> {
        if (abs(line.rawStartMs - first.rawMs) < MIN_FIT_GAP_MS) return null to FitRejection.TOO_CLOSE
        val fit = SubtitleTiming.fitTwoPoints(
            first.rawMs, first.targetMs, line.rawStartMs, positionMs - SubtitleTiming.DEFAULT_REACTION_MS
        ) ?: return null to FitRejection.IMPLAUSIBLE
        return fit to null
    }

    /** The correction in compact parts; the offset is the shift at [positionMs]. A rate matching a frame-rate preset is named by it. */
    fun summary(timing: SubtitleTiming, nudgeMs: Long = 0, positionMs: Long = 0): CorrectionSummary {
        val t = effective(timing, nudgeMs)
        val offset = t.transform(positionMs) - positionMs
        if (abs(t.a - 1.0) < 1e-6) return CorrectionSummary(t.bMs.let { Math.round(it) }, null, null)
        val pair = SubtitleTiming.FPS_PRESETS.firstOrNull { abs(SubtitleTiming.fpsRatio(it.first, it.second) - t.a) < 2e-4 }
        return CorrectionSummary(offset, pair, if (pair == null) t.a else null)
    }
}
