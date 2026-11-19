package com.abhinavxt.debforge.download

import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.FileDescriptor
import java.io.IOException

/** Saving only the audio track of a downloaded MP4 as .m4a, without re-encoding. */
object AudioExtract {
    /** Only AAC goes into an .m4a as is. */
    fun supports(mime: String?): Boolean = mime == "audio/mp4a-latm"

    private val MP4_EXTS = setOf("mp4", "m4v", "mov", "3gp")
    private val MP4_MIMES = setOf("video/mp4", "video/quicktime", "video/3gpp")

    /** True when [url] (query and fragment ignored) or [mime] names an MP4-family file; never for HLS. */
    fun canExtract(url: String, mime: String? = null): Boolean {
        if (url.startsWith("hls:")) return false
        if (mime?.substringBefore(';')?.trim()?.lowercase() in MP4_MIMES) return true
        val path = url.substringBefore('#').substringBefore('?')
        val name = path.substringAfterLast('/')
        val dot = name.lastIndexOf('.')
        return dot >= 0 && name.substring(dot + 1).lowercase() in MP4_EXTS
    }

    /** [videoName] with its extension replaced by .m4a (added when there is none): "Clip.mp4" is "Clip.m4a". */
    fun m4aName(videoName: String): String {
        val dot = videoName.lastIndexOf('.')
        val base = if (dot > 0) videoName.substring(0, dot) else videoName
        return "$base.m4a"
    }

    /**
     * MIME of the first audio track in the file at [fd], or null when it has none.
     * Throws IOException when the file isn't something the extractor reads (not an MP4).
     */
    fun audioMime(fd: FileDescriptor): String? {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(fd)
            for (i in 0 until ex.trackCount) {
                val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
                if (mime != null && mime.startsWith("audio/")) return mime
            }
            return null
        } catch (e: IOException) {
            throw e
        } catch (e: RuntimeException) {
            throw IOException("Not a readable media file", e)
        } finally {
            ex.release()
        }
    }
}
