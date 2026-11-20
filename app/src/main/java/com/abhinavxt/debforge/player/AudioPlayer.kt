package com.abhinavxt.debforge.player

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.SystemClock
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.abhinavxt.debforge.MainActivity
import com.abhinavxt.debforge.R
import com.abhinavxt.debforge.domain.DirectLinkParser
import com.abhinavxt.debforge.data.local.DownloadDao
import com.abhinavxt.debforge.data.history.PlayHistory
import com.abhinavxt.debforge.data.playback.AudioQueueStore
import com.abhinavxt.debforge.data.playback.AudioResume
import com.abhinavxt.debforge.data.playback.QueueSnapshot
import com.abhinavxt.debforge.data.playback.YouTubeMeta
import com.abhinavxt.debforge.domain.YouTubeSearchResult
import com.abhinavxt.debforge.data.playback.SavedPosition
import com.abhinavxt.debforge.data.playback.SavedTrack
import com.abhinavxt.debforge.data.playback.Snapshot
import com.abhinavxt.debforge.data.playback.restorePlan
import com.abhinavxt.debforge.data.playback.validShuffleOrder
import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder
import com.abhinavxt.debforge.data.sponsor.SponsorSegmentRepository
import com.abhinavxt.debforge.domain.DownloadState
import com.abhinavxt.debforge.domain.SponsorSegment
import com.abhinavxt.debforge.domain.SponsorSkip
import com.abhinavxt.debforge.download.LocalFiles
import com.abhinavxt.debforge.data.youtube.YouTubeException
import com.abhinavxt.debforge.data.youtube.YouTubeResolver
import com.abhinavxt.debforge.data.youtube.decodeEntities
import com.abhinavxt.debforge.domain.YouTubeFormats
import com.abhinavxt.debforge.domain.YouTubeLinks
import com.abhinavxt.debforge.domain.YouTubeStream
import com.abhinavxt.debforge.domain.YouTubeVideo
import com.abhinavxt.debforge.player.youtube.YouTubeMediaSourceFactory
import com.abhinavxt.debforge.ui.history.audioHistoryEntry
import com.abhinavxt.debforge.player.youtube.YouTubeStreamTag
import com.abhinavxt.debforge.player.youtube.YouTubeStreaming
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import com.abhinavxt.debforge.data.local.DownloadEntity
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** One playable audio file; plain data so callers need no database types. */
data class AudioTrack(
    val id: String,
    val uri: Uri,
    val title: String,
    val artist: String?,
    val artworkFile: File?,
    /** Remote artwork (a YouTube thumbnail); [artworkFile] wins when both are set. */
    val artworkUrl: String? = null,
    /** Set for a streamed YouTube track: [uri] is then the audio stream URL, which expires. */
    val youtube: YouTubeAudio? = null,
    /** The artist's channel page, for a streamed YouTube track; not stored anywhere, so a downloaded file has none. */
    val artistUrl: String? = null,
    /** A downloaded video file: the same item can be watched (see [canWatch]). Set by [audioTrackFor], so a restore keeps it. */
    val isVideoFile: Boolean = false
) {
    /** The video of this item can be opened: a downloaded video file or a streamed YouTube video; never a podcast or audio file. */
    val canWatch: Boolean get() = isVideoFile || youtube != null

    /** A podcast episode streamed from its feed's url: a network [uri] with no YouTube stream behind it. */
    val isRemoteStream: Boolean get() = youtube == null && (uri.scheme == "http" || uri.scheme == "https")

    /**
     * Where the resume spot is kept. A streamed episode shares the key of its downloaded file (the download id of
     * its url), so streaming it and later downloading it, or the other way round, keeps the place.
     */
    val resumeId: String get() = if (isRemoteStream) DirectLinkParser.itemId(uri.toString()) else id
}

/** Whether a failed track should stay queued and paused: a stream (YouTube or podcast) that failed with no connection. */
internal fun keepsOfflineOnError(isStream: Boolean, online: Boolean): Boolean = isStream && !online

/** What [AudioPlayer.enqueue] did with a track. */
enum class EnqueueOutcome { STARTED, ADDED, ALREADY_QUEUED }

/** Which YouTube audio stream a track plays, enough to re-resolve it when the link expires. */
data class YouTubeAudio(val videoId: String, val audioItag: Int)

/** A track that streams [audio] of [video]; its id is the resume key `ytaudio:<id>`, so it never collides with a download id. */
fun youtubeAudioTrack(video: YouTubeVideo, audio: YouTubeStream) = AudioTrack(
    id = YouTubeStreaming.streamPositionKey(video.id, audioOnly = true),
    uri = Uri.parse(audio.url),
    title = video.title,
    artist = video.uploader.ifBlank { null },
    artworkFile = null,
    artworkUrl = video.thumbnailUrl,
    youtube = YouTubeAudio(video.id, audio.itag),
    artistUrl = video.uploaderUrl
)

/** The fresh stream for [itag] (or the nearest audio choice when it is gone) after re-resolving [video]; null if it has no audio. */
fun freshAudioStream(video: YouTubeVideo, itag: Int, sdkInt: Int): YouTubeStream? {
    val choices = YouTubeFormats.choices(video.streams, sdkInt, video.durationSec)
    val pick = YouTubeStreaming.reselect(YouTubeLinks.Ref(video.id, null, itag), 0, choices) ?: return null
    return video.streams.firstOrNull { it.itag == pick.audioItag }
}

/** A stream error is worth one re-resolve unless one already ran in the last [YT_RECOVER_GAP_MS] (a link that fails at once stays failed). */
const val YT_RECOVER_GAP_MS = 60_000L
fun mayRecoverLink(lastAttemptAtMs: Long?, nowMs: Long): Boolean = lastAttemptAtMs == null || nowMs - lastAttemptAtMs >= YT_RECOVER_GAP_MS

/**
 * How long to wait before re-resolving after a stream error, null for none. A link that was just looked up and still
 * fails (a freshly restored queue after a cold start) gets one more try after a pause; after that, or when the last
 * try is recent, the track stays failed. [attempts] counts the tries in the current burst, [lastAttemptAtMs] the latest.
 */
const val YT_RECOVER_RETRY_DELAY_MS = 2_000L
fun recoverDelayMs(attempts: Int, lastAttemptAtMs: Long?, nowMs: Long): Long? = when {
    lastAttemptAtMs == null || nowMs - lastAttemptAtMs >= YT_RECOVER_GAP_MS -> 0L
    attempts < 2 -> YT_RECOVER_RETRY_DELAY_MS
    else -> null
}

/**
 * Why a streamed track's link could not be looked up again: [OFFLINE] (no validated network, or the lookup failed on
 * the connection) keeps the track for when the connection is back; [BROKEN] (the video is gone, private, or has no
 * audio) drops it.
 */
enum class LinkFailure { OFFLINE, BROKEN }
fun linkFailure(error: Throwable?, online: Boolean): LinkFailure = when {
    !online -> LinkFailure.OFFLINE
    generateSequence(error) { it.cause }.any { it is YouTubeException.Network || it is java.io.IOException } -> LinkFailure.OFFLINE
    else -> LinkFailure.BROKEN
}

enum class AudioRepeat { OFF, ALL, ONE }

/** What a home-screen widget button asks of the player. */
enum class WidgetAction { TOGGLE, NEXT, PREVIOUS }

data class AudioState(
    val queue: List<AudioTrack> = emptyList(),
    val index: Int = 0,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val shuffle: Boolean = false,
    val repeat: AudioRepeat = AudioRepeat.OFF,
    /** Queue indices in playing order from the current one (shuffle-aware); natural order by default. */
    val order: List<Int> = queue.indices.toList(),
    /** From the player, so shuffle and repeat count: Next does nothing on the last item with repeat off. */
    val hasNext: Boolean = true,
    val hasPrevious: Boolean = true,
    val speed: Float = 1f,
    /** Sleep timer: time left when counting down, [sleepAtEnd] when pausing after this track; both unset = off. */
    val sleepLeftMs: Long? = null,
    val sleepAtEnd: Boolean = false,
    /** Ids of queue items that autoplay appended (labelled "Autoplay"); kept in memory only. */
    val autoplayIds: Set<String> = emptySet(),
    /** The "autoplay related videos" setting, mirrored in the queue sheet. */
    val autoplayOn: Boolean = true
) {
    val track: AudioTrack? get() = queue.getOrNull(index)
}

/** Off -> all -> one -> off. */
fun AudioRepeat.next(): AudioRepeat = AudioRepeat.entries[(ordinal + 1) % AudioRepeat.entries.size]

fun AudioRepeat.toPlayerMode(): Int = when (this) {
    AudioRepeat.OFF -> Player.REPEAT_MODE_OFF
    AudioRepeat.ALL -> Player.REPEAT_MODE_ALL
    AudioRepeat.ONE -> Player.REPEAT_MODE_ONE
}

fun repeatFromPlayerMode(mode: Int): AudioRepeat = when (mode) {
    Player.REPEAT_MODE_ALL -> AudioRepeat.ALL
    Player.REPEAT_MODE_ONE -> AudioRepeat.ONE
    else -> AudioRepeat.OFF
}

/** The play control shows intent: buffering still counts as playing, a finished or failed queue does not. */
fun wantsToPlay(playWhenReady: Boolean, playbackState: Int): Boolean =
    playWhenReady && playbackState != Player.STATE_IDLE && playbackState != Player.STATE_ENDED

/** Indices from [current] following [nextOf] (-1 / C.INDEX_UNSET ends it); stops on a cycle. */
fun playOrder(count: Int, current: Int, nextOf: (Int) -> Int): List<Int> {
    val out = ArrayList<Int>()
    var i = current
    while (i in 0 until count && out.size < count) { out += i; i = nextOf(i) }
    return out
}

/** What plays after [current] in [order]: the rest, plus the earlier entries again when repeating all. */
fun upNextOrder(order: List<Int>, current: Int, repeat: AudioRepeat): List<Int> {
    val pos = order.indexOf(current)
    if (pos < 0) return emptyList()
    return order.drop(pos + 1) + if (repeat == AudioRepeat.ALL) order.take(pos) else emptyList()
}

/** [queue] without the entries whose [id] is in [ids]. */
fun <T> queueWithout(queue: List<T>, ids: Set<String>, id: (T) -> String): List<T> = queue.filter { id(it) !in ids }

val AUDIO_SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
val SLEEP_MINUTES = listOf(15, 30, 45, 60)
const val SLEEP_END_OF_TRACK = -1

const val AUDIO_REMEMBER_OVER_MS = 20 * 60_000L
const val AUDIO_RESUME_EDGE_MS = 30_000L

/** Only long tracks with a known length are worth remembering. */
fun shouldRemember(durationMs: Long): Boolean = durationMs > AUDIO_REMEMBER_OVER_MS

/** Where to start a track saved at [savedMs]; 0 = from the start (not remembered, a peek, or finished). */
fun resumePositionMs(savedMs: Long, durationMs: Long): Long = when {
    !shouldRemember(durationMs) -> 0L
    savedMs < AUDIO_RESUME_EDGE_MS || durationMs - savedMs <= AUDIO_RESUME_EDGE_MS || savedMs * 100 >= durationMs * 97 -> 0L
    else -> savedMs
}

/** What a track's position means for its saved spot: keep what is saved (a peek), update it, or drop it (finished). */
enum class ResumeAction { KEEP, SAVE, FORGET }

/** A peek must not erase an earlier spot (the new track reports ~0 while its saved spot is still being looked up). */
fun resumeActionFor(positionMs: Long, durationMs: Long): ResumeAction = when {
    !shouldRemember(durationMs) -> ResumeAction.KEEP
    resumePositionMs(positionMs, durationMs) > 0 -> ResumeAction.SAVE
    positionMs < AUDIO_RESUME_EDGE_MS -> ResumeAction.KEEP
    else -> ResumeAction.FORGET
}

/** Position after skipping [deltaMs]: never below 0, and never past [durationMs] when it is known. */
fun skipTarget(positionMs: Long, deltaMs: Long, durationMs: Long): Long {
    val t = (positionMs + deltaMs).coerceAtLeast(0)
    return if (durationMs == androidx.media3.common.C.TIME_UNSET || durationMs < 0) t else t.coerceAtMost(durationMs)
}

/** A deliberate seek (or restart) to the start of a long track drops its saved spot; a save at ~0 after a track change is not one. */
fun seekForgetsSpot(targetMs: Long): Boolean = targetMs < AUDIO_RESUME_EDGE_MS

/** "End of track" belongs to the item it was set on: it only holds while that item is still the current one. */
fun sleepEndStillApplies(setForId: String?, currentId: String?): Boolean = setForId != null && setForId == currentId

/** "1x", "1.25x". */
fun speedLabel(speed: Float): String = (if (speed == speed.toInt().toFloat()) speed.toInt().toString() else speed.toString()) + "x"

/** Whole minutes to show for a running timer: rounds up, so never "0" while it is still active. */
fun sleepMinutesLeft(remainingMs: Long): Int = maxOf(1L, (remainingMs + 59_999) / 60_000).toInt()

/** "3:05" or "1:02:03"; negative and unknown (time unset) durations show as 0:00. */
fun formatClock(ms: Long): String {
    val s = (ms.coerceAtLeast(0) / 1000)
    val h = s / 3600
    val m = s % 3600 / 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s % 60) else "%d:%02d".format(m, s % 60)
}

/**
 * Background audio for downloaded music: one ExoPlayer that outlives the UI,
 * shown by [BackgroundPlaybackService] as a media notification. Video uses
 * its own player; [parkForVideo] pauses the queue and keeps it for when the video closes.
 */
@OptIn(UnstableApi::class, FlowPreview::class)
@Singleton
class AudioPlayer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val resume: AudioResume,
    private val youtubeResolver: YouTubeResolver,
    private val queueStore: AudioQueueStore,
    private val downloads: DownloadDao,
    private val history: PlayHistory,
    private val sponsorSegments: SponsorSegmentRepository,
    private val settings: com.abhinavxt.debforge.data.prefs.SettingsStore
) {

    private val _state = MutableStateFlow(AudioState())
    val state: StateFlow<AudioState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val playing = MutableStateFlow(false)
    private val queueChanged = MutableStateFlow(0)
    private val restoreLock = Mutex()
    /** Restored from disk and not yet prepared: nothing plays, no service runs, until the user acts ([activate]). */
    private var restoredPending = false
    private var activating = false
    private var activateJob: Job? = null
    /** The restored current track's length, shown while the unprepared player cannot report it. */
    private var restoredDurationId: String? = null
    private var restoredDurationMs = 0L
    private var player: ExoPlayer? = null

    /** SponsorBlock segments of the current YouTube track, loaded off the main thread; [sponsorId] is the track they belong to. */
    private var sponsorId: String? = null
    private var sponsorList: List<SponsorSegment> = emptyList()
    private val sponsorDone = HashSet<Long>()
    private var sponsorJob: Job? = null
    /** Uptime of a failed load of [sponsorId]'s segments (null = none or loaded); one retry is allowed later in the same item. */
    private var sponsorFailedAt: Long? = null
    private var sponsorRetried = false

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            publish()
            playing.value = player.isPlaying // position only moves while playing
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            // "End of track" has paused at the end of the item: the timer is spent.
            if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM && sleepAtEnd) cancelSleep()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            // Ran off the end of the queue: nothing is left for the timer to wait for.
            if (playbackState == Player.STATE_ENDED && sleepAtEnd) cancelSleep()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) youtubeBreaker.reset() else { saveCurrent(); savePositionNow() }
        }

        override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
            val p = player ?: return
            val id = tracks.getOrNull(old.mediaItemIndex)?.resumeId ?: return
            when {
                reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION -> {
                    scope.launch { resume.forget(id) } // finished
                    recordHistory(p, old.mediaItemIndex, old.positionMs, ended = true)
                }
                reason == Player.DISCONTINUITY_REASON_SEEK && old.mediaItemIndex != new.mediaItemIndex -> {
                    save(p, old.mediaItemIndex, old.positionMs)
                    recordHistory(p, old.mediaItemIndex, old.positionMs)
                }
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // Swapping the current item for its resolved copy fires a transient transition: not a change of track.
            val sameItem = reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED && mediaItem?.mediaId == lastTransitionId
            lastTransitionId = mediaItem?.mediaId
            if (replacing || sameItem) return
            savePositionNow()
            sponsorDone.clear() // a new item or a repeat-one restart skips afresh
            // Repeat-one keeps the same item, so the timer still applies; any other change of item drops it.
            if (sleepAtEnd && !sleepEndStillApplies(sleepItemId, mediaItem?.mediaId)) cancelSleep()
            resolveAhead()
            maybeAutoplay()
            val mark = handoffAfterTransition(handoffId, mediaItem?.mediaId, reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)
            handoffId = mark.next
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED || reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) return
            if (mark.skipResume) return // the video's own position wins
            applyResume(mediaItem?.mediaId ?: return)
        }

        override fun onPlayerError(error: PlaybackException) {
            // A file deleted while queued or playing: drop it and carry on with the next one.
            val p = player ?: return
            val bad = p.currentMediaItemIndex
            val yt = tracks.getOrNull(bad)
            // No connection: the stream did not fail, the network did. Keep it (and what follows) for later.
            if (yt != null && keepsOfflineOnError(yt.youtube != null || yt.isRemoteStream, isOnline())) { keepOffline(yt.id); return }
            // A streamed link that expired: look it up again once and carry on at the same second.
            val now = SystemClock.uptimeMillis()
            val last = recoverAttempts[yt?.id]
            val wait = if (yt?.youtube != null) recoverDelayMs(recoverCounts[yt.id] ?: 0, last, now) else null
            if (yt != null && wait != null) {
                val burst = if (last == null || now - last >= YT_RECOVER_GAP_MS) 0 else recoverCounts[yt.id] ?: 0
                recoverAttempts[yt.id] = now
                recoverCounts[yt.id] = burst + 1
                recoverJob?.cancel()
                val at = p.currentPosition
                recoverJob = scope.launch { delay(wait); if (!recover(yt, bad, at)) { dropTrack(bad, yt.id); resolveAhead() } }
                return
            }
            dropTrack(bad, yt?.id)
        }
    }

    private val relatedCache = RelatedCache()

    // Autoplay: when the last item of a streamed queue becomes current, append a related video (radio style).
    private var autoplayEnabled = true
    private val autoplayIds = HashSet<String>()
    /**
     * Video ids that played or were picked this session (not persisted), so autoplay does not loop back. Deliberately
     * not cleared with the queue: closing it and starting again should not repeat what was just heard.
     */
    private val playedVideos = HashSet<String>()
    private var autoplayJob: Job? = null
    private var autoplayFor: String? = null

    fun setAutoplay(on: Boolean) {
        autoplayEnabled = on
        scope.launch { settings.setYouTubeAutoplay(on) }
        publish()
        if (on) maybeAutoplay()
    }

    private fun isLastInOrder(p: ExoPlayer): Boolean {
        val tl = p.currentTimeline
        if (tl.windowCount != tracks.size) return false
        return tl.getNextWindowIndex(p.currentMediaItemIndex, Player.REPEAT_MODE_OFF, p.shuffleModeEnabled) == C.INDEX_UNSET
    }

    private fun maybeAutoplay() {
        val p = player ?: return
        if (restoredPending) return
        val track = tracks.getOrNull(p.currentMediaItemIndex) ?: return
        val yt = track.youtube
        if (yt != null) playedVideos.add(yt.videoId)
        if (!shouldAutoplay(isLastInOrder(p), yt != null, repeatFromPlayerMode(p.repeatMode), autoplayEnabled, sleepAtEnd)) return
        if (autoplayFor == track.id && autoplayJob?.isActive == true) return
        autoplayJob?.cancel()
        autoplayFor = track.id
        autoplayJob = scope.launch { appendAutoplay(track, p) }
    }

    private suspend fun appendAutoplay(track: AudioTrack, p: ExoPlayer) {
        val yt = track.youtube ?: return
        // Not cached yet: look the current item up again (shared with resolve-ahead and recovery) so its related list lands.
        val related = relatedCache.get(yt.videoId) ?: run { refreshed(track); relatedCache.get(yt.videoId) } ?: return
        // The queue may have moved on while looking up.
        if (player !== p || tracks.getOrNull(p.currentMediaItemIndex)?.id != track.id) return
        if (!shouldAutoplay(isLastInOrder(p), true, repeatFromPlayerMode(p.repeatMode), autoplayEnabled, sleepAtEnd)) return
        val queued = tracks.mapNotNullTo(HashSet()) { it.youtube?.videoId }
        val pick = pickAutoplayMarked(related, playedVideos, queued) ?: return
        val id = YouTubeLinks.videoId(pick.url) ?: return
        val outcome = enqueueYouTube(id, YouTubeMeta(pick.title, pick.channel, pick.durationSec.toInt()))
        if (outcome == EnqueueOutcome.ADDED) {
            tracks.lastOrNull()?.let { autoplayIds.add(it.id) }
            publish()
        }
    }

    /** The related videos of [videoId] from the last time the player resolved it (null if not among the last 20). For autoplay. */
    fun relatedFor(videoId: String): List<YouTubeSearchResult>? = relatedCache.get(videoId)

    /** Lookups that failed for good, in a row: three mean YouTube is refusing us, so stop dropping items. */
    private val youtubeBreaker = BrokenBreaker()
    private val skipToast = ToastCollapser()
    /** One lookup per video at a time, shared by resolve-ahead, error recovery and starting. */
    private val lookups = InFlight<String, Refresh>()
    /** True while [replaceKeepingOrder] swaps an item: the transition it fires is not a change of track. */
    private var replacing = false
    private var lastTransitionId: String? = null

    private val recoverAttempts = HashMap<String, Long>()
    private val recoverCounts = HashMap<String, Int>()
    private var recoverJob: Job? = null

    /** The outcome of looking a streamed track's link up again. */
    private sealed interface Refresh {
        class Fresh(val track: AudioTrack) : Refresh
        /** No connection: keep the track as it is. */
        object Offline : Refresh
        /** The video itself is gone (or has no audio): drop the track. */
        object Broken : Refresh
        /** Several lookups in a row failed: YouTube is blocking us. Playback is paused; keep everything. */
        object Blocked : Refresh
    }

    /** [track] with a fresh stream URL (and itag, when the old one is gone), or why it could not be looked up. */
    private suspend fun refreshed(track: AudioTrack): Refresh {
        val yt = track.youtube ?: return Refresh.Broken
        return lookups.run(yt.videoId, scope) { lookUp(track, yt) }
    }

    private suspend fun lookUp(track: AudioTrack, yt: YouTubeAudio): Refresh {
        val r = lookUpOnce(track, yt)
        when (r) {
            is Refresh.Fresh -> youtubeBreaker.reset()
            Refresh.Broken -> if (youtubeBreaker.recordBroken()) { blocked(); return Refresh.Blocked }
            else -> Unit
        }
        return r
    }

    /** YouTube refused several lookups in a row: pause once with one message and keep the queue. */
    private fun blocked() {
        player?.pause()
        publish()
        Toast.makeText(context, R.string.yt_not_responding, Toast.LENGTH_LONG).show()
    }

    private suspend fun lookUpOnce(track: AudioTrack, yt: YouTubeAudio): Refresh {
        val video = try {
            youtubeResolver.resolve("https://www.youtube.com/watch?v=${yt.videoId}")
        } catch (e: CancellationException) {
            throw e // play()/clear() replaced the queue: not a failure
        } catch (e: Exception) {
            return if (linkFailure(e, isOnline()) == LinkFailure.OFFLINE) Refresh.Offline else Refresh.Broken
        }
        relatedCache.put(yt.videoId, video.related)
        val stream = freshAudioStream(video, yt.audioItag, android.os.Build.VERSION.SDK_INT) ?: return Refresh.Broken
        return Refresh.Fresh(track.copy(uri = Uri.parse(stream.url), youtube = yt.copy(audioItag = stream.itag)))
    }

    private fun isOnline(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun showOffline() = Toast.makeText(context, R.string.yt_err_network, Toast.LENGTH_SHORT).show()

    /** A streamed track failed for want of a connection: stay on it, paused, and let Play try again later. */
    private fun keepOffline(id: String) {
        recoverJob?.cancel()
        recoverAttempts.remove(id); recoverCounts.remove(id) // not the link's fault: the next try starts afresh
        player?.pause()
        publish()
        showOffline()
    }

    /** Swaps in a fresh URL for [track] at [index] and resumes at [positionMs]; false when the track should be dropped. */
    private suspend fun recover(track: AudioTrack, index: Int, positionMs: Long): Boolean {
        val fresh = when (val r = refreshed(track)) {
            is Refresh.Fresh -> r.track
            Refresh.Offline -> { if (tracks.getOrNull(index)?.id == track.id) keepOffline(track.id); return true }
            Refresh.Broken -> return false
            Refresh.Blocked -> return true
        }
        val p = player ?: return true // cleared meanwhile: nothing to drop
        val at = tracks.indexOfFirst { it.id == track.id }
        if (at < 0) return true // the queue changed under us
        // Resolve-ahead may have put the same fresh link in meanwhile: replacing again would restart the track.
        if (tracks[at].uri != fresh.uri) {
            tracks = tracks.toMutableList().also { it[at] = fresh }
            replaceKeepingOrder(p, at, fresh)
            p.seekTo(at, if (at == index) positionMs else p.currentPosition)
            queueChanged.value++
        }
        p.prepare()
        p.play()
        return true
    }

    /** A file deleted while queued or playing: drop it and carry on with the next one. */
    private fun dropTrack(bad: Int, id: String?) {
        val p = player ?: return
        // The queue may have changed since the error: find the track again by id.
        val at = if (id == null) bad else tracks.indexOfFirst { it.id == id }
        if (at < 0) return
        val wasStreamed = tracks[at].youtube != null
        if (p.mediaItemCount <= 1) { clear(); if (wasStreamed) showSkipped(); return }
        val wasCurrent = at == p.currentMediaItemIndex
        tracks = tracks.filterIndexed { i, _ -> i != at }
        p.removeMediaItem(at)
        // The user may have moved on during the pause before a retry: an idle player still has to carry on.
        if (!restoredPending && (wasCurrent || p.playbackState == Player.STATE_IDLE)) {
            val resume = wasCurrent || p.playWhenReady
            p.prepare()
            if (resume) p.play()
        }
        queueChanged.value++
        if (wasStreamed) showSkipped()
    }

    private fun showSkipped() {
        if (skipToast.shouldShow(SystemClock.uptimeMillis())) Toast.makeText(context, R.string.yt_skipped_unavailable, Toast.LENGTH_SHORT).show()
    }

    /** Swaps the item at [index] for [track]; with shuffle on, the replace would put it at a random spot, so the old order is put back. */
    private fun replaceKeepingOrder(p: ExoPlayer, index: Int, track: AudioTrack) {
        val order = if (p.shuffleModeEnabled && p.mediaItemCount == tracks.size) shuffleOrderOf(p) else null
        replacing = true
        try {
            p.replaceMediaItem(index, track.toMediaItem())
            if (order != null && p.mediaItemCount == order.size) p.setShuffleOrder(DefaultShuffleOrder(order.toIntArray(), System.nanoTime()))
        } finally { replacing = false }
    }

    private var aheadJob: Job? = null
    private var aheadId: String? = null

    private fun cancelLookups() {
        aheadJob?.cancel(); aheadJob = null; aheadId = null
        autoplayJob?.cancel(); autoplayJob = null; autoplayFor = null; autoplayIds.clear()
        lookups.cancelAll()
    }

    /** Looks up the link of the next streamed item that has none, so it starts without an error; one at a time, as links expire. */
    private fun resolveAhead() {
        val p = player ?: return
        if (restoredPending) return
        val tl = p.currentTimeline
        val mode = if (p.repeatMode == Player.REPEAT_MODE_ALL) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
        val order = if (tl.windowCount == tracks.size) playOrder(tracks.size, p.currentMediaItemIndex) { tl.getNextWindowIndex(it, mode, p.shuffleModeEnabled) }
            else tracks.indices.toList().drop(p.currentMediaItemIndex)
        val track = nextToResolve(tracks, order)?.let { tracks[it] } ?: return
        if (aheadId == track.id && aheadJob?.isActive == true) return
        aheadJob?.cancel()
        aheadId = track.id
        aheadJob = scope.launch { applyAhead(track, p) }
    }

    private suspend fun applyAhead(track: AudioTrack, p: ExoPlayer): Refresh? {
        val r = refreshed(track)
        if (player !== p) return null
        val at = tracks.indexOfFirst { it.id == track.id }
        if (at < 0) return null
        when (r) {
            Refresh.Offline, Refresh.Blocked -> Unit // the error path keeps it for when the connection is back
            Refresh.Broken -> { dropTrack(at, track.id); resolveAhead() }
            is Refresh.Fresh -> if (tracks[at].uri != r.track.uri) {
                tracks = tracks.toMutableList().also { it[at] = r.track }
                if (at == p.currentMediaItemIndex) {
                    val pos = p.currentPosition // it became current while looking up: the replace resets it
                    replaceKeepingOrder(p, at, r.track)
                    p.seekTo(at, pos)
                    p.prepare()
                } else replaceKeepingOrder(p, at, r.track)
                queueChanged.value++
                publish()
            }
        }
        if (r is Refresh.Fresh) resolveAhead()
        return r
    }

    init {
        scope.launch { settings.youTubeAutoplayFlow.collect { autoplayEnabled = it; publish(); if (it) maybeAutoplay() } }
        // Save the position now and then while playing, so a killed process loses little.
        scope.launch { playing.collectLatest { if (it) while (true) { delay(15_000); saveSpot(); savePositionNow() } } }
        // Play history: the same cadence as the video player (about every 5 s while playing); pause and track changes record too.
        scope.launch { playing.collectLatest { if (it) while (true) { delay(5_000); recordCurrent() } } }
        // The queue itself, saved a moment after it stops changing.
        // SponsorBlock: independent of the UI publish loop so it also works with the screen off.
        scope.launch { playing.collectLatest { if (it) while (true) { sponsorTick(); delay(500) } } }
        scope.launch { queueChanged.debounce(500).collect { saveQueueNow() } }
        // Republish the position only while playing and somebody is watching.
        scope.launch {
            combine(_state.subscriptionCount.map { it > 0 }, playing) { watched, on -> watched && on }
                .distinctUntilChanged()
                .collectLatest { if (it) while (true) { publish(); delay(500) } }
        }
    }

    private fun sponsorTick() {
        val p = player ?: return
        if (restoredPending || p.playbackState != Player.STATE_READY) return
        val id = p.currentMediaItem?.mediaId ?: return
        if (sponsorId != id) { loadSponsor(id); return }
        val failed = sponsorFailedAt
        if (failed != null && AudioSponsorSkip.retryDue(failed, SystemClock.uptimeMillis(), sponsorRetried)) { sponsorRetried = true; loadSponsor(id, retry = true); return }
        if (sponsorList.isEmpty()) return
        when (val d = AudioSponsorSkip.decide(sponsorList, p.currentPosition, currentDurationMs(p), sponsorDone)) {
            AudioSkip.None -> {}
            is AudioSkip.Seek -> { sponsorDone += d.startMs; p.seekTo(d.toMs); publish() }
            is AudioSkip.EndTrack -> {
                // Seek to the end of the CURRENT item and let ExoPlayer finish it: the same auto transition, repeat,
                // pause-at-end (sleep timer) or STATE_ENDED as a natural end. Same-item seeks are not saved as a resume spot.
                sponsorDone += d.startMs
                p.seekTo(p.currentMediaItemIndex, AudioSponsorSkip.endSeekMs(currentDurationMs(p)))
            }
        }
    }

    /** Starts loading the segments of track [id]; an empty list stands for "none", so the load runs once per item. */
    private fun loadSponsor(id: String, retry: Boolean = false) {
        sponsorJob?.cancel()
        sponsorId = id
        sponsorFailedAt = null
        if (!retry) { sponsorList = emptyList(); sponsorDone.clear(); sponsorRetried = false }
        val track = tracks.firstOrNull { it.id == id } ?: return
        sponsorJob = scope.launch {
            val videoId = track.youtube?.videoId ?: withContext(Dispatchers.IO) {
                downloads.getById(id)?.sourceRef?.let { YouTubeLinks.parseSourceRef(it)?.videoId }
            } ?: return@launch
            val found = try {
                withContext(Dispatchers.IO) { sponsorSegments.segmentsFor(videoId) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (AudioSponsorSkip.loadStillCurrent(id, sponsorId)) {
                if (found == null) { sponsorFailedAt = SystemClock.uptimeMillis(); return@launch }
                sponsorList = SponsorSkip.normalise(found, 0)
            }
        }
    }

    private fun exo(): ExoPlayer = player ?: ExoPlayer.Builder(context)
        .setMediaSourceFactory(YouTubeMediaSourceFactory(context))
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
            true
        )
        .setHandleAudioBecomingNoisy(true)
        .setWakeMode(C.WAKE_MODE_LOCAL)
        .build()
        .also {
            // A downloaded video file plays here as sound only: no frames are decoded.
            it.trackSelectionParameters = it.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true).build()
            it.addListener(listener); player = it
        }

    private var playJob: Job? = null
    /** The item a video handed over at its own position: its saved audio spot must not override that. */
    private var handoffId: String? = null

    private fun setOpenIntent() {
        BackgroundPlayback.openIntent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(BackgroundPlayback.EXTRA_EXPAND_NOW_PLAYING, true)
    }

    /**
     * Plays [track] (the item a video was showing) from [startMs] at [speed]. The same item already current just seeks;
     * one already queued is jumped to; any other is inserted right after the current item (or starts a one-item
     * queue). A saved audio resume spot never overrides [startMs].
     */
    fun playHandoff(track: AudioTrack, startMs: Long, speed: Float) {
        playJob?.cancel()
        playJob = scope.launch {
            awaitRestore(parkRestoreJob) // a parked queue is still coming back: join it, do not replace it
            // A local download and the streamed copy of one YouTube video are the same item: match them by video id.
            val (videoId, localId) = handoffIdentity(track)
            fun planNow(): HandoffPlan {
                val cur = player
                return if (cur == null || tracks.isEmpty()) HandoffPlan.StartNew
                else handoffPlan(tracks, cur.currentMediaItemIndex, track, localId, videoId)
            }
            var t = track
            val failure = HandoffFailure(track, startMs, speed)
            handoffId = null
            if (planNow() !is HandoffPlan.JumpTo && !restoredPending && t.youtube != null && t.uri == Uri.EMPTY) {
                when (val r = refreshed(t)) {
                    is Refresh.Fresh -> t = r.track
                    Refresh.Offline -> { showOffline(); handoffFailed.tryEmit(failure); return@launch }
                    Refresh.Blocked -> { handoffFailed.tryEmit(failure); return@launch }
                    Refresh.Broken -> { showSkipped(); handoffFailed.tryEmit(failure); return@launch }
                }
            }
            when (val plan = planNow()) {
                HandoffPlan.StartNew -> {
                    start(listOf(t), 0, startMs.coerceAtLeast(0))
                    player?.setPlaybackSpeed(speed)
                    queueChanged.value++
                    return@launch
                }
                is HandoffPlan.JumpTo -> {
                    val p = player ?: return@launch
                    if (plan.index != p.currentMediaItemIndex) { handoffId = tracks[plan.index].id; p.seekTo(plan.index, startMs.coerceAtLeast(0)) }
                    else p.seekTo(startMs.coerceAtLeast(0))
                }
                is HandoffPlan.InsertAt -> {
                    val p = player ?: return@launch
                    handoffId = t.id
                    insertKeepingShuffle(p, plan.index, t, next = true)
                    p.seekTo(plan.index, startMs.coerceAtLeast(0))
                }
            }
            val p = player ?: return@launch
            p.setPlaybackSpeed(speed)
            if (restoredPending) {
                if (!activate()) { handoffFailed.tryEmit(failure); return@launch }
            } else {
                pauseBackgroundedVideo()
                setOpenIntent()
                BackgroundPlayback.player = p
                startPlaybackService()
                if (p.playbackState == Player.STATE_IDLE) p.prepare()
                p.play()
                resolveAhead()
            }
            queueChanged.value++
            publish()
        }
    }

    /**
     * A video is about to play: pause the audio queue and keep it. The player and the service are released, and the
     * queue comes back as a restored, paused one (see [restore]); play on the mini player resumes it.
     */
    fun parkForVideo() {
        val p = player ?: return
        when (parkAction(tracks.isEmpty(), restoredPending)) {
            ParkAction.NONE -> return
            ParkAction.CANCEL_ACTIVATION -> {
                // Already parked, perhaps mid-activation: it must not finish and take audio focus from the video.
                activateJob?.cancel()
                playJob?.cancel()
                pendingAction = null
                activating = false
                publish()
                return
            }
            ParkAction.PARK -> Unit
        }
        p.pause()
        saveCurrent()
        val snap = currentSnapshot(p) ?: return
        playJob?.cancel()
        release()
        parkRestoreJob = scope.launch { queueStore.saveQueue(snap); restore() }
    }

    /**
     * A video closed at [positionMs]. When it is the item the parked queue is on (same download, or the same YouTube
     * video whether streamed or downloaded), the queue continues from there; any other video leaves it as it was.
     */
    fun videoClosed(itemId: String, videoId: String?, positionMs: Long, durationMs: Long) {
        scope.launch {
            awaitRestore(parkRestoreJob)
            val p = player ?: return@launch
            if (!restoredPending) return@launch // audio is already playing: the user moved on
            val cur = tracks.getOrNull(p.currentMediaItemIndex) ?: return@launch
            val (curVideoId, _) = handoffIdentity(cur)
            val at = parkedPositionAfterVideo(videoMatchesTrack(cur.id, curVideoId, itemId, videoId), positionMs, durationMs) ?: return@launch
            if (player !== p || !restoredPending) return@launch
            p.seekTo(at)
            queueStore.savePosition(cur.id, at, currentDurationMs(p))
            publish()
        }
    }

    /** The restore of a parked queue, so a handoff arriving at once waits for it (see [playHandoff]). */
    private var parkRestoreJob: Job? = null

    /** A handoff that could not start playing: the video it came from is already closed, so the UI offers a way back. */
    val handoffFailed = MutableSharedFlow<HandoffFailure>(extraBufferCapacity = 1)

    /** The live playback position of the current item (the published state can lag a moment behind). */
    fun positionNow(): Long = player?.currentPosition ?: _state.value.positionMs

    /** Pauses the audio (a restored queue that is still activating included). */
    fun pause() {
        if (restoredPending) { if (activating) whenActive { it.pause() }; return }
        player?.pause()
    }

    /**
     * Replaces the queue and starts at [startIndex], where it was left if it is a remembered long track.
     * To stream a YouTube video's audio: `play(listOf(youtubeAudioTrack(video, audioStream)), 0)`.
     */
    fun play(queue: List<AudioTrack>, startIndex: Int) {
        if (queue.isEmpty()) return
        playJob?.cancel()
        playJob = scope.launch { start(queue, startIndex, resumeFor(queue[startIndex.coerceIn(queue.indices)].resumeId)) }
    }

    /**
     * Plays finished downloads from the one at [index]. Their files are looked up off the main thread (hundreds of
     * files in a picked folder take a while); files that are gone are skipped.
     */
    fun playDownloads(queue: List<DownloadEntity>, index: Int) {
        if (queue.isEmpty()) return
        val tappedId = queue.getOrNull(index)?.id
        playJob?.cancel()
        playJob = scope.launch {
            val tracks = withContext(Dispatchers.IO) { audioTracksFor(context, queue) }
            if (tracks.isEmpty()) return@launch
            val start = startIndexOf(tracks, tappedId)
            start(tracks, start, resumeFor(tracks[start].resumeId))
        }
    }

    /** Seeks the current item to where it was left, unless playback has already moved on or past the start. */
    private fun applyResume(id: String) {
        scope.launch {
            val at = resumeFor(resumeKeyOf(id))
            val p = player ?: return@launch
            if (at > 0 && p.currentMediaItem?.mediaId == id && p.currentPosition < AUDIO_RESUME_EDGE_MS) p.seekTo(at)
        }
    }

    private fun resumeKeyOf(mediaId: String): String = tracks.firstOrNull { it.id == mediaId }?.resumeId ?: mediaId

    private suspend fun resumeFor(id: String): Long =
        resume.get(id)?.let { resumePositionMs(it.positionMs, it.durationMs) } ?: 0L

    private fun start(queue: List<AudioTrack>, startIndex: Int, startMs: Long) {
        saveCurrent() // the queue being replaced
        val p = exo()
        cancelSleep() // "End of track" was for a track of the old queue
        recoverJob?.cancel()
        cancelLookups()
        lastTransitionId = null
        recoverAttempts.clear(); recoverCounts.clear()
        activateJob?.cancel()
        pendingAction = null
        restoredPending = false
        activating = false
        pauseBackgroundedVideo()
        tracks = queue
        p.setMediaItems(queue.map { it.toMediaItem() }, startIndex.coerceIn(queue.indices), startMs)
        p.prepare()
        p.play()
        // Hand over before the first publish so the session exists when playback starts.
        BackgroundPlayback.openIntent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(BackgroundPlayback.EXTRA_EXPAND_NOW_PLAYING, true)
        BackgroundPlayback.player = p
        // Always: the service may have stopped itself (task removed) while the player lived on.
        startPlaybackService()
        publish()
        queueChanged.value++
        resolveAhead()
    }

    /**
     * Replaces the queue with downloaded tracks and YouTube items (streamed, unless a completed download of the video
     * exists: then that file plays), starting at [startIndex]. A first item that cannot be looked up is skipped.
     */
    fun playMixed(entries: List<MixedEntry>, startIndex: Int) {
        if (entries.isEmpty()) return
        playJob?.cancel()
        playJob = scope.launch {
            val local = localDownloadsFor(entries)
            var queue = entries.map { buildTrack(it, local) }.distinctBy { it.id }
            val startId = buildTrack(entries[startIndex.coerceIn(entries.indices)], local).id
            var at = queue.indexOfFirst { it.id == startId }.coerceAtLeast(0)
            val first = queue[at]
            if (first.youtube != null && first.uri == Uri.EMPTY) {
                when (val r = refreshed(first)) {
                    is Refresh.Fresh -> queue = queue.toMutableList().also { it[at] = r.track }
                    Refresh.Offline -> Unit // starts and keeps itself for later, see onPlayerError
                    Refresh.Blocked -> return@launch // YouTube is refusing us: said so already, nothing starts
                    Refresh.Broken -> {
                        showSkipped()
                        queue = queue.filterIndexed { i, _ -> i != at }
                        if (queue.isEmpty()) return@launch
                        at = at.coerceAtMost(queue.lastIndex)
                    }
                }
            }
            start(queue, at, resumeFor(queue[at].resumeId))
        }
    }

    /** The video ids of [entries] that have a completed download on disk, mapped to the track that plays it. */
    private suspend fun localDownloadsFor(entries: List<MixedEntry>): Map<String, AudioTrack> =
        localDownloadsForIds(entries.filterIsInstance<MixedEntry.YouTube>().mapTo(HashSet()) { it.videoId })

    /** The YouTube video id of [track] (streamed, or a download of one) and the id of its preferred local download, if any. */
    private suspend fun handoffIdentity(track: AudioTrack): Pair<String?, String?> {
        val videoId = track.youtube?.videoId ?: withContext(Dispatchers.IO) {
            downloads.getById(track.id)?.let { YouTubeLinks.parseSourceRef(it.sourceRef)?.videoId }
        } ?: return null to null
        val local = if (track.youtube != null) localDownloadsForIds(setOf(videoId))[videoId]?.id else null
        return videoId to local
    }

    private suspend fun localDownloadsForIds(wanted: Set<String>): Map<String, AudioTrack> {
        if (wanted.isEmpty()) return emptyMap()
        return withContext(Dispatchers.IO) {
            val rows = downloads.completed().filter { row ->
                YouTubeLinks.parseSourceRef(row.sourceRef)?.videoId in wanted && row.finalFilePath?.let { LocalFiles.exists(it) } == true
            }
            val byId = rows.associateBy { it.id }
            preferredLocalDownloads(rows.map { YouTubeDownloadRow(it.id, it.sourceRef) }).mapNotNull { (video, id) ->
                byId[id]?.let { audioTrackFor(context, it) }?.let { video to it }
            }.toMap()
        }
    }

    private fun buildTrack(e: MixedEntry, local: Map<String, AudioTrack>): AudioTrack = when (e) {
        is MixedEntry.Local -> e.track
        is MixedEntry.YouTube -> local[e.videoId] ?: lazyYouTubeTrack(e.videoId, e.meta.copy(title = decodeEntities(e.meta.title)))
    }

    /** Adds a YouTube video to the end of the queue (starts it when nothing is queued); plays the downloaded file if there is one. */
    suspend fun enqueueYouTube(videoId: String, meta: YouTubeMeta): EnqueueOutcome = addYouTube(videoId, meta, next = false)

    /** Like [enqueueYouTube], but right after the current item. */
    suspend fun enqueueYouTubeNext(videoId: String, meta: YouTubeMeta): EnqueueOutcome = addYouTube(videoId, meta, next = true)

    /**
     * Inserts [track] at queue index [at]. Under shuffle the player puts it at a random spot in the play order, so it
     * is placed right after the current item ([next]) or at the end of the play order instead.
     */
    private fun insertKeepingShuffle(p: ExoPlayer, at: Int, track: AudioTrack, next: Boolean) {
        val current = p.currentMediaItemIndex
        val order = if (p.shuffleModeEnabled && p.mediaItemCount == tracks.size) shuffleOrderOf(p) else null
        tracks = tracks.toMutableList().also { it.add(at, track) }
        p.addMediaItem(at, track.toMediaItem())
        if (order != null && p.mediaItemCount == order.size + 1) {
            val fixed = if (next) orderWithPlayNext(order, current, at) else orderWithAppend(order, at)
            p.setShuffleOrder(DefaultShuffleOrder(fixed.toIntArray(), System.nanoTime()))
        }
    }

    private suspend fun addYouTube(videoId: String, meta: YouTubeMeta, next: Boolean): EnqueueOutcome {
        val entry = MixedEntry.YouTube(videoId, meta)
        val track = buildTrack(entry, localDownloadsFor(listOf(entry)))
        return withContext(Dispatchers.Main.immediate) {
            val p = player
            if (p == null || tracks.isEmpty()) { playMixed(listOf(MixedEntry.Local(track)), 0); return@withContext EnqueueOutcome.STARTED }
            if (youTubeAlreadyQueued(tracks.map { it.id }, videoId, track.id)) return@withContext EnqueueOutcome.ALREADY_QUEUED
            val at = if (next) (p.currentMediaItemIndex + 1).coerceAtMost(tracks.size) else tracks.size
            insertKeepingShuffle(p, at, track, next)
            queueChanged.value++
            publish()
            resolveAhead()
            EnqueueOutcome.ADDED
        }
    }

    /**
     * Brings the saved queue back after the app was closed: paused at the same track and second, with shuffle,
     * repeat and speed. The player is not prepared and no service starts until the user plays (see [activate]).
     * False when something is already loaded or nothing was saved.
     */
    suspend fun restore(): Boolean = restoreLock.withLock {
        if (player != null) return@withLock false
        val snap = queueStore.load() ?: return@withLock false
        val local = withContext(Dispatchers.IO) {
            snap.queue.tracks.filter { it.local }.mapNotNull { t ->
                val row = downloads.getById(t.id) ?: return@mapNotNull null
                if (row.state != DownloadState.COMPLETED || row.finalFilePath?.let { LocalFiles.exists(it) } != true) return@mapNotNull null
                audioTrackFor(context, row)?.let { t.id to it }
            }.toMap()
        }
        val plan = restorePlan(snap) { it in local }
        if (player != null) return@withLock false // play() got in while we were reading
        if (plan == null) { queueStore.forget(); return@withLock false }
        val restored = plan.tracks.mapNotNull { t ->
            if (t.local) local[t.id] else {
                val video = t.videoId
                val itag = t.itag
                val url = t.streamUrl
                if (video == null && url != null) AudioTrack(t.id, Uri.parse(url), decodeEntities(t.title), t.artist, null, t.artworkUrl) // a podcast episode
                else if (video == null || itag == null) null
                else AudioTrack(t.id, t.streamUrl?.let { Uri.parse(it) } ?: Uri.EMPTY, decodeEntities(t.title), t.artist, null, t.artworkUrl,
                    YouTubeAudio(video, itag), t.artistUrl)
            }
        }
        if (restored.size != plan.tracks.size) return@withLock false
        val p = exo()
        tracks = restored
        // Before setMediaItems: its transition saves the position, which must already know the duration.
        restoredPending = true
        restoredDurationId = null
        restoredDurationMs = 0L
        if (restored[plan.index].id == snap.position.curId) { restoredDurationId = snap.position.curId; restoredDurationMs = snap.position.durationMs }
        p.setMediaItems(restored.map { it.toMediaItem() }, plan.index, plan.posMs)
        if (snap.queue.shuffle) {
            // Same order as before the restart; unusable when tracks were dropped (the saved indices no longer line up).
            validShuffleOrder(snap.queue.shuffleOrder, snap.queue.tracks.size)?.takeIf { plan.tracks.size == snap.queue.tracks.size }
                ?.let { p.setShuffleOrder(DefaultShuffleOrder(it.toIntArray(), System.nanoTime())) }
        }
        p.shuffleModeEnabled = snap.queue.shuffle
        p.repeatMode = snap.queue.repeat.toPlayerMode()
        p.setPlaybackSpeed(snap.queue.speed)
        p.playWhenReady = false
        publish()
        true
    }

    /**
     * First user action on a restored queue: re-resolve a streamed current track (its link has expired), hand the player
     * to the service and start. False when it could not start (the app may not start a service from the background).
     */
    private suspend fun activate(fromWidget: Boolean = false): Boolean {
        val p = player ?: return false
        if (!restoredPending) return true
        activating = true // the play button flips at once, even while the link is looked up
        publish()
        try {
            val at = p.currentMediaItemIndex
            val track = tracks.getOrNull(at)
            var failed = false
            if (track?.youtube != null) {
                val r = refreshed(track)
                if (player !== p) return false
                if (r === Refresh.Blocked) return false // paused with a message already; the queue stays as it is
                if (r === Refresh.Offline) {
                    // Keep the restored queue as it is for when the connection is back; the widget opens the app instead.
                    if (!fromWidget) showOffline()
                    return false
                }
                val fresh = (r as? Refresh.Fresh)?.track
                if (fresh == null) failed = true
                else if (tracks.getOrNull(at)?.id == track.id) {
                    tracks = tracks.toMutableList().also { it[at] = fresh }
                    val pos = p.currentPosition // the replace resets it when the item cannot update in place
                    replaceKeepingOrder(p, at, fresh)
                    p.seekTo(at, pos)
                    queueChanged.value++
                }
            }
            if (failed && track != null) {
                // No service for a track that cannot play (from a widget it would be a foreground start, owed a
                // notification). The widget keeps the queue and offers to open the app; in the app it is dropped.
                if (fromWidget) return false
                if (p.mediaItemCount <= 1) { clear(); return false }
            }
            pauseBackgroundedVideo()
            BackgroundPlayback.openIntent = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(BackgroundPlayback.EXTRA_EXPAND_NOW_PLAYING, true)
            BackgroundPlayback.player = p
            if (!startPlaybackService()) {
                BackgroundPlayback.player = null
                BackgroundPlayback.openIntent = null
                return false
            }
            restoredPending = false
            if (failed && track != null) { dropTrack(at, track.id); return false }
            p.prepare()
            p.play()
            resolveAhead()
            maybeAutoplay() // a restored queue may end on its last item
            return true
        } finally {
            activating = false
            publish()
        }
    }

    /**
     * Starts the playback service. A plain start is refused from the background (a cold process answering a widget
     * tap), but a foreground start is exempt when the user tapped a widget; the service then promotes itself once playing.
     */
    private fun startPlaybackService(): Boolean = try {
        BackgroundPlayback.start(context)
        true
    } catch (e: IllegalStateException) {
        try {
            BackgroundPlayback.startForeground(context)
            true
        } catch (e: IllegalStateException) { false } // not allowed from the background at all
    }

    /** Runs [block] on the player; the first action on a restored queue starts it first (see [activate]). */
    private fun whenActive(block: (ExoPlayer) -> Unit) {
        val p = player ?: return
        if (!restoredPending) { block(p); return }
        pendingAction = block // taps while activating: the latest one wins and runs once it is done
        if (activateJob?.isActive == true) return
        activateJob = scope.launch {
            val ok = activate()
            val action = pendingAction
            pendingAction = null
            if (ok && action != null) player?.let(action)
        }
    }

    private var pendingAction: ((ExoPlayer) -> Unit)? = null

    private var tracks: List<AudioTrack> = emptyList()

    // The sleep timer lives here, with the player, so it outlasts the screen; it is not persisted.
    private var sleepJob: Job? = null
    private var sleepDeadline = 0L
    private var sleepAtEnd = false
    private var sleepItemId: String? = null

    fun setSpeed(speed: Float) { player?.setPlaybackSpeed(speed); queueChanged.value++ }

    /** [minutes] 0 = off, [SLEEP_END_OF_TRACK], or a countdown; pauses (keeps the queue) when it fires. */
    fun setSleepTimer(minutes: Int) {
        val p = player ?: return
        cancelSleep()
        if (minutes == SLEEP_END_OF_TRACK) { sleepAtEnd = true; sleepItemId = p.currentMediaItem?.mediaId; p.pauseAtEndOfMediaItems = true }
        else if (minutes > 0) {
            val ms = minutes * 60_000L
            sleepDeadline = SystemClock.uptimeMillis() + ms // uptime, like delay() on the main dispatcher
            sleepJob = scope.launch {
                // Republish on a clock so the "N min" label counts down while paused too (publishing otherwise follows playback).
                var left = ms
                while (left > 0) {
                    val step = minOf(left, 30_000L)
                    delay(step)
                    left -= step
                    if (left > 0) publish()
                }
                p.pause(); cancelSleep()
            }
        }
        maybeAutoplay() // turning "End of track" off while on the last item
        publish()
    }

    private fun cancelSleep() {
        sleepJob?.cancel()
        sleepJob = null
        sleepAtEnd = false
        sleepItemId = null
        player?.pauseAtEndOfMediaItems = false
        publish()
    }

    /** Emits once the saved queue is gone from disk, so a widget showing it from there can update. */
    val queueForgotten = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** True when this process has a player (playing, or a restored queue waiting for a tap). */
    val isActive: Boolean get() = player != null

    /**
     * A home-screen widget button: restores the saved queue when the process is new, then does [action].
     * False when nothing could be played (no saved queue, or Android refused the service start, see [activate]).
     */
    suspend fun playFromWidget(action: WidgetAction): Boolean = withContext(Dispatchers.Main.immediate) {
        restore()
        val p = player ?: return@withContext false
        var activated = false
        if (restoredPending) {
            val running = activateJob?.takeIf { it.isActive }
            if (running != null) running.join()
            if (restoredPending) {
                if (!activate(fromWidget = true)) return@withContext false
                activated = true
            } else if (action == WidgetAction.TOGGLE) {
                // An in-app tap was already activating: that started playback, toggling again would pause it.
                return@withContext playbackTookHold(p)
            }
            activated = true
            if (action == WidgetAction.TOGGLE) return@withContext playbackTookHold(p) // activating started playback
        }
        when (action) {
            WidgetAction.TOGGLE -> toggle()
            WidgetAction.NEXT -> next()
            WidgetAction.PREVIOUS -> previous()
        }
        !activated || playbackTookHold(p)
    }

    /**
     * After a widget-started activation: playback is really going within ~2 s. A refusal later in the
     * service's foreground start would leave the player idle or paused; then it is paused and false returned.
     */
    private suspend fun playbackTookHold(p: ExoPlayer): Boolean {
        repeat(20) {
            if (player !== p) return false
            if (p.playWhenReady && (p.playbackState == Player.STATE_READY || p.playbackState == Player.STATE_BUFFERING)) return true
            delay(100)
        }
        if (player === p) p.pause()
        return false
    }

    fun toggle() {
        if (restoredPending) { // activating starts playback, so a tap while it does means pause
            if (activating) whenActive { it.pause() } else whenActive { }
            return
        }
        player?.let {
            if (wantsToPlay(it.playWhenReady, it.playbackState)) it.pause()
            else {
                youtubeBreaker.reset() // an explicit press: try afresh after the breaker tripped
                val cur = tracks.getOrNull(it.currentMediaItemIndex)
                if (cur?.youtube != null && cur.uri == Uri.EMPTY) {
                    // Never resolved (the breaker stopped it): look it up, then prepare and play in this one press.
                    it.playWhenReady = true
                    scope.launch {
                        val r = applyAhead(cur, it)
                        if (player !== it) return@launch
                        if (tracks.getOrNull(it.currentMediaItemIndex)?.uri != Uri.EMPTY) {
                            if (it.playbackState == Player.STATE_IDLE) it.prepare()
                            it.play()
                        } else {
                            // Nothing to play (offline, blocked, or gone): do not leave the UI showing playing while idle.
                            it.playWhenReady = false
                            if (r === Refresh.Offline) showOffline() // Blocked already said so
                            publish()
                        }
                    }
                    return
                }
                if (it.playbackState == Player.STATE_IDLE) it.prepare() // stopped by an error (kept while offline): try again
                it.play()
            }
        }
    }
    fun next() { whenActive { it.seekToNextMediaItem() } }
    /** Past 3 seconds restarts the track, like every music player. */
    fun previous() = whenActive { p ->
        if (p.currentPosition > p.maxSeekToPreviousPosition) forgetCurrentSpot() // restarts this track
        p.seekToPrevious()
    }
    fun seekTo(ms: Long) {
        if (seekForgetsSpot(ms)) forgetCurrentSpot()
        whenActive { it.seekTo(ms); publish() }
    }
    /** Skips [deltaMs] from the current position, staying inside the track. */
    fun seekBy(deltaMs: Long) {
        val p = player ?: return
        seekTo(skipTarget(p.currentPosition, deltaMs, p.duration))
    }

    /** The user went back to the start of this track on purpose, so a pause within the first seconds must not bring the old spot back. */
    private fun forgetCurrentSpot() {
        val id = player?.let { tracks.getOrNull(it.currentMediaItemIndex)?.resumeId } ?: return
        scope.launch { resume.forget(id) }
    }
    fun setShuffle(on: Boolean) { player?.shuffleModeEnabled = on; queueChanged.value++; resolveAhead() }
    fun cycleRepeat() { player?.let { it.repeatMode = repeatFromPlayerMode(it.repeatMode).next().toPlayerMode(); queueChanged.value++; resolveAhead(); maybeAutoplay() } }

    /** Plays [index] of the current queue (tapping "Up next"). */
    fun skipTo(index: Int) = whenActive { it.seekToDefaultPosition(index); it.play() }

    /**
     * Adds [track] to the end of the queue (starts it when nothing is playing or loaded). A track already queued
     * is left where it is. Returns what happened.
     */
    fun enqueue(track: AudioTrack): EnqueueOutcome {
        val p = player
        if (p == null || tracks.isEmpty()) { play(listOf(track), 0); return EnqueueOutcome.STARTED }
        if (tracks.any { it.id == track.id }) return EnqueueOutcome.ALREADY_QUEUED
        insertKeepingShuffle(p, tracks.size, track, next = false)
        queueChanged.value++
        publish()
        return EnqueueOutcome.ADDED
    }

    /** Files were deleted: take them out of the queue, moving on if the current one went, and forget where they were left. */
    fun removeTracks(ids: Set<String>) {
        removeFromQueue(ids)
        scope.launch { resume.forgetAll(ids) } // after any save the removal itself triggered
    }

    private fun removeFromQueue(ids: Set<String>) {
        val p = player ?: return
        val gone = tracks.indices.filter { tracks[it].id in ids }
        if (gone.isEmpty()) return
        if (gone.size == tracks.size) { clear(); return }
        val before = p.currentMediaItem?.mediaId
        tracks = queueWithout(tracks, ids) { it.id }
        gone.asReversed().forEach { p.removeMediaItem(it) }
        if (p.playbackState == Player.STATE_IDLE && !restoredPending) { p.prepare(); p.play() }
        // The removal's own transition is skipped (it also fires for a new queue), so start the next track where it was left.
        p.currentMediaItem?.mediaId?.takeIf { it != before }?.let { applyResume(it) }
        queueChanged.value++
        publish()
    }

    private fun currentDurationMs(p: Player): Long =
        p.duration.takeIf { it != C.TIME_UNSET }
            ?: restoredDurationMs.takeIf { restoredPending && restoredDurationId == tracks.getOrNull(p.currentMediaItemIndex)?.id }
            ?: 0L

    /** Remembers the current track and second for the restore after a restart. */
    private fun savePositionNow() {
        val p = player ?: return
        val id = tracks.getOrNull(p.currentMediaItemIndex)?.id ?: return
        val pos = p.currentPosition
        val dur = currentDurationMs(p)
        scope.launch { queueStore.savePosition(id, pos, dur) }
    }

    private fun saveQueueNow() {
        val p = player ?: return
        currentSnapshot(p)?.let { snap -> scope.launch { queueStore.saveQueue(snap) } }
    }

    private fun currentSnapshot(p: ExoPlayer): Snapshot? {
        if (tracks.isEmpty()) return null
        val queue = QueueSnapshot(
            tracks.map { it.toSaved() }, p.shuffleModeEnabled, repeatFromPlayerMode(p.repeatMode), p.playbackParameters.speed,
            shuffleOrder = if (p.shuffleModeEnabled) shuffleOrderOf(p) else null
        )
        val position = SavedPosition(tracks.getOrNull(p.currentMediaItemIndex)?.id, p.currentPosition, currentDurationMs(p))
        return Snapshot(queue, position)
    }

    /** The play order with shuffle on, read from the timeline (the player does not expose its ShuffleOrder). */
    private fun shuffleOrderOf(p: Player): List<Int>? {
        val t = p.currentTimeline
        if (t.windowCount != tracks.size) return null
        val order = ArrayList<Int>(t.windowCount)
        var i = t.getFirstWindowIndex(true)
        while (i != C.INDEX_UNSET && order.size <= t.windowCount) {
            order += i
            i = t.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, true)
        }
        return validShuffleOrder(order, t.windowCount)
    }

    private fun AudioTrack.toSaved() = SavedTrack(
        id = id, title = title, artist = artist, local = youtube == null && !isRemoteStream,
        videoId = youtube?.videoId, itag = youtube?.audioItag,
        streamUrl = if (youtube != null || isRemoteStream) uri.toString() else null,
        artworkUrl = if (youtube != null || isRemoteStream) artworkUrl else null,
        artistUrl = artistUrl
    )

    private fun saveCurrent() { saveSpot(); recordCurrent() }

    private fun saveSpot() { player?.let { save(it, it.currentMediaItemIndex, it.currentPosition) } }

    /** Only a track that played this session: a restored queue nobody has started is not history. */
    private fun recordCurrent() { if (restoredPending) return; player?.let { recordHistory(it, it.currentMediaItemIndex, it.currentPosition) } }

    /** Adds the track at [index] to the play history (skipped under 5 s unless [ended]; nothing while history is paused). */
    private fun recordHistory(p: Player, index: Int, positionMs: Long, ended: Boolean = false) {
        val track = tracks.getOrNull(index) ?: return
        val timeline = p.currentTimeline
        val windowMs = if (index < timeline.windowCount) timeline.getWindow(index, Timeline.Window()).durationMs else C.TIME_UNSET
        val duration = if (windowMs != C.TIME_UNSET) windowMs
            else if (index == p.currentMediaItemIndex) currentDurationMs(p) else 0L
        audioHistoryEntry(track, positionMs, duration, ended, System.currentTimeMillis())?.let(history::record)
    }

    /** Remembers a long track's spot; a finished track forgets it, a peek leaves an earlier spot alone. */
    private fun save(p: Player, index: Int, positionMs: Long) {
        val id = tracks.getOrNull(index)?.resumeId ?: return
        val duration = if (index < p.currentTimeline.windowCount) p.currentTimeline.getWindow(index, Timeline.Window()).durationMs else C.TIME_UNSET
        if (duration == C.TIME_UNSET || !shouldRemember(duration)) return
        when (resumeActionFor(positionMs, duration)) {
            ResumeAction.SAVE -> scope.launch { resume.save(id, positionMs, duration) }
            ResumeAction.FORGET -> scope.launch { resume.forget(id) }
            ResumeAction.KEEP -> Unit
        }
    }

    /** Stops, releases the player and the notification. */
    fun clear() {
        saveCurrent()
        scope.launch { queueStore.forget(); queueForgotten.tryEmit(Unit) } // closing the queue forgets it (a video only parks it, see parkForVideo)
        release()
    }

    /** Lets go of the player, the service and the queue in memory; what is saved on disk stays as it is. */
    private fun release() {
        recoverJob?.cancel()
        cancelLookups()
        lastTransitionId = null
        handoffId = null
        activateJob?.cancel()
        pendingAction = null
        restoredPending = false
        activating = false
        playing.value = false
        BackgroundPlayback.openIntent = null // only audio sets it; a later video must open itself
        sponsorJob?.cancel(); sponsorId = null; sponsorList = emptyList(); sponsorDone.clear()
        sponsorFailedAt = null; sponsorRetried = false
        val p = player ?: return
        cancelSleep()
        player = null
        if (BackgroundPlayback.player === p) {
            BackgroundPlayback.player = null
            BackgroundPlayback.stop(context)
        }
        p.removeListener(listener)
        p.release()
        tracks = emptyList()
        _state.value = AudioState()
    }

    /** A video left playing in the background owns the service; pause it and take over. */
    private fun pauseBackgroundedVideo() {
        val other = BackgroundPlayback.player
        if (other != null && other !== player) {
            other.pause()
            BackgroundPlayback.player = null
            BackgroundPlayback.stop(context)
        }
    }

    private fun publish() {
        val p = player ?: return
        _state.value = AudioState(
            queue = tracks,
            index = p.currentMediaItemIndex,
            isPlaying = activating || wantsToPlay(p.playWhenReady, p.playbackState),
            positionMs = p.currentPosition,
            durationMs = currentDurationMs(p),
            shuffle = p.shuffleModeEnabled,
            repeat = repeatFromPlayerMode(p.repeatMode),
            hasNext = p.hasNextMediaItem(),
            hasPrevious = p.hasPreviousMediaItem(),
            speed = p.playbackParameters.speed,
            sleepLeftMs = if (sleepJob != null) (sleepDeadline - SystemClock.uptimeMillis()).coerceAtLeast(0) else null,
            sleepAtEnd = sleepAtEnd,
            autoplayIds = autoplayIds.filterTo(HashSet()) { id -> tracks.any { it.id == id } },
            autoplayOn = autoplayEnabled,
            order = if (p.shuffleModeEnabled && p.mediaItemCount == tracks.size) {
                val tl = p.currentTimeline
                playOrder(tracks.size, p.currentMediaItemIndex) { tl.getNextWindowIndex(it, if (p.repeatMode == Player.REPEAT_MODE_ALL) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF, true) }
            } else tracks.indices.toList()
        )
    }

    private fun AudioTrack.toMediaItem() = MediaItem.Builder()
        .setMediaId(id)
        .setUri(uri)
        .apply { if (youtube != null) setTag(YouTubeStreamTag(uri.toString(), hasVideo = false)) }
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setArtworkUri(artworkFile?.takeIf { it.exists() }?.toUri() ?: artworkUrl?.let { Uri.parse(it) })
                .build()
        )
        .build()
}
