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

    fun isPlaylistOnly(text: String): Boolean = videoId(text) == null && playlist.containsMatchIn(text)

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
