package com.abhinavxt.debforge.ui.youtube

import com.abhinavxt.debforge.data.youtube.YouTubeException
import com.abhinavxt.debforge.domain.Container
import com.abhinavxt.debforge.domain.FormatChoice
import com.abhinavxt.debforge.domain.YouTubeLinks
import com.abhinavxt.debforge.ui.browse.formatSize
import java.util.Locale

/** Why the screen shows an error; each kind has its own friendly string. */
enum class YouTubeErrorKind { NOT_LINK, PLAYLIST, SIGN_IN, UNAVAILABLE, NETWORK, GENERIC }

fun errorKind(e: Throwable): YouTubeErrorKind = when (e) {
    is YouTubeException.PlaylistOnly -> YouTubeErrorKind.PLAYLIST
    is YouTubeException.SignInRequired -> YouTubeErrorKind.SIGN_IN
    is YouTubeException.Unavailable -> YouTubeErrorKind.UNAVAILABLE
    is YouTubeException.Network -> YouTubeErrorKind.NETWORK
    else -> YouTubeErrorKind.GENERIC
}

/** True when [text] holds a single-video link or a playlist link the resolver can explain. */
fun looksLikeYouTube(text: String) = YouTubeLinks.videoId(text) != null || YouTubeLinks.isPlaylistOnly(text)

/** "youtu.be/abc" -> "youtu.be"; used for the clipboard chip. */
fun linkHost(text: String): String =
    text.split(Regex("\\s+")).firstOrNull { "youtu" in it }
        ?.let { Regex("(?:https?://)?(?:www\\.|m\\.|music\\.)?([^/\\s?#]+)").find(it)?.groupValues?.get(1) }
        ?: "YouTube"

val FormatChoice.isAudio get() = videoItag == null

fun choiceLabel(c: FormatChoice): String =
    if (c.isAudio) "${if (c.container == Container.M4A) "M4A" else "Opus"} · ${c.audioBitrateBps / 1000} kbps"
    else "${c.height}p${if (c.fps > 30) c.fps.toString() else ""} · ${if (c.container == Container.WEBM) "WebM" else "MP4"}"

fun sizeText(c: FormatChoice): String = (if (c.isEstimate) "~" else "") + formatSize(c.estimatedBytes)

fun durationText(sec: Long): String {
    val h = sec / 3600
    val m = sec / 60 % 60
    val s = sec % 60
    val locale = Locale.getDefault() // same rule as formatSize: the device locale
    return if (h > 0) String.format(locale, "%d:%02d:%02d", h, m, s) else String.format(locale, "%d:%02d", m, s)
}

/** File systems cap a name at 255 bytes; leave room for " [1080p].ext" and a " (1)" duplicate suffix. */
private const val MAX_TITLE_BYTES = 200

/** [s] cut to at most [maxBytes] of UTF-8, never inside a surrogate pair. */
internal fun truncateUtf8(s: String, maxBytes: Int): String {
    var bytes = 0
    var i = 0
    while (i < s.length) {
        val cp = s.codePointAt(i)
        val n = when { cp < 0x80 -> 1; cp < 0x800 -> 2; cp < 0x10000 -> 3; else -> 4 }
        if (bytes + n > maxBytes) break
        bytes += n
        i += Character.charCount(cp)
    }
    return s.substring(0, i)
}

/** `<title> [1080p].mp4` or `<title>.m4a`, with characters illegal in file names removed; [fallback] names an empty title. */
fun fileNameFor(title: String, c: FormatChoice, fallback: String = "YouTube video"): String {
    val clean = truncateUtf8(title.replace(Regex("[/\\\\:*?\"<>|\\p{Cntrl}]"), "").replace(Regex("\\s+"), " ").trim(), MAX_TITLE_BYTES)
        .trim().trimEnd('.').ifBlank { fallback }
    return if (c.isAudio) "$clean.${c.extension}" else "$clean [${c.height}p].${c.extension}"
}
