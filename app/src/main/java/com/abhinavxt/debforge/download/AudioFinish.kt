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

    /** Audio downloads to a picked (SAF) folder are downloaded privately and then copied: a fast finish needs a plain file. */
    fun needsPrivatePart(kind: Kind?, finalPath: String): Boolean = kind != null && SafPaths.isSaf(finalPath)

    /** File name of the private working copy of download [id]. */
    fun pendingName(id: String): String = id.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".part"

    /**
     * Whether a private download must start over: rows say [doneBytes] were written, but the private
     * file ([privateLength], null when absent) doesn't hold them (an older version wrote into the
     * picked folder, or the private file was cleared).
     */
    fun mustRestart(doneBytes: Long, privateLength: Long?): Boolean =
        doneBytes > 0 && (privateLength == null || privateLength < doneBytes)

    /**
     * Whether a failed extraction is final (the video is dropped) or retried (the video is kept):
     * only a file the extractor can't read, or whose first audio track isn't AAC, is final.
     */
    fun isPermanent(readable: Boolean, audioMime: String?): Boolean = !readable || !AudioExtract.supports(audioMime)

    /**
     * The .m4a is already in place and the video is gone: an extraction that finished but was cut off
     * before the state was saved. Nothing is left to download or extract.
     */
    fun alreadyExtracted(finalExists: Boolean, partExists: Boolean): Boolean = finalExists && !partExists

    /** Free space to look for: the rest of the download, plus room for the extracted audio (at most the video's size). */
    fun spaceNeeded(stillNeeded: Long, videoSize: Long, audio: Boolean): Long = if (audio) stillNeeded + videoSize else stillNeeded
}
