package com.abhinavxt.debforge.player.tracks

import java.util.Locale

/**
 * Picks the audio and subtitle tracks when a file starts: what the user chose
 * last time for this show (or file), else their preferred languages, avoiding
 * commentary tracks and "signs & songs" subtitles. Pure: unit-tested in
 * TrackPickerTest.
 */
object TrackPicker {

    /** One track, as far as picking is concerned. */
    data class Candidate(
        val language: String?,
        val label: String?,
        val isDefault: Boolean = false,
        val isForced: Boolean = false,
        /** Flagged as commentary / audio description by the file. */
        val commentary: Boolean = false
    )

    /** What was chosen last time (per show, or per file for movies). */
    data class Remembered(
        val audioLanguage: String? = null,
        val audioLabel: String? = null,
        val textOff: Boolean = false,
        val textLanguage: String? = null,
        val textLabel: String? = null,
        val speed: Float? = null,
        /** Where the intro was skipped last time (shows only): offer "Skip intro" around there. */
        val introAtMs: Long? = null
    )

    sealed interface Choice {
        /** Leave it to the player (the file's default flags). */
        data object Leave : Choice
        data object Off : Choice
        data class Pick(val index: Int) : Choice
    }

    /** Subtitle setting: follow the file, always off, or a language. */
    const val AUTO = ""
    const val OFF = "off"

    private val COMMENTARY = Regex("""(?i)comment|description|\baudio\s*desc|\bdescriptive\b""")
    private val SIGNS = Regex("""(?i)\b(signs?|songs?|forced|karaoke)\b""")
    private val FULL = Regex("""(?i)\b(full|dialog(ue)?|complete)\b""")

    fun pickAudio(tracks: List<Candidate>, preferred: String, remembered: Remembered?): Choice {
        if (tracks.size < 2) return Choice.Leave
        remembered?.audioLanguage?.let { lang ->
            match(tracks, lang, remembered.audioLabel)?.let { return Choice.Pick(it) }
        }
        if (preferred.isBlank()) {
            // No preference: keep the file's default, unless that's commentary.
            val default = tracks.indexOfFirst { it.isDefault }.takeIf { it >= 0 } ?: 0
            if (!isCommentary(tracks[default])) return Choice.Leave
            return tracks.indices.firstOrNull { !isCommentary(tracks[it]) }?.let { Choice.Pick(it) } ?: Choice.Leave
        }
        val inLanguage = tracks.indices.filter { sameLanguage(tracks[it].language, preferred) && !isCommentary(tracks[it]) }
        val best = inLanguage.firstOrNull { tracks[it].isDefault } ?: inLanguage.firstOrNull()
        return best?.let { Choice.Pick(it) } ?: Choice.Leave
    }

    fun pickText(tracks: List<Candidate>, setting: String, remembered: Remembered?): Choice {
        remembered?.let { r ->
            if (r.textOff) return Choice.Off
            r.textLanguage?.let { lang -> match(tracks, lang, r.textLabel)?.let { return Choice.Pick(it) } }
        }
        return when (setting) {
            AUTO -> Choice.Leave
            OFF -> Choice.Off
            else -> {
                val inLanguage = tracks.indices.filter { sameLanguage(tracks[it].language, setting) }
                // Full dialogue beats "signs & songs"; those only if nothing else.
                val dialogue = inLanguage.filter { !isSigns(tracks[it]) }
                val best = dialogue.firstOrNull { FULL.containsMatchIn(tracks[it].label.orEmpty()) }
                    ?: dialogue.firstOrNull { tracks[it].isDefault }
                    ?: dialogue.firstOrNull()
                    ?: inLanguage.firstOrNull()
                best?.let { Choice.Pick(it) } ?: Choice.Leave
            }
        }
    }

    /** Same language and label, else the same language. */
    private fun match(tracks: List<Candidate>, language: String, label: String?): Int? {
        val same = tracks.indices.filter { sameLanguage(tracks[it].language, language) }
        return same.firstOrNull { label != null && tracks[it].label == label } ?: same.firstOrNull()
    }

    private fun isCommentary(c: Candidate) = c.commentary || COMMENTARY.containsMatchIn(c.label.orEmpty())
    private fun isSigns(c: Candidate) = c.isForced || SIGNS.containsMatchIn(c.label.orEmpty())

    /** "en", "en-US", "eng" are all English. */
    fun sameLanguage(a: String?, b: String?): Boolean {
        val x = normalize(a) ?: return false
        return x == normalize(b)
    }

    private val ISO3_TO_2: Map<String, String> by lazy {
        buildMap {
            Locale.getISOLanguages().forEach { two ->
                runCatching { Locale.forLanguageTag(two).isO3Language }.getOrNull()?.let { put(it, two) }
            }
            // Bibliographic codes some files use.
            putAll(mapOf("ger" to "de", "fre" to "fr", "chi" to "zh", "dut" to "nl", "cze" to "cs", "gre" to "el", "per" to "fa", "rum" to "ro", "slo" to "sk", "alb" to "sq", "arm" to "hy", "baq" to "eu", "bur" to "my", "geo" to "ka", "ice" to "is", "mac" to "mk", "mao" to "mi", "may" to "ms", "tib" to "bo", "wel" to "cy"))
        }
    }

    fun normalize(code: String?): String? {
        val primary = code?.trim()?.lowercase()?.substringBefore('-')?.substringBefore('_')?.takeIf { it.isNotEmpty() }
            ?: return null
        if (primary == "und") return null
        return if (primary.length == 3) ISO3_TO_2[primary] ?: primary else primary
    }
}

/** Languages offered in Settings for audio and subtitles, shown in the app's language. */
object PlayerLanguages {
    val CODES = listOf(
        "en", "hi", "ja", "ko", "zh", "es", "fr", "de", "it", "pt", "ru", "ar", "tr", "id",
        "bn", "ta", "te", "mr", "ml", "kn", "th", "vi", "pl", "nl"
    )

    fun displayName(code: String, inLocale: Locale = Locale.getDefault()): String =
        Locale.forLanguageTag(code).getDisplayLanguage(inLocale)
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(inLocale) else it.toString() }
            .ifEmpty { code }
}
