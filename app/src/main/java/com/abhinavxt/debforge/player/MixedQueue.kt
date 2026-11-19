package com.abhinavxt.debforge.player

import android.net.Uri
import com.abhinavxt.debforge.data.playback.PlaylistEntry
import com.abhinavxt.debforge.data.playback.YouTubeMeta
import com.abhinavxt.debforge.domain.YouTubeLinks
import com.abhinavxt.debforge.domain.YouTubeSearchResult
import com.abhinavxt.debforge.player.youtube.YouTubeStreaming
import kotlinx.coroutines.async

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

/**
 * Counts YouTube lookups that failed for good ([recordBroken]) in a row. After [limit] of them YouTube is likely
 * blocking requests (bot check) rather than every video being gone, so the caller stops dropping items.
 */
class BrokenBreaker(private val limit: Int = 3) {
    private var count = 0

    /** Records one failure; true when this one trips the breaker (the count then starts over). */
    fun recordBroken(): Boolean {
        count++
        if (count < limit) return false
        count = 0
        return true
    }

    fun reset() { count = 0 }
}

/** Lets a message through at most once per [windowMs]; repeats inside the window are swallowed. */
class ToastCollapser(private val windowMs: Long = 4_000L) {
    private var lastAt: Long? = null

    fun shouldShow(nowMs: Long): Boolean {
        val last = lastAt
        if (last != null && nowMs - last in 0 until windowMs) return false
        lastAt = nowMs
        return true
    }
}

/**
 * The shuffle order after a new item is inserted at queue index [at] to play right after [current]: [order] lists the
 * queue indices in playing order before the insert; indices from [at] up shift by one.
 */
fun orderWithPlayNext(order: List<Int>, current: Int, at: Int): List<Int> {
    val shifted = order.map { if (it >= at) it + 1 else it }
    val pos = shifted.indexOf(current)
    return shifted.toMutableList().also { it.add(if (pos < 0) 0 else pos + 1, at) }
}

/** The shuffle order after a new item is appended at queue index [at] (the old size): it plays last. */
fun orderWithAppend(order: List<Int>, at: Int): List<Int> =
    order.map { if (it >= at) it + 1 else it } + at

/** True when a YouTube video is already queued, as a stream ([ytaudio] id) or as its download, whichever [builtId] is. */
fun youTubeAlreadyQueued(queueIds: Collection<String>, videoId: String, builtId: String): Boolean =
    builtId in queueIds || YouTubeStreaming.streamPositionKey(videoId, audioOnly = true) in queueIds

/** Runs one computation per key at a time: a second caller for a key already running awaits the same result. */
class InFlight<K : Any, V> {
    private val map = java.util.concurrent.ConcurrentHashMap<K, kotlinx.coroutines.Deferred<V>>()

    fun isRunning(key: K) = map[key]?.isActive == true

    suspend fun run(key: K, scope: kotlinx.coroutines.CoroutineScope, block: suspend () -> V): V {
        val existing = map[key]
        if (existing != null) return existing.await()
        val d = scope.async(start = kotlinx.coroutines.CoroutineStart.LAZY) { block() }
        map[key] = d
        d.invokeOnCompletion { map.remove(key, d) }
        d.start()
        return d.await()
    }

    fun cancelAll() { map.values.forEach { it.cancel() }; map.clear() }
}

/**
 * The related video autoplay appends: the first of [related] that was not played this session, is not queued, and is
 * not live (a live stream reports no duration). Null when nothing fits.
 */
fun pickAutoplay(related: List<YouTubeSearchResult>, playedThisSession: Set<String>, queuedIds: Set<String>): YouTubeSearchResult? =
    related.firstOrNull { r ->
        val id = YouTubeLinks.videoId(r.url) ?: return@firstOrNull false
        r.durationSec > 0 && id !in playedThisSession && id !in queuedIds
    }

/**
 * [pickAutoplay], and the picked id is marked in [playedThisSession] at once: if the item is later dropped as
 * unavailable it is not picked again, which would loop.
 */
fun pickAutoplayMarked(related: List<YouTubeSearchResult>, playedThisSession: MutableSet<String>, queuedIds: Set<String>): YouTubeSearchResult? {
    val pick = pickAutoplay(related, playedThisSession, queuedIds) ?: return null
    YouTubeLinks.videoId(pick.url)?.let { playedThisSession.add(it) }
    return pick
}

/**
 * Whether reaching the current item should append a related video: the item is the last in play order and streams
 * from YouTube, repeat is off, the setting is on, and a sleep timer set for the end of the track is not armed.
 */
fun shouldAutoplay(isLastInOrder: Boolean, isYouTube: Boolean, repeat: AudioRepeat, enabled: Boolean, sleepAtEnd: Boolean): Boolean =
    enabled && isLastInOrder && isYouTube && repeat == AudioRepeat.OFF && !sleepAtEnd
