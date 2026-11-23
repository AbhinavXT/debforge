package com.abhinavxt.debforge.data.playback

import org.json.JSONObject

/** Whether a YouTube playlist item is played as audio or as video. */
enum class YouTubeMode(val code: Char) { AUDIO('a'), VIDEO('v') }

/** One playlist entry. Stored as a plain string: a download id, or `yt:a:<videoId>` / `yt:v:<videoId>`. */
sealed interface PlaylistEntry {
    data class Download(val id: String) : PlaylistEntry
    data class YouTube(val videoId: String, val mode: YouTubeMode) : PlaylistEntry

    companion object {
        const val YT_PREFIX = "yt:"
        private val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")

        fun isValidVideoId(id: String) = VIDEO_ID.matches(id)

        /** Anything that is not a well-formed YouTube token is a download id; blank gives null. */
        fun parse(token: String): PlaylistEntry? {
            if (token.isBlank()) return null
            // Downloaded YouTube files also have ids starting "yt:" (e.g. "yt:<id>:-:140"): those are downloads.
            return parseYouTube(token) ?: Download(token)
        }

        /** Only well-formed `yt:` tokens; null for download ids and malformed tokens. */
        fun parseYouTube(token: String): YouTube? {
            if (!token.startsWith(YT_PREFIX) || token.length != YT_PREFIX.length + 2 + 11) return null
            val mode = YouTubeMode.entries.firstOrNull { it.code == token[3] } ?: return null
            if (token[4] != ':') return null
            val id = token.substring(5)
            return if (isValidVideoId(id)) YouTube(id, mode) else null
        }

        fun format(e: PlaylistEntry): String = when (e) {
            is Download -> e.id
            is YouTube -> "$YT_PREFIX${e.mode.code}:${e.videoId}"
        }

        fun isYouTubeToken(token: String) = parseYouTube(token) != null

        /** Thumbnails are not stored; derived from the video id. */
        fun thumbnailUrl(videoId: String) = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
    }
}

/** Display data for a YouTube playlist item, keyed by video id in [AudioListsState.ytMeta]. */
data class YouTubeMeta(val title: String, val channel: String?, val durationSec: Int?) {
    internal fun toJson(): JSONObject = JSONObject().put("t", title).also { o ->
        channel?.let { o.put("c", it) }
        durationSec?.let { o.put("d", it) }
    }

    companion object {
        internal fun fromMap(o: Map<*, *>): YouTubeMeta? {
            val t = (o["t"] as? String)?.takeIf { it.isNotBlank() } ?: return null
            return YouTubeMeta(t, o["c"] as? String, (o["d"] as? Number)?.toInt()?.takeIf { it > 0 })
        }
    }
}

enum class YouTubeAddResult { ADDED, MODE_CHANGED, ALREADY_IN_LIST, LIMIT_REACHED, NO_SUCH_LIST, INVALID_ID }

/** A playlist read as typed entries plus the metadata of its YouTube items. */
data class TypedPlaylist(val id: String, val name: String, val entries: List<PlaylistEntry>)
