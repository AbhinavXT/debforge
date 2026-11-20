package com.abhinavxt.debforge.player.subtitles

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.Consumer
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser

/**
 * Applies a remembered [SubtitleTiming] while a side-loaded subtitle is parsed, so seeks and rate fixes
 * need no clock tricks. The subtitle's key is the `id` set with `SubtitleConfiguration.Builder.setId`
 * (Media3 passes it in the [Format] given to [create]). Raw cues go to [book]. Text spans (ASS styling) are
 * untouched; only the times change.
 */
@UnstableApi
class RetimingParserFactory(
    private val timingFor: (String) -> SubtitleTiming?,
    private val book: CueBook? = null,
    private val delegate: SubtitleParser.Factory = DefaultSubtitleParserFactory()
) : SubtitleParser.Factory {
    override fun supportsFormat(format: Format) = delegate.supportsFormat(format)

    override fun getCueReplacementBehavior(format: Format) = delegate.getCueReplacementBehavior(format)

    override fun create(format: Format): SubtitleParser {
        val inner = delegate.create(format)
        // Only ids made by timingKeyFor are ours; embedded tracks (HLS "1:en", DASH ids) are left alone.
        val key = format.id?.takeIf(::isTimingKey) ?: return inner
        book?.begin(key)
        return RetimingParser(inner, key, timingFor(key)?.takeUnless { it.isIdentity }, book)
    }
}

@UnstableApi
internal class RetimingParser(
    private val inner: SubtitleParser,
    private val key: String,
    private val timing: SubtitleTiming?,
    private val book: CueBook?
) : SubtitleParser {
    override fun parse(
        data: ByteArray,
        offset: Int,
        length: Int,
        outputOptions: SubtitleParser.OutputOptions,
        output: Consumer<CuesWithTiming>
    ) {
        val options = if (timing == null || outputOptions.startTimeUs == C.TIME_UNSET) outputOptions else {
            val raw = timing.inverse().transformUs(outputOptions.startTimeUs)
            if (outputOptions.outputAllCues) SubtitleParser.OutputOptions.cuesAfterThenRemainingCuesBefore(raw)
            else SubtitleParser.OutputOptions.onlyCuesAfter(raw)
        }
        inner.parse(data, offset, length, options) { cwt ->
            record(cwt)
            val moved = if (timing == null) cwt else retime(cwt, timing)
            if (moved != null) output.accept(moved)
        }
    }

    private fun record(cwt: CuesWithTiming) {
        val book = book ?: return
        if (cwt.startTimeUs == C.TIME_UNSET || cwt.durationUs == C.TIME_UNSET) return
        val text = cwt.cues.mapNotNull { it.text?.toString() }.joinToString("\n")
        if (text.isBlank()) return
        book.add(key, RawCue(cwt.startTimeUs / 1000, cwt.endTimeUs / 1000, text))
    }

    override fun reset() = inner.reset()

    override fun getCueReplacementBehavior() = inner.cueReplacementBehavior

    companion object {
        /** Null when the cue ends before time zero after the move. */
        fun retime(cwt: CuesWithTiming, timing: SubtitleTiming): CuesWithTiming? {
            if (cwt.startTimeUs == C.TIME_UNSET) return cwt
            var start = timing.transformUs(cwt.startTimeUs)
            if (cwt.durationUs == C.TIME_UNSET) return CuesWithTiming(cwt.cues, maxOf(start, 0), C.TIME_UNSET)
            var duration = maxOf(0L, Math.round(timing.a * cwt.durationUs))
            if (start < 0) {
                duration += start
                start = 0
                if (duration <= 0) return null
            }
            return CuesWithTiming(cwt.cues, start, duration)
        }
    }
}
