package com.abhinavxt.debforge.player.youtube

import com.abhinavxt.debforge.domain.YouTubeSubtitle

/** The subtitle a viewer had on: its language code and label, as the player lists the track. */
data class SubtitleChoice(val language: String?, val label: String)

private fun baseLanguage(code: String?): String? = code?.substringBefore('-')?.substringBefore('_')?.lowercase()?.ifBlank { null }

/**
 * The track of [available] that is [chosen]: the same language and label, else the same language (a manual track
 * before an auto-generated one). Null when nothing was chosen or the language is not offered.
 */
fun pickSubtitle(available: List<YouTubeSubtitle>, chosen: SubtitleChoice?): YouTubeSubtitle? {
    val lang = baseLanguage(chosen?.language) ?: return null
    val sameLanguage = available.filter { baseLanguage(it.languageCode) == lang }
    return sameLanguage.firstOrNull { it.label == chosen?.label && it.languageCode.equals(chosen.language, ignoreCase = true) }
        ?: sameLanguage.firstOrNull { it.label == chosen?.label }
        ?: sameLanguage.sortedBy { it.auto }.firstOrNull()
}

/** The subtitles of [available] that match [attached] ones (a refresh of the links), each at most once, in the old order. */
fun reattachSubtitles(available: List<YouTubeSubtitle>, attached: List<SubtitleChoice>): List<YouTubeSubtitle> =
    attached.mapNotNull { pickSubtitle(available, it) }.distinctBy { it.url }
