package com.abhinavxt.debforge.domain

import java.util.Locale

enum class MediaKind { SHOW, MOVIE, OTHER }

/**
 * What we could read out of a scene/P2P-style release filename. Pure data;
 * produced by [ReleaseNameParser]. Anything not recognised stays null.
 */
data class ReleaseInfo(
    val kind: MediaKind,
    /** Human title ("The Bear"). For OTHER, a cleaned-up filename. */
    val title: String,
    val year: Int?,
    val season: Int?,
    val episode: Int?,
    /** "2160p", "1080p", ... */
    val resolution: String?,
    /** "WEB-DL", "BluRay", "REMUX", ... */
    val source: String?,
    /** "HDR", "DV" (Dolby Vision) — first match only. */
    val hdr: String?,
    val extension: String?
) {
    /** Playable in a video player (drives the Play button). */
    val isVideo: Boolean get() = extension != null && extension in VIDEO_EXTENSIONS

    /** Stable key for grouping the same show/movie across files and pages. */
    val groupKey: String
        get() = when (kind) {
            MediaKind.SHOW -> "show:" + normalize(title)
            MediaKind.MOVIE -> "movie:" + normalize(title) + ":" + (year ?: "")
            MediaKind.OTHER -> "other:" + normalize(title)
        }

    /** "S02E05" / "E12" / null. */
    val episodeLabel: String?
        get() = when {
            kind != MediaKind.SHOW || episode == null -> null
            season != null -> "S%02dE%02d".format(Locale.ROOT, season, episode)
            else -> "E%02d".format(Locale.ROOT, episode)
        }

    /** Primary line: "The Bear · S02E05", "Oppenheimer (2023)". */
    val displayTitle: String
        get() = when (kind) {
            MediaKind.SHOW -> listOfNotNull(title, episodeLabel).joinToString(" · ")
            MediaKind.MOVIE -> if (year != null) "$title ($year)" else title
            MediaKind.OTHER -> title
        }

    /** Quality chips joined: "2160p · WEB-DL · HDR". Empty if nothing known. */
    val qualityLabel: String
        get() = listOfNotNull(resolution, source, hdr).joinToString(" · ")

    companion object {
        val VIDEO_EXTENSIONS = setOf(
            "mkv", "mp4", "avi", "m4v", "mov", "wmv", "ts", "m2ts", "webm", "flv", "mpg", "mpeg"
        )

        fun normalize(s: String): String =
            s.lowercase(Locale.ROOT)
                .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
                .trim()
    }
}

/**
 * Heuristic release-name parser. Release names are chaotic, so it prefers
 * leaving something unknown over guessing wrong: a file only becomes a SHOW
 * with a clear episode marker, and a MOVIE with a plausible year followed by
 * release/quality tokens.
 */
object ReleaseNameParser {

    private val VIDEO_EXT = ReleaseInfo.VIDEO_EXTENSIONS

    private val LEADING_TAGS = Regex("""^(?:\s*[\[(][^\])]*[\])]\s*)+""")

    // Episode markers, most specific first. Each captures the title before it.
    private val SXXEXX = Regex("""^(?<t>.*?)[\s._\-(\[]*\b[Ss](?<s>\d{1,2})[\s._-]?[Ee](?<e>\d{1,3})""")
    private val SEASON_EP_WORDS = Regex("""^(?<t>.*?)[\s._-]+Season[\s._-]*(?<s>\d{1,2})[\s._-]+Episode[\s._-]*(?<e>\d{1,3})""", RegexOption.IGNORE_CASE)
    private val N_X_NN = Regex("""^(?<t>.*?)[\s._-]+(?<s>\d{1,2})[xX×](?<e>\d{2,3})\b""")
    private val EP_WORD = Regex("""^(?<t>.*?)[\s._-]+(?:Episode|Ep\.?)[\s._-]*(?<e>\d{1,3})\b""", RegexOption.IGNORE_CASE)
    private val ANIME_DASH = Regex("""^(?<t>.+?)\s+-\s+(?<e>\d{1,3})(?:v\d)?(?=[\s._\[(]|$)""")

    private val YEAR = Regex("""(?<![\d])(19\d{2}|20\d{2})(?![\d])""")

    private val RESOLUTION = Regex("""\b(4320p|2160p|1440p|1080p|1080i|720p|576p|480p|4k|uhd)\b""", RegexOption.IGNORE_CASE)
    private val SOURCES = listOf(
        "REMUX" to Regex("""\bremux\b""", RegexOption.IGNORE_CASE),
        "BluRay" to Regex("""\b(blu-?ray|bdrip|brrip|bdremux)\b""", RegexOption.IGNORE_CASE),
        "WEB-DL" to Regex("""\bweb[-.]?dl\b""", RegexOption.IGNORE_CASE),
        "WEBRip" to Regex("""\bweb-?rip\b""", RegexOption.IGNORE_CASE),
        "WEB" to Regex("""\bweb\b""", RegexOption.IGNORE_CASE),
        "HDTV" to Regex("""\bhdtv\b""", RegexOption.IGNORE_CASE),
        "DVDRip" to Regex("""\bdvd-?rip\b""", RegexOption.IGNORE_CASE)
    )
    private val HDR = listOf(
        "DV" to Regex("""\b(dv|dovi|dolby[\s._-]?vision)\b""", RegexOption.IGNORE_CASE),
        "HDR" to Regex("""\bhdr(10\+?|10plus)?\b""", RegexOption.IGNORE_CASE)
    )

    /**
     * Tokens that mark the end of a title. The title is everything before
     * the first of these (or the year / episode marker).
     */
    private val TECH_START = Regex(
        """[\s._\-(\[]+(?:4320p|2160p|1440p|1080p|1080i|720p|576p|480p|4k|uhd|web[-.]?dl|web-?rip|web|blu-?ray|bdrip|brrip|remux|hdtv|dvd-?rip|hdrip|x26[45]|h\.?26[45]|hevc|av1|xvid|10bit|hdr|dv|proper|repack|extended|unrated|imax|multi|dual|complete|season|s\d{1,2}\b)""",
        RegexOption.IGNORE_CASE
    )

    fun parse(filename: String): ReleaseInfo {
        val (base, ext) = splitExtension(filename)
        val name = base.replace(LEADING_TAGS, "").trim().ifEmpty { base }

        val resolution = RESOLUTION.find(name)?.value?.let(::normalizeResolution)
        val source = SOURCES.firstOrNull { (_, r) -> r.containsMatchIn(name) }?.first
        val hdr = HDR.firstOrNull { (_, r) -> r.containsMatchIn(name) }?.first

        // --- shows --------------------------------------------------------
        for ((regex, hasSeason) in listOf(
            SXXEXX to true, SEASON_EP_WORDS to true, N_X_NN to true, EP_WORD to false, ANIME_DASH to false
        )) {
            val m = regex.find(name) ?: continue
            val rawTitle = m.groups["t"]?.value.orEmpty()
            val episode = m.groups["e"]?.value?.toIntOrNull() ?: continue
            val season = if (hasSeason) m.groups["s"]?.value?.toIntOrNull() else null
            // A show title may carry a year ("Doctor Who 2005 S01E01").
            val yearInTitle = YEAR.findAll(rawTitle).lastOrNull()
            val title = cleanTitle(
                if (yearInTitle != null && yearInTitle.range.first > 0) rawTitle.substring(0, yearInTitle.range.first) else rawTitle
            )
            if (title.isBlank()) continue
            return ReleaseInfo(
                kind = MediaKind.SHOW,
                title = title,
                year = yearInTitle?.value?.toIntOrNull(),
                season = season,
                episode = episode,
                resolution = resolution,
                source = source,
                hdr = hdr,
                extension = ext
            )
        }

        // --- movies -------------------------------------------------------
        // Year must not be the very first token (titles like "2012" exist but
        // "1917.2019.1080p" should give title 1917, year 2019): take the LAST
        // year that has some title text before it.
        val isVideo = ext == null || ext in VIDEO_EXT
        val year = YEAR.findAll(name).lastOrNull { it.range.first > 0 }
        if (year != null && isVideo) {
            val title = cleanTitle(name.substring(0, year.range.first))
            if (title.isNotBlank()) {
                return ReleaseInfo(
                    kind = MediaKind.MOVIE,
                    title = title,
                    year = year.value.toInt(),
                    season = null,
                    episode = null,
                    resolution = resolution,
                    source = source,
                    hdr = hdr,
                    extension = ext
                )
            }
        }

        // A yearless video with clear release tags is still very likely a movie.
        val techStart = TECH_START.find(name)
        if (isVideo && ext != null && techStart != null && techStart.range.first > 0 && resolution != null) {
            val title = cleanTitle(name.substring(0, techStart.range.first))
            if (title.isNotBlank()) {
                return ReleaseInfo(MediaKind.MOVIE, title, null, null, null, resolution, source, hdr, ext)
            }
        }

        // --- everything else ---------------------------------------------
        // Non-video files (ISOs, archives, docs) keep their exact name —
        // "prettifying" ubuntu-24.04-desktop-amd64 only makes it harder to find.
        if (!isVideo) {
            return ReleaseInfo(MediaKind.OTHER, base, null, null, null, resolution, source, hdr, ext)
        }
        val cleaned = cleanTitle(
            if (techStart != null && techStart.range.first > 0) name.substring(0, techStart.range.first) else name
        ).ifBlank { base }
        return ReleaseInfo(MediaKind.OTHER, cleaned, null, null, null, resolution, source, hdr, ext)
    }

    private fun splitExtension(filename: String): Pair<String, String?> {
        val dot = filename.lastIndexOf('.')
        if (dot <= 0) return filename to null
        val ext = filename.substring(dot + 1)
        return if (ext.length in 1..5 && ext.all { it.isLetterOrDigit() }) {
            filename.substring(0, dot) to ext.lowercase(Locale.ROOT)
        } else {
            filename to null
        }
    }

    /** Separators -> spaces, strip trailing tech tokens / brackets, Title Case. */
    private fun cleanTitle(raw: String): String {
        var t = raw
        TECH_START.find(t)?.let { if (it.range.first > 0) t = t.substring(0, it.range.first) }
        t = t.replace('_', ' ')
        // Dots are separators unless they're part of an acronym like "S.W.A.T".
        t = t.replace(Regex("""(?<![A-Z])\.|\.(?![A-Z]\b)"""), " ")
        t = t.replace(Regex("""[\[\](){}]"""), " ")
            .replace(Regex("""\s+-\s*$"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim(' ', '-', '.', ',')
        return titleCase(t)
    }

    private val DOTTED_ACRONYM = Regex("""^(?:[A-Za-z]\.){2,}[A-Za-z]?\.?$""")

    private val SMALL_WORDS = setOf("a", "an", "and", "as", "at", "but", "by", "for", "in", "of", "on", "or", "the", "to", "vs", "with")

    private fun titleCase(s: String): String =
        s.split(' ').filter { it.isNotEmpty() }.mapIndexed { i, w ->
            when {
                // Dotted acronyms: S.W.A.T, S.H.I.E.L.D -> upper-case as-is.
                DOTTED_ACRONYM.matches(w) -> w.uppercase(Locale.ROOT)
                // Keep deliberate casing: acronyms (FBI, S.W.A.T), mixed case (iCarly).
                w.length > 1 && w.any { it.isUpperCase() } && w.any { it.isLowerCase() } && !w.first().isUpperCase() -> w
                w.length in 2..5 && w.all { it.isUpperCase() || !it.isLetter() } -> w
                i > 0 && w.lowercase(Locale.ROOT) in SMALL_WORDS -> w.lowercase(Locale.ROOT)
                else -> w.lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) }
            }
        }.joinToString(" ")

    private fun normalizeResolution(r: String): String = when (r.lowercase(Locale.ROOT)) {
        "4k", "uhd" -> "2160p"
        else -> r.lowercase(Locale.ROOT)
    }
}
