package com.abhinavxt.debforge.player.subtitles

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** What the sync-by-listening action is doing, for the panel. */
sealed interface AutoSyncState {
    data object Idle : AutoSyncState
    /** Reading the video's sound; [progress] is 0..1. */
    data class Listening(val progress: Float) : AutoSyncState
    /** Finding speech and matching it with the subtitle. */
    data object Matching : AutoSyncState
}

sealed interface AutoSyncOutcome {
    data class Synced(val timing: SubtitleTiming) : AutoSyncOutcome
    data object NoMatch : AutoSyncOutcome
    /** Every audio track needs a decoder this device lacks. */
    data object NoDecoder : AutoSyncOutcome
    data object Failed : AutoSyncOutcome
}

/** Why the sound could not be read. */
class ScanFailure(val noDecoder: Boolean, message: String, cause: Throwable? = null) : Exception(message, cause)

/** The steps of sync-by-listening: scan, detect speech, align. Cancellation propagates (nothing is reported). */
object AutoSync {
    suspend fun run(
        scan: suspend (onProgress: (Float) -> Unit) -> FloatArray,
        cues: List<CueSpan>,
        onState: (AutoSyncState) -> Unit,
        align: (BooleanArray, List<CueSpan>, () -> Unit) -> AlignResult =
            { speech, c, active -> SpeechAlign.align(speech, c, ensureActive = active) },
    ): AutoSyncOutcome {
        if (cues.isEmpty()) return AutoSyncOutcome.NoMatch
        onState(AutoSyncState.Listening(0f))
        val energy = try {
            scan { onState(AutoSyncState.Listening(it.coerceIn(0f, 1f))) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ScanFailure) {
            return if (e.noDecoder) AutoSyncOutcome.NoDecoder else AutoSyncOutcome.Failed
        } catch (e: Exception) {
            return AutoSyncOutcome.Failed
        }
        onState(AutoSyncState.Matching)
        return withContext(Dispatchers.Default) {
            val ctx = currentCoroutineContext()
            val active = { ctx.ensureActive() }
            val speech = SpeechDetector.detect(energy, ensureActive = active)
            when (val r = align(speech, cues, active)) {
                is AlignResult.Match -> AutoSyncOutcome.Synced(SubtitleTiming(r.a, r.bMs))
                is AlignResult.NoMatch -> AutoSyncOutcome.NoMatch
            }
        }
    }
}

/** Language tags match across 2- and 3-letter forms ("en", "eng"; "de", "ger", "deu"). Null, blank or "und" never match. */
fun sameLanguage(a: String?, b: String?): Boolean {
    val x = normaliseLanguage(a) ?: return false
    val y = normaliseLanguage(b) ?: return false
    return x == y
}

private val BIBLIOGRAPHIC = mapOf(
    "fre" to "fra", "ger" to "deu", "dut" to "nld", "cze" to "ces", "gre" to "ell", "chi" to "zho",
    "per" to "fas", "rum" to "ron", "slo" to "slk", "baq" to "eus", "ice" to "isl", "mac" to "mkd",
    "may" to "msa", "bur" to "mya", "tib" to "bod", "wel" to "cym", "arm" to "hye", "geo" to "kat",
    "alb" to "sqi", "mao" to "mri",
)

private fun normaliseLanguage(tag: String?): String? {
    val base = tag?.trim()?.lowercase()?.substringBefore('-')?.substringBefore('_')
        ?.takeIf { it.length in 2..3 && it != "und" } ?: return null
    if (base.length == 3) return BIBLIOGRAPHIC[base] ?: base
    return try {
        java.util.Locale(base).isO3Language.takeIf { it.isNotEmpty() } ?: base
    } catch (_: Exception) {
        base
    }
}
