package com.abhinavxt.debforge.domain

import com.abhinavxt.debforge.data.subtitles.OnlineSubtitle

/** When a starting video should look for online subtitles, and the chip's state machine. Pure; unit-tested in AutoSubsTest. */
object AutoSubsRules {

    /**
     * Whether to search OpenSubtitles for a video that just started. Needs the setting on, an API key, a phone or
     * tablet, and no text track in the user's language. A video whose subtitles the user turned off last time, or that
     * has a full track with no language tag (it may well be theirs), is left alone.
     */
    fun shouldSearch(
        mode: OnlineSubsWhenNone,
        hasKey: Boolean,
        isTv: Boolean,
        userTurnedSubtitlesOff: Boolean,
        textTracks: List<TextTrackInfo>,
        languages: List<String>
    ): Boolean {
        if (mode == OnlineSubsWhenNone.OFF || !hasKey || isTv || userTurnedSubtitlesOff) return false
        if (hasUntaggedFullTrack(textTracks)) return false
        return SubtitleAutoPick.needsSubtitles(textTracks, languages)
    }

    /** A full (not forced) text track with no language tag may well be the user's, so it counts as covering any language. Shared with the download-time fetch. */
    fun hasUntaggedFullTrack(textTracks: List<TextTrackInfo>): Boolean =
        textTracks.any { !it.forced && it.language.isNullOrBlank() }
}

/** What the player's small subtitles chip shows. */
sealed interface AutoSubsChip {
    data object Hidden : AutoSubsChip
    /** "English subtitles found · Add". */
    data class Offer(val sub: OnlineSubtitle, val language: String) : AutoSubsChip
    /** Downloading; [tapped]: the user asked for it (so a failure is worth telling them about). */
    data class Adding(val sub: OnlineSubtitle, val language: String, val tapped: Boolean) : AutoSubsChip
    /** "Added English subtitles · Undo". */
    data class Added(val language: String) : AutoSubsChip
}

sealed interface AutoSubsEvent {
    data class Searched(val result: AutoPickResult, val language: String) : AutoSubsEvent
    /** The user tapped Add. */
    data object Add : AutoSubsEvent
    /** The chip's time ran out. */
    data object Expired : AutoSubsEvent
    /** The subtitle is attached and selected. */
    data object Attached : AutoSubsEvent
    data class Failed(val quota: Boolean) : AutoSubsEvent
    data object Undo : AutoSubsEvent
    data object Reset : AutoSubsEvent
}

/** What the player must do for a state change. */
sealed interface AutoSubsEffect {
    data class Download(val sub: OnlineSubtitle, val tapped: Boolean) : AutoSubsEffect
    /** Only when the user tapped: the existing quota or download-failed message. */
    data class Tell(val quota: Boolean) : AutoSubsEffect
    data object RemoveAdded : AutoSubsEffect
}

data class AutoSubsStep(val chip: AutoSubsChip, val effect: AutoSubsEffect? = null)

object AutoSubsMachine {
    const val OFFER_MS = 8_000L
    const val ADDED_MS = 7_000L

    fun reduce(chip: AutoSubsChip, event: AutoSubsEvent): AutoSubsStep = when (event) {
        is AutoSubsEvent.Searched -> if (chip !is AutoSubsChip.Hidden) AutoSubsStep(chip) else when (val r = event.result) {
            is AutoPickResult.Add -> AutoSubsStep(AutoSubsChip.Adding(r.subtitle, event.language, tapped = false), AutoSubsEffect.Download(r.subtitle, false))
            is AutoPickResult.Offer -> AutoSubsStep(AutoSubsChip.Offer(r.subtitle, event.language))
            AutoPickResult.Nothing -> AutoSubsStep(chip)
        }
        AutoSubsEvent.Add -> if (chip is AutoSubsChip.Offer) {
            AutoSubsStep(AutoSubsChip.Adding(chip.sub, chip.language, tapped = true), AutoSubsEffect.Download(chip.sub, true))
        } else AutoSubsStep(chip)
        AutoSubsEvent.Expired -> if (chip is AutoSubsChip.Offer || chip is AutoSubsChip.Added) AutoSubsStep(AutoSubsChip.Hidden) else AutoSubsStep(chip)
        AutoSubsEvent.Attached -> if (chip is AutoSubsChip.Adding) AutoSubsStep(AutoSubsChip.Added(chip.language)) else AutoSubsStep(chip)
        is AutoSubsEvent.Failed -> if (chip is AutoSubsChip.Adding) {
            AutoSubsStep(AutoSubsChip.Hidden, if (chip.tapped) AutoSubsEffect.Tell(event.quota) else null)
        } else AutoSubsStep(chip)
        AutoSubsEvent.Undo -> if (chip is AutoSubsChip.Added) AutoSubsStep(AutoSubsChip.Hidden, AutoSubsEffect.RemoveAdded) else AutoSubsStep(chip)
        AutoSubsEvent.Reset -> AutoSubsStep(AutoSubsChip.Hidden)
    }
}
