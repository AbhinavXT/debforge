package com.abhinavxt.debforge.domain

import java.util.Locale

/** A subtitle file ready for the player: a playable link plus how to show it. */
data class SubtitleLink(
    val url: String,
    val mimeType: String,
    /** ISO 639-1 code ("en"), or null when unknown. */
    val language: String?,
    val label: String,
    val forced: Boolean,
    /** Where to get a fresh link when [url] expires (null for plain links). */
    val provider: ProviderId? = null,
    val sourceRef: String? = null
)

/**
 * Finds the subtitle files that belong to a video in the same torrent, and
 * reads their language from the name. Pure: unit-tested in SubtitlesTest.
 *
 * Streaming a URL, external players only see the video; these files sit next
 * to it in the torrent (".en.srt", "Subs/<episode>/2_English.srt"), and
 * DebForge knows the whole file list.
 */
object Subtitles {

    /** Extension → MIME type Media3 understands for side-loaded subtitles. */
    val MIME_TYPES = mapOf(
        "srt" to "application/x-subrip",
        "ass" to "text/x-ssa",
        "ssa" to "text/x-ssa",
        "vtt" to "text/vtt"
    )

    /** At most this many are attached (season packs can hold dozens of languages). */
    const val MAX_TRACKS = 16

    data class Found(
        val item: DownloadItem,
        val mimeType: String,
        val language: String?,
        val forced: Boolean,
        val sdh: Boolean
    )

    fun isSubtitle(item: DownloadItem): Boolean = ExtraFiles.extensionOf(item.filename) in MIME_TYPES

    /**
     * Subtitles for [video] among [siblings] (the other files of its
     * torrent, same service). A torrent with one video: all of them. A pack
     * with several: those whose path names the video, or whose
     * season/episode matches.
     */
    fun forVideo(video: DownloadItem, siblings: List<DownloadItem>): List<Found> {
        val sameTorrent = siblings.filter {
            it.id != video.id && it.provider == video.provider &&
                video.parentRef != null && it.parentRef == video.parentRef
        }
        val subs = sameTorrent.filter(::isSubtitle)
        if (subs.isEmpty()) return emptyList()
        // Real videos only: a sample clip doesn't make it a multi-video pack.
        val videos = ExtraFiles.hideInTorrent(sameTorrent + video).filter(ExtraFiles::isVideo)
        val picked = if (videos.size <= 1) subs else {
            val base = video.filename.substringBeforeLast('.').lowercase()
            val episode = episodeOf(video.path ?: video.filename)
            subs.filter { s ->
                val where = (s.path ?: s.filename).lowercase()
                (base.length >= 4 && base in where) || (episode != null && episodeOf(where) == episode)
            }
        }
        return picked.map { s ->
            val (lang, forced, sdh) = describe(s.filename)
            Found(s, MIME_TYPES.getValue(ExtraFiles.extensionOf(s.filename)), lang, forced, sdh)
        }
            .sortedWith(compareBy<Found>({ it.language == null }, { it.language }, { it.forced }, { it.sdh }))
            .take(MAX_TRACKS)
    }

    private val EPISODE = Regex("""s(\d{1,2})[ ._-]?e(\d{1,3})""", RegexOption.IGNORE_CASE)

    /** "S01E02" from anywhere in a name or path, or null. */
    fun episodeOf(name: String): String? = EPISODE.find(name)?.let { m ->
        "S%02dE%02d".format(Locale.ROOT, m.groupValues[1].toInt(), m.groupValues[2].toInt())
    }

    /** Language names and codes as they appear in subtitle file names. */
    private val NAMES = mapOf(
        "english" to "en", "spanish" to "es", "espanol" to "es", "español" to "es", "latino" to "es",
        "french" to "fr", "francais" to "fr", "français" to "fr", "german" to "de", "deutsch" to "de",
        "italian" to "it", "italiano" to "it", "portuguese" to "pt", "brazilian" to "pt", "russian" to "ru",
        "hindi" to "hi", "arabic" to "ar", "japanese" to "ja", "korean" to "ko", "chinese" to "zh",
        "dutch" to "nl", "swedish" to "sv", "polish" to "pl", "turkish" to "tr", "indonesian" to "id",
        "bengali" to "bn", "tamil" to "ta", "telugu" to "te", "marathi" to "mr", "malayalam" to "ml",
        "danish" to "da", "norwegian" to "no", "finnish" to "fi", "greek" to "el", "hebrew" to "he",
        "czech" to "cs", "hungarian" to "hu", "romanian" to "ro", "ukrainian" to "uk", "vietnamese" to "vi",
        "thai" to "th", "malay" to "ms", "persian" to "fa", "croatian" to "hr", "serbian" to "sr"
    )
    private val CODES3 = mapOf(
        "eng" to "en", "spa" to "es", "fre" to "fr", "fra" to "fr", "ger" to "de", "deu" to "de",
        "ita" to "it", "por" to "pt", "rus" to "ru", "hin" to "hi", "ara" to "ar", "jpn" to "ja",
        "kor" to "ko", "chi" to "zh", "zho" to "zh", "dut" to "nl", "nld" to "nl", "swe" to "sv",
        "pol" to "pl", "tur" to "tr", "ind" to "id", "ben" to "bn", "tam" to "ta", "tel" to "te",
        "mar" to "mr", "mal" to "ml", "dan" to "da", "nor" to "no", "fin" to "fi", "gre" to "el",
        "ell" to "el", "heb" to "he", "cze" to "cs", "ces" to "cs", "hun" to "hu", "rum" to "ro",
        "ron" to "ro", "ukr" to "uk", "vie" to "vi", "tha" to "th", "may" to "ms", "msa" to "ms",
        "per" to "fa", "fas" to "fa"
    )
    private val CODES2 = (NAMES.values + CODES3.values).toSet()

    /**
     * (language, forced, SDH) from a file name. Words anywhere near the end
     * count ("2_English.srt", "Movie.English.SDH.srt"); short codes only
     * right before the extension ("Movie.en.srt", "Movie.eng.forced.srt"),
     * so a title like "It.2017.srt" isn't read as Italian.
     */
    fun describe(filename: String): Triple<String?, Boolean, Boolean> {
        val stem = filename.substringBeforeLast('.').lowercase()
        val tokens = stem.split('.', '_', '-', ' ', '(', ')', '[', ']').filter { it.isNotEmpty() }
        val forced = "forced" in tokens
        // "hi" is left to Hindi: as a hearing-impaired tag it's ambiguous.
        val sdh = "sdh" in tokens || "cc" in tokens
        val tail = tokens.filterNot { it == "forced" || it == "sdh" || it == "cc" }
        val lastFew = tail.takeLast(3)
        val language = lastFew.asReversed().firstNotNullOfOrNull { NAMES[it] }
            ?: tail.takeLast(2).asReversed().firstNotNullOfOrNull { CODES3[it] }
            ?: tail.lastOrNull()?.takeIf { it in CODES2 && tail.size >= 2 }
            ?: tail.lastOrNull()?.let { t ->
                // "pt-br" / "pt_br" end up as "pt","br": Brazilian Portuguese.
                if (t == "br" && tail.getOrNull(tail.size - 2) == "pt") "pt" else null
            }
        return Triple(language, forced, sdh)
    }

    /** "English", "English (SDH)", "French · Forced", or the file name when unknown. */
    fun label(found: Found, display: Locale, forcedWord: String): String {
        val base = found.language?.let { code ->
            Locale.forLanguageTag(code).getDisplayLanguage(display).replaceFirstChar { it.titlecase(display) }.ifEmpty { null }
        } ?: found.item.filename.substringBeforeLast('.')
        return buildString {
            append(base)
            if (found.sdh) append(" (SDH)")
            if (found.forced) append(" · ").append(forcedWord)
        }
    }
}
