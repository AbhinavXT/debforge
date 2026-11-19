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
    private val idOnly = Regex("[A-Za-z0-9_-]{11}")
    private val parts = Regex("^https?://([^/?#]*)([^?#]*)(?:\\?([^#]*))?", RegexOption.IGNORE_CASE)

    /**
     * The id of the one video a browser page at [url] shows, or null: a watch page (its `v=` param; a `list=` beside
     * it is still that video), `/shorts/<id>`, `/live/<id>`, or a youtu.be link. Channel, playlist-only, search and
     * other sites' pages are not. The id is read only from those places, never from elsewhere in the URL.
     * Parsed by hand so a stray `|` or space (which java.net.URI rejects) does not hide the button.
     */
    fun pageVideoId(url: String): String? {
        val m = parts.find(url.trim()) ?: return null
        val host = m.groupValues[1].substringAfterLast('@').substringBefore(':').lowercase()
        val path = m.groupValues[2]
        val query = m.groupValues[3]
        val id = when (host) {
            "youtu.be" -> path.removePrefix("/").substringBefore('/')
            in pageHosts -> when {
                path == "/watch" -> query.split('&').firstOrNull { it.startsWith("v=") }?.removePrefix("v=")
                path.startsWith("/shorts/") -> path.removePrefix("/shorts/").substringBefore('/')
                path.startsWith("/live/") -> path.removePrefix("/live/").substringBefore('/')
                else -> null
            }
            else -> null
        }
        return id?.takeIf { idOnly.matches(it) }
    }

    fun isVideoPage(url: String): Boolean = pageVideoId(url) != null

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
