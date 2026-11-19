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

/** The id of the first download in [rows] that holds [videoId] with a video stream (an audio-only one does not count); null when none. */
fun preferredVideoDownload(rows: List<YouTubeDownloadRow>, videoId: String): String? =
    rows.firstOrNull { row ->
        val ref = YouTubeLinks.parseSourceRef(row.sourceRef)
        ref != null && ref.videoId == videoId && ref.videoItag != null
    }?.id

/** What a video handing over to audio does to the queue. */
sealed interface HandoffPlan {
    /** The item is already queued at [index]: seek there. */
    data class JumpTo(val index: Int) : HandoffPlan
    /** Insert the item at [index], right after the current one. */
    data class InsertAt(val index: Int) : HandoffPlan
    /** Nothing is queued: a one-item queue. */
    object StartNew : HandoffPlan
}

/** The same item: the same id, or the same YouTube video (a stream and its audio stream differ only in link or itag). */
fun sameHandoffItem(a: AudioTrack, b: AudioTrack): Boolean =
    a.id == b.id || (a.youtube != null && a.youtube.videoId == b.youtube?.videoId)

/** Where [track] goes in [queue] (play position [current]): the current item or an already queued one is reused. */
fun handoffPlan(
    queue: List<AudioTrack>,
    current: Int,
    track: AudioTrack,
    /** The id of a local download of the same YouTube video, when [track] is its stream. */
    localId: String? = null,
    /** The YouTube video id [track] stands for (a download of a YouTube video has no [AudioTrack.youtube]). */
    videoId: String? = null
): HandoffPlan {
    if (queue.isEmpty()) return HandoffPlan.StartNew
    fun same(q: AudioTrack) = sameHandoffItem(q, track) || (localId != null && q.id == localId) ||
        (videoId != null && q.youtube?.videoId == videoId)
    val cur = current.coerceIn(queue.indices)
    if (same(queue[cur])) return HandoffPlan.JumpTo(cur)
    val at = queue.indexOfFirst { same(it) }
    if (at >= 0) return HandoffPlan.JumpTo(at)
    return HandoffPlan.InsertAt(cur + 1)
}

/** Whether the resume-spot seek on reaching [mediaId] is skipped: it is the item a handoff just placed at its own position. */
fun handoffSkipsResume(mediaId: String?, handoffId: String?): Boolean = handoffId != null && mediaId == handoffId

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

/** What [AudioPlayer.parkForVideo] does: nothing, only stop a pending activation, or park the playing queue. */
enum class ParkAction { NONE, CANCEL_ACTIVATION, PARK }

/** A queue that is already parked (restored, paused) may still be activating: that must be cancelled, not left to finish. */
fun parkAction(queueEmpty: Boolean, restoredPending: Boolean): ParkAction = when {
    queueEmpty -> ParkAction.NONE
    restoredPending -> ParkAction.CANCEL_ACTIVATION
    else -> ParkAction.PARK
}

/** A handoff transition: whether the resume-spot seek is skipped, and the handoff id that remains. */
data class HandoffMark(val skipResume: Boolean, val next: String?)

/**
 * The handoff item is done with once playback reaches it or moves past it by a real transition. A playlist-changed
 * transition (a replace in place) says nothing about that, so it keeps the id.
 */
fun handoffAfterTransition(handoffId: String?, mediaId: String?, playlistChanged: Boolean): HandoffMark {
    if (handoffId == null || playlistChanged) return HandoffMark(false, handoffId)
    return HandoffMark(skipResume = mediaId == handoffId, next = null)
}

/** Waits for a restore that is still running, so a handoff decides its plan on the restored queue. */
suspend fun awaitRestore(restore: kotlinx.coroutines.Job?) { restore?.join() }

/** A handoff to audio that failed to start. */
data class HandoffFailure(val track: AudioTrack, val startMs: Long, val speed: Float)

/** Whether a video is the item the parked queue is on: the same download id, or the same YouTube video (local or streamed copy). */
fun videoMatchesTrack(trackId: String, trackVideoId: String?, itemId: String, videoId: String?): Boolean =
    trackId == itemId || (videoId != null && trackVideoId == videoId)

/** Where the parked audio continues once a matching video closes at [positionMs]; null leaves it alone (no match, or the video ended). */
fun parkedPositionAfterVideo(matches: Boolean, positionMs: Long, durationMs: Long): Long? = when {
    !matches || positionMs < 0 -> null
    durationMs > 0 && positionMs >= durationMs - 1_000 -> null
    else -> positionMs
}
