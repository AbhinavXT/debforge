package com.abhinavxt.debforge.player

import java.util.Locale

/**
 * Which OpenSubtitles languages to search in, from Settings' subtitle
 * language ("" = the file's default, "off", or a code) and the phone's
 * language. OpenSubtitles splits Portuguese and Chinese by region. Pure:
 * unit-tested in OnlineSubtitleLanguagesTest.
 */
object OnlineSubtitleLanguages {

    fun forSetting(setting: String, device: Locale): List<String> {
        val code = setting.takeIf { it.isNotBlank() && it != "off" } ?: device.language
        return when (val lang = normalize(code)) {
            "" -> listOf("en")
            "pt" -> listOf("pt-br", "pt-pt")
            "zh" -> listOf("zh-cn", "zh-tw")
            else -> listOf(lang)
        }
    }

    /** Java's old codes ("in", "iw") to ISO 639-1, region dropped. */
    private fun normalize(code: String): String = when (val c = code.lowercase(Locale.ROOT).substringBefore('-').substringBefore('_')) {
        "in" -> "id"
        "iw" -> "he"
        "ji" -> "yi"
        else -> c
    }
}
