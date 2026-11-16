package com.abhinavxt.debforge.domain

enum class StreamKind { VIDEO, AUDIO }
enum class Container { MP4, WEBM, M4A }

/** contentLength is -1 when unknown; bitrateBps is 0 when unknown. */
data class YouTubeStream(
    val itag: Int,
    val url: String,
    val kind: StreamKind,
    val container: Container,
    val codec: String,
    val height: Int = 0,
    val fps: Int = 0,
    val bitrateBps: Long = 0,
    val contentLength: Long = -1,
)

data class YouTubeVideo(
    val id: String,
    val title: String,
    val uploader: String,
    val durationSec: Long,
    val thumbnailUrl: String?,
    val streams: List<YouTubeStream>,
)

/** One row of a YouTube search; [url] goes through the normal link path. */
data class YouTubeSearchResult(
    val url: String,
    val title: String,
    val channel: String?,
    val durationSec: Long,
    val thumbnailUrl: String?,
)

/** A playlist's videos (first 200); each [YouTubeSearchResult.url] goes through the normal link path. */
data class YouTubePlaylist(val title: String, val items: List<YouTubeSearchResult>)

/** One downloadable option. [videoItag] is null for audio only; [height] 0 then. */
data class FormatChoice(
    val videoItag: Int?,
    val audioItag: Int,
    val extension: String,
    val estimatedBytes: Long,
    val isEstimate: Boolean,
    val height: Int,
    val fps: Int,
    val container: Container,
    val audioBitrateBps: Long,
    val isDefault: Boolean = false,
)

object YouTubeFormats {
    private const val AV1_MIN_SDK = 34

    private fun YouTubeStream.rank() = fps * 1_000_000_000L + bitrateBps

    fun choices(streams: List<YouTubeStream>, sdkInt: Int, durationSec: Long = 0): List<FormatChoice> {
        val audio = streams.filter { it.kind == StreamKind.AUDIO }
        val aac = audio.filter { it.container == Container.M4A }.maxByOrNull { it.bitrateBps }
        val opus = audio.filter { it.container == Container.WEBM }.maxByOrNull { it.bitrateBps }

        val video = streams.filter { it.kind == StreamKind.VIDEO && it.height > 0 }.groupBy { it.height }
            .toSortedMap(reverseOrder())
            .mapNotNull { (_, at) ->
                fun by(pred: (YouTubeStream) -> Boolean) = at.filter(pred).maxByOrNull { it.rank() }
                val h264 = by { it.codec.startsWith("avc1") }
                val vp9 = by { it.codec.startsWith("vp") }
                val av1 = by { it.codec.startsWith("av01") }.takeIf { sdkInt >= AV1_MIN_SDK }
                when {
                    h264 != null && aac != null -> build(h264, aac, "mp4", Container.MP4, durationSec)
                    vp9 != null && opus != null -> build(vp9, opus, "webm", Container.WEBM, durationSec)
                    av1 != null && aac != null -> build(av1, aac, "mp4", Container.MP4, durationSec)
                    else -> null
                }
            }
        val audioOnly = listOfNotNull(
            aac?.let { build(null, it, "m4a", Container.M4A, durationSec) },
            opus?.let { build(null, it, "weba", Container.WEBM, durationSec) },
        )
        val all = video + audioOnly
        val default = video.firstOrNull { it.container == Container.MP4 && it.height <= 1080 }
            ?: all.firstOrNull()
        return all.map { it.copy(isDefault = it === default) }
    }

    private fun build(v: YouTubeStream?, a: YouTubeStream, ext: String, c: Container, durationSec: Long): FormatChoice {
        val parts = listOfNotNull(v, a)
        val known = parts.all { it.contentLength > 0 }
        val bytes = if (known) parts.sumOf { it.contentLength }
        else parts.sumOf { if (it.contentLength > 0) it.contentLength else it.bitrateBps * durationSec / 8 }
        return FormatChoice(v?.itag, a.itag, ext, bytes, !known, v?.height ?: 0, v?.fps ?: 0, c, a.bitrateBps)
    }
}

/** Names are persisted in settings: add entries, never rename. */
enum class DefaultQuality(val height: Int = 0) { ASK, BEST, P1080(1080), P720(720), P480(480), AUDIO }

/** The choice the preview preselects for [quality]; [DefaultQuality.ASK] keeps the built-in default. */
fun pickDefaultChoice(choices: List<FormatChoice>, quality: DefaultQuality): FormatChoice? {
    val video = choices.filter { it.videoItag != null }.sortedByDescending { it.height }
    val pick = when (quality) {
        DefaultQuality.ASK -> null
        DefaultQuality.BEST -> video.firstOrNull()
        DefaultQuality.AUDIO -> choices.filter { it.videoItag == null }.let { a -> a.firstOrNull { it.isDefault } ?: a.firstOrNull() }
        else -> video.firstOrNull { it.height <= quality.height } ?: video.lastOrNull()
    }
    return pick ?: choices.firstOrNull { it.isDefault } ?: choices.firstOrNull()
}
