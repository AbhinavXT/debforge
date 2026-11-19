package com.abhinavxt.debforge.player

import android.net.Uri
import com.abhinavxt.debforge.data.playback.PlaylistEntry
import com.abhinavxt.debforge.data.playback.YouTubeMeta
import com.abhinavxt.debforge.domain.YouTubeLinks
import com.abhinavxt.debforge.domain.YouTubeSearchResult
import com.abhinavxt.debforge.player.youtube.YouTubeStreaming

/** One item of a mixed queue: a downloaded track, or a YouTube video that streams (unless a download of it is found). */
sealed interface MixedEntry {
    data class Local(val track: AudioTrack) : MixedEntry
    data class YouTube(val videoId: String, val meta: YouTubeMeta) : MixedEntry
}

/** A completed YouTube download, reduced to what the local preference needs. */
data class YouTubeDownloadRow(val id: String, val sourceRef: String)

/**
 * The download to play instead of streaming, per video id: an audio-only download wins over one that has video
 * (its audio plays fine too); within a kind the first row wins.
 */
fun preferredLocalDownloads(rows: List<YouTubeDownloadRow>): Map<String, String> {
    val audio = LinkedHashMap<String, String>()
    val video = LinkedHashMap<String, String>()
    for (row in rows) {
        val ref = YouTubeLinks.parseSourceRef(row.sourceRef) ?: continue
        (if (ref.videoItag == null) audio else video).putIfAbsent(ref.videoId, row.id)
    }
    return video + audio // audio overrides video for the same video id
}

/** A streamed YouTube track that is not resolved yet: its id is `ytaudio:<id>`, itag 0 and an empty uri until looked up. */
fun lazyYouTubeTrack(videoId: String, meta: YouTubeMeta) = AudioTrack(
    id = YouTubeStreaming.streamPositionKey(videoId, audioOnly = true),
    uri = Uri.EMPTY,
    title = meta.title,
    artist = meta.channel,
    artworkFile = null,
    artworkUrl = PlaylistEntry.thumbnailUrl(videoId),
    youtube = YouTubeAudio(videoId, 0)
)

/**
 * The first queue index after the current one (the head of [order], which lists queue indices in playing order from
 * the current item) that streams from YouTube and has no link yet; null when none needs one.
 */
fun nextToResolve(tracks: List<AudioTrack>, order: List<Int>): Int? =
    order.drop(1).firstOrNull { i -> tracks.getOrNull(i)?.let { it.youtube != null && it.uri == Uri.EMPTY } == true }

/** The related lists of the last few resolved videos, least recently used dropped first. */
class RelatedCache(private val max: Int = 20) {
    private val map = object : LinkedHashMap<String, List<YouTubeSearchResult>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<YouTubeSearchResult>>?) = size > max
    }

    @Synchronized fun put(videoId: String, related: List<YouTubeSearchResult>) { map[videoId] = related }
    @Synchronized fun get(videoId: String): List<YouTubeSearchResult>? = map[videoId]
}
