package com.abhinavxt.debforge.domain

/** Recognises YouTube links in pasted or shared text, and builds/parses the `yt:` source ref. */
object YouTubeLinks {
    private const val ID = "([A-Za-z0-9_-]{11})(?![A-Za-z0-9_-])"
    private const val HOST = "(?<![\\w.-])(?:https?://)?(?:[\\w-]+\\.)*"
    private val long = Regex(
        "$HOST(?:youtube\\.com|youtube-nocookie\\.com)/(?:watch\\?(?:[^\\s#]*&)?v=|(?:shorts|live|embed|v)/)$ID"
    )
    private val short = Regex("$HOST" + "youtu\\.be/$ID")
    private val playlist = Regex("$HOST" + "youtube\\.com/(?:playlist\\?|[^\\s]*[?&]list=)")

    fun videoId(text: String): String? =
        (long.find(text) ?: short.find(text))?.groupValues?.get(1)

    /** A link the resolver accepts for a feed item: its own [mediaUrl] when that is a video link, else the watch URL of [itemKey]. */
    fun watchUrl(itemKey: String, mediaUrl: String? = null): String =
        mediaUrl?.takeIf { videoId(it) != null } ?: "https://www.youtube.com/watch?v=$itemKey"

    private val pageHosts = setOf("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com")

    /**
     * Whether a browser page at [url] shows one video: a watch page (a `list=` beside the `v=` is still that video),
     * a short, or a youtu.be link. Channel, playlist-only, search and other sites' pages are not.
     */
    fun isVideoPage(url: String): Boolean {
        val uri = try { java.net.URI(url.trim()) } catch (e: java.net.URISyntaxException) { return false }
        if (uri.scheme?.lowercase() !in setOf("http", "https")) return false
        val host = uri.host?.lowercase() ?: return false
        val path = uri.rawPath.orEmpty()
        val onPath = when (host) {
            "youtu.be" -> path.length > 1
            in pageHosts -> path == "/watch" || path.startsWith("/shorts/")
            else -> false
        }
        return onPath && videoId(url) != null
    }

    fun isPlaylistOnly(text: String): Boolean = videoId(text) == null && playlist.containsMatchIn(text)

    private val listParam = Regex("[?&]list=([A-Za-z0-9_-]+)")

    /** The canonical playlist URL for a playlist link, or null when there is no list id. */
    fun playlistUrl(text: String): String? =
        listParam.find(text)?.groupValues?.get(1)?.let { "https://www.youtube.com/playlist?list=$it" }

    private val anyUrl = Regex("^\\s*https?://", RegexOption.IGNORE_CASE)

    /** Plain words to search for: not blank, not a video/playlist link, and not some other web address. */
    fun isSearchQuery(text: String): Boolean =
        text.isNotBlank() && videoId(text) == null && !isPlaylistOnly(text) && !anyUrl.containsMatchIn(text)

    /** The trimmed words to remember as a recent search, or null when [text] is blank or a link (those are not searches). */
    fun historyQuery(text: String): String? = text.trim().takeIf { isSearchQuery(it) }

    const val PREFIX = "yt:"

    /** `yt:<videoId>:<videoItag|->:<audioItag>` */
    fun sourceRef(videoId: String, videoItag: Int?, audioItag: Int): String =
        "$PREFIX$videoId:${videoItag ?: "-"}:$audioItag"

    fun isSourceRef(ref: String): Boolean = ref.startsWith(PREFIX)

    data class Ref(val videoId: String, val videoItag: Int?, val audioItag: Int)

    fun parseSourceRef(ref: String): Ref? {
        if (!isSourceRef(ref)) return null
        val p = ref.removePrefix(PREFIX).split(':')
        if (p.size != 3) return null
        val audio = p[2].toIntOrNull() ?: return null
        val video = if (p[1] == "-") null else p[1].toIntOrNull() ?: return null
        return Ref(p[0], video, audio)
    }
}
