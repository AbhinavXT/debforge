package com.abhinavxt.debforge.download

import com.abhinavxt.debforge.domain.HlsPlaylist

/** Picks how a YouTube audio-only download is finished: header fix-up in place, or the MediaMuxer combine. */
object AudioFinish {
    enum class Kind { M4A, WEBM }

    /**
     * The fast path applies to one progressive file only: a single URL read as consecutive
     * byte ranges with no init section. HLS joins and video+audio never qualify.
     */
    fun isSingleProgressive(media: HlsPlaylist.Media): Boolean {
        if (media.init != null || media.segments.isEmpty()) return false
        val url = media.segments.first().url
        var next = 0L
        for (s in media.segments) {
            val off = s.offset ?: return false
            val len = s.length ?: return false
            if (s.url != url || off != next || s.key != null) return false
            next = off + len
        }
        return true
    }

    /** The header check to run for [finalPath]'s container, or null when the combine must run. */
    fun kindFor(finalPath: String, trackCount: Int, singleProgressive: Boolean): Kind? {
        if (trackCount != 1 || !singleProgressive) return null
        return when (finalPath.substringAfterLast('.', "").lowercase()) {
            "m4a", "mp4" -> Kind.M4A
            "webm", "weba" -> Kind.WEBM
            else -> null
        }
    }
}
