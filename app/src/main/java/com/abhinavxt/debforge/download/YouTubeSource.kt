package com.abhinavxt.debforge.download

import com.abhinavxt.debforge.data.local.ChunkDao
import com.abhinavxt.debforge.data.local.DownloadEntity
import com.abhinavxt.debforge.data.youtube.YouTubeResolver
import com.abhinavxt.debforge.domain.HlsPlaylist
import com.abhinavxt.debforge.domain.YouTubeLinks
import com.abhinavxt.debforge.ui.browse.formatSize
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns a `yt:` row into the pieces [HlsDownloader] downloads: the video
 * stream (if any) and the audio stream, each cut into [PIECE] byte ranges
 * (YouTube throttles a plain whole-file GET to a crawl, ranged requests run
 * at full speed). The video is re-resolved on every attempt because stream
 * URLs expire; the itags in the source ref pick the same streams each time,
 * so the same length gives the same pieces and a resume can continue.
 */
@Singleton
class YouTubeSource @Inject constructor(
    private val resolver: YouTubeResolver,
    private val chunkDao: ChunkDao
) {

    companion object {
        const val PIECE = 4_000_000L

        /** [length] bytes as consecutive ranges of [piece] bytes, the last one short; none for length <= 0. */
        fun planPieces(url: String, length: Long, piece: Long = PIECE): List<HlsPlaylist.Segment> {
            if (length <= 0 || piece <= 0) return emptyList()
            return (0 until (length + piece - 1) / piece).map { i ->
                HlsPlaylist.Segment(url, i * piece, minOf(piece, length - i * piece), null)
            }
        }

        /** Not enough room for what's left of the streams plus the joined file; [written] is already on disk. */
        fun spaceError(total: Long, free: Long?, written: Long = 0L): String? {
            val needed = (total - written).coerceAtLeast(0L) + total
            return if (free != null && free < needed)
                "Not enough space: needs ${formatSize(needed)}, ${formatSize(free)} free. Free up space, then retry."
            else null
        }
    }

    /** Video track first (absent for audio only), then audio. Throws when it can't be downloaded. */
    suspend fun tracks(entity: DownloadEntity, partPath: String): List<HlsPlaylist.Media> {
        val ref = YouTubeLinks.parseSourceRef(entity.sourceRef) ?: throw IOException("Bad YouTube link")
        val video = resolver.resolve("https://www.youtube.com/watch?v=${ref.videoId}")
        val media = listOfNotNull(ref.videoItag, ref.audioItag).map { itag ->
            val stream = video.streams.firstOrNull { it.itag == itag }
                ?: throw IOException("YouTube no longer offers this format. Add the video again.")
            val pieces = planPieces(stream.url, stream.contentLength)
            if (pieces.isEmpty()) throw IOException("YouTube didn't report the size of this format")
            HlsPlaylist.Media(pieces, null, video.durationSec.toDouble(), live = false, drm = false)
        }
        val total = media.sumOf { m -> m.segments.sumOf { it.length ?: 0L } }
        val written = chunkDao.getForDownload(entity.id).sumOf { it.bytesWritten }
        spaceError(total, DownloadFiles.availableBytes(partPath), written)?.let { throw IOException(it) }
        return media
    }
}
