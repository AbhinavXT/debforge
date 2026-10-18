package com.abhinavxt.debforge.domain

import java.net.URI

/**
 * Reads HLS (.m3u8) playlists, the format most sites stream video in: a
 * master playlist lists qualities, a media playlist lists the pieces of one
 * quality. Joined in order the pieces are one playable file (.ts, or .mp4
 * when the playlist has an init section). Some qualities keep their audio in
 * a stream of its own, joined apart and combined with the video afterwards.
 *
 * Pure Kotlin, no Android types: unit-tested in HlsPlaylistTest.
 */
object HlsPlaylist {

    sealed interface Playlist

    /** Qualities, best first; [otherUrls]: every other playlist it names (audio, subtitles, trick-play). */
    data class Master(val variants: List<Variant>, val otherUrls: Set<String> = emptySet()) : Playlist

    /** [audioUrl]: the media playlist of its separate audio, if it has one. */
    data class Variant(val url: String, val bandwidth: Long, val height: Int?, val audioUrl: String? = null)

    data class Media(
        val segments: List<Segment>,
        /** fMP4 init section, written before the first piece. */
        val init: Segment?,
        val durationSec: Double,
        /** No end marker: a live stream, still growing. */
        val live: Boolean,
        /** Encrypted with something other than plain AES-128 (DRM). */
        val drm: Boolean
    ) : Playlist

    /** One piece; [offset] / [length] when it's a byte range of a bigger file. */
    data class Segment(val url: String, val offset: Long?, val length: Long?, val key: Key?)

    /** AES-128-CBC key at [url], with the piece's [iv]. */
    class Key(val url: String, val iv: ByteArray)

    /** Null when [text] isn't a playlist. [url] is where it came from (pieces are relative to it). */
    fun parse(text: String, url: String): Playlist? {
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.firstOrNull()?.startsWith("#EXTM3U") != true) return null
        return if (lines.any { it.startsWith("#EXT-X-STREAM-INF:") }) master(lines, url) else media(lines, url)
    }

    private fun master(lines: List<String>, base: String): Master {
        val audioTracks = lines.filter { it.startsWith("#EXT-X-MEDIA:") }.map(::attributes)
            .filter { it["TYPE"] == "AUDIO" && it["URI"] != null && it["GROUP-ID"] != null }
        // Per audio group, its default track (or the first one).
        val audio = audioTracks.sortedByDescending { it["DEFAULT"] == "YES" }
            .groupBy { it.getValue("GROUP-ID") }
            .mapValues { (_, tracks) -> resolve(base, tracks.first().getValue("URI")) }
        val variants = lines.withIndex().mapNotNull { (i, line) ->
            if (!line.startsWith("#EXT-X-STREAM-INF:")) return@mapNotNull null
            val uri = lines.getOrNull(i + 1)?.takeIf { !it.startsWith("#") } ?: return@mapNotNull null
            val a = attributes(line)
            Variant(
                url = resolve(base, uri),
                bandwidth = a["BANDWIDTH"]?.toLongOrNull() ?: 0L,
                height = a["RESOLUTION"]?.substringAfter('x', "")?.toIntOrNull(),
                audioUrl = a["AUDIO"]?.let { audio[it] }
            ) to a["CODECS"].orEmpty()
        }
        // One video offered with several audio codecs: AAC first, it's what an MP4 can hold.
        return Master(
            variants.sortedByDescending { (_, codecs) -> "mp4a" in codecs }.map { it.first }
                .distinctBy { it.url }.sortedByDescending { it.bandwidth },
            lines.filter { it.startsWith("#EXT-X-MEDIA:") || it.startsWith("#EXT-X-I-FRAME-STREAM-INF:") }
                .mapNotNull { attributes(it)["URI"] }.map { resolve(base, it) }.toSet()
        )
    }

    private fun media(lines: List<String>, base: String): Media {
        val segments = mutableListOf<Segment>()
        var init: Segment? = null
        var sequence = 0L
        var duration = 0.0
        var ended = false
        var drm = false
        var keyUrl: String? = null
        var keyIv: ByteArray? = null
        var range: Pair<Long, Long?>? = null
        // A byte range without "@offset" continues where the last one of the same file ended.
        var lastUrl: String? = null
        var nextOffset = 0L
        for (line in lines) {
            val value = line.substringAfter(':', "")
            when {
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> sequence = value.toLongOrNull() ?: 0L
                line == "#EXT-X-ENDLIST" -> ended = true
                line.startsWith("#EXTINF:") -> duration += value.substringBefore(',').toDoubleOrNull() ?: 0.0
                line.startsWith("#EXT-X-BYTERANGE:") -> range = byteRange(value)
                line.startsWith("#EXT-X-KEY:") -> {
                    val a = attributes(line)
                    keyUrl = null
                    keyIv = null
                    when {
                        a["METHOD"] == "NONE" -> Unit
                        a["METHOD"] == "AES-128" && (a["KEYFORMAT"] ?: "identity") == "identity" && a["URI"] != null -> {
                            keyUrl = resolve(base, a.getValue("URI"))
                            keyIv = a["IV"]?.let(::hex)
                        }
                        else -> drm = true
                    }
                }
                line.startsWith("#EXT-X-MAP:") -> {
                    val a = attributes(line)
                    val r = a["BYTERANGE"]?.let(::byteRange)
                    // An encrypted init section must name its IV.
                    val key = keyUrl?.let { k -> keyIv?.let { Key(k, it) } }
                    init = a["URI"]?.let { Segment(resolve(base, it), r?.let { it.second ?: 0L }, r?.first, key) }
                }
                !line.startsWith("#") -> {
                    val url = resolve(base, line)
                    val r = range
                    val offset = r?.let { it.second ?: if (url == lastUrl) nextOffset else 0L }
                    if (r != null && offset != null) nextOffset = offset + r.first
                    val key = keyUrl?.let { Key(it, keyIv ?: sequenceIv(sequence + segments.size)) }
                    segments += Segment(url, offset, r?.first, key)
                    lastUrl = url
                    range = null
                }
            }
        }
        return Media(segments, init, duration, live = !ended, drm = drm)
    }

    /** "1000@200" -> length 1000 at offset 200; no "@" -> offset null. */
    private fun byteRange(s: String): Pair<Long, Long?>? {
        val length = s.substringBefore('@').trim().toLongOrNull() ?: return null
        return length to s.substringAfter('@', "").trim().toLongOrNull()
    }

    /** No IV given: the piece's sequence number, as 16 big-endian bytes. */
    private fun sequenceIv(n: Long) = ByteArray(16) { i -> if (i < 8) 0 else (n shr (8 * (15 - i))).toByte() }

    private fun hex(s: String): ByteArray? {
        val h = s.removePrefix("0x").removePrefix("0X").padStart(32, '0')
        if (h.length != 32) return null
        return ByteArray(16) { i -> h.substring(2 * i, 2 * i + 2).toIntOrNull(16)?.toByte() ?: return null }
    }

    private val ATTR = Regex("""([A-Z0-9-]+)=("[^"]*"|[^,]*)""")

    private fun attributes(line: String): Map<String, String> =
        ATTR.findAll(line.substringAfter(':')).associate { it.groupValues[1] to it.groupValues[2].trim('"') }

    private fun resolve(base: String, ref: String): String =
        runCatching { URI(base).resolve(ref.replace(" ", "%20")).toString() }.getOrDefault(ref)
}
