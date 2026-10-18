package com.abhinavxt.debforge.domain

import java.net.URI

/**
 * Finds the videos a web page links to, so the user can pick which to
 * download: <video> / <source> tags, og:video and twitter:player:stream
 * meta tags, links to video files, and video URLs inside the page's scripts
 * (players often keep them in JSON). HLS / DASH playlists are counted but
 * not offered: they're streams of small pieces, not one file to download.
 *
 * Only what's in the HTML itself: pages that build their player with
 * JavaScript after loading show nothing here.
 *
 * Pure Kotlin, no Android types: unit-tested in PageVideosTest.
 */
object PageVideos {

    data class Found(
        /** Absolute video URLs, page order, no duplicates. */
        val videos: List<String>,
        /** HLS / DASH playlists seen (not downloadable as one file). */
        val streams: Int,
        /** The page's <title> or og:title, if any. */
        val title: String?
    )

    val VIDEO_EXTS = setOf("mp4", "m4v", "mkv", "webm", "mov", "avi", "wmv", "flv", "ts", "m2ts", "mpg", "mpeg", "3gp", "ogv")
    private val STREAM_EXTS = setOf("m3u8", "mpd")

    private val OPT = setOf(RegexOption.IGNORE_CASE)
    private val TAG = Regex("""<(video|source|a|meta|base)\b([^>]*)>""", OPT)
    private val ATTR = Regex("""([a-zA-Z_:-]+)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'>]+))""")
    private val TITLE = Regex("""<title[^>]*>(.*?)</title>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    /** An absolute URL in script text, JSON-escaped or not ("https:\/\/cdn\/a.mp4"). */
    private val RAW_URL = Regex("""https?:(?:\\?/){2}[^\s"'<>()\\]*(?:\\/[^\s"'<>()\\]*)*""", OPT)
    private val META_VIDEO = setOf("og:video", "og:video:url", "og:video:secure_url", "twitter:player:stream")
    private val META_TITLE = setOf("og:title", "twitter:title")

    fun find(html: String, pageUrl: String): Found {
        var base = pageUrl
        val videos = LinkedHashSet<String>()
        val streams = HashSet<String>()
        var metaTitle: String? = null

        fun offer(raw: String?, explicit: Boolean) {
            val url = resolve(base, raw) ?: return
            when (extensionOf(url)) {
                in STREAM_EXTS -> streams += url
                in VIDEO_EXTS -> videos += url
                // <video src> / og:video name a video even without an extension.
                else -> if (explicit) videos += url
            }
        }

        TAG.findAll(html).forEach { m ->
            val attrs = attributes(m.groupValues[2])
            when (m.groupValues[1].lowercase()) {
                "base" -> attrs["href"]?.let { resolve(pageUrl, it) }?.let { base = it }
                "video" -> { offer(attrs["src"], explicit = true) }
                "source" -> {
                    // <source> also sits in <picture> / <audio>: only video types, or no type at all.
                    val type = attrs["type"]?.lowercase()
                    if (type == null || type.startsWith("video/") || "mpegurl" in type || "dash" in type) {
                        offer(attrs["src"], explicit = type != null)
                    }
                }
                "a" -> offer(attrs["href"], explicit = false)
                "meta" -> {
                    val key = (attrs["property"] ?: attrs["name"])?.lowercase()
                    when (key) {
                        in META_VIDEO -> offer(attrs["content"], explicit = true)
                        in META_TITLE -> if (metaTitle == null) metaTitle = attrs["content"]?.let(::unescape)?.trim()?.ifBlank { null }
                    }
                }
            }
        }
        RAW_URL.findAll(html).forEach { offer(it.value.replace("\\/", "/"), explicit = false) }

        val title = metaTitle ?: TITLE.find(html)?.groupValues?.get(1)?.let(::unescape)
            ?.replace(Regex("""\s+"""), " ")?.trim()?.ifBlank { null }
        return Found(videos.toList(), streams.size, title)
    }

    /** True if [name] ("clip.mp4") or a Content-Type says video. */
    fun isVideo(name: String?, contentType: String?): Boolean =
        contentType?.substringBefore(';')?.trim()?.lowercase()?.startsWith("video/") == true ||
            name?.let { extensionOf(it) in VIDEO_EXTS } == true

    /**
     * A URL the in-app browser saw the page load is a whole video file worth
     * offering. ".ts" is left out: in a browser those are HLS stream pieces.
     */
    fun isSniffable(url: String): Boolean = extensionOf(url).let { it in VIDEO_EXTS && it != "ts" }

    /** An HLS playlist: a stream DebForge can join into one file. */
    fun isHlsPlaylist(url: String): Boolean = extensionOf(url) == "m3u8"

    private fun attributes(s: String): Map<String, String> =
        ATTR.findAll(s).associate { m ->
            m.groupValues[1].lowercase() to unescape(m.groupValues[2].ifEmpty { m.groupValues[3] }.ifEmpty { m.groupValues[4] })
        }

    /** [raw] made absolute against [base]; null for blob:, data:, javascript: and junk. */
    private fun resolve(base: String, raw: String?): String? {
        val s = raw?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("#") } ?: return null
        val abs = runCatching { URI(base).resolve(URI(s.replace(" ", "%20"))) }.getOrNull() ?: return null
        val scheme = abs.scheme?.lowercase()
        if (scheme != "http" && scheme != "https" || abs.host.isNullOrEmpty()) return null
        // The fragment never reaches the server; drop it so "a.mp4#t=5" and "a.mp4" are one video.
        return abs.toASCIIString().substringBefore('#')
    }

    private fun extensionOf(url: String): String {
        val path = runCatching { URI(url).path }.getOrNull() ?: url.substringBefore('?')
        val last = path.substringAfterLast('/')
        return if ('.' in last) last.substringAfterLast('.').lowercase() else ""
    }

    private fun unescape(s: String) = s
        .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&#039;", "'")
        .replace("&lt;", "<").replace("&gt;", ">")
}
