package com.abhinavxt.debforge.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
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
import com.abhinavxt.debforge.data.local.DownloadDao
import com.abhinavxt.debforge.data.history.PlayHistory
import com.abhinavxt.debforge.data.playback.AudioQueueStore
import com.abhinavxt.debforge.data.playback.AudioResume
import com.abhinavxt.debforge.data.playback.QueueSnapshot
import com.abhinavxt.debforge.data.playback.SavedPosition
import com.abhinavxt.debforge.data.playback.SavedTrack
import com.abhinavxt.debforge.data.playback.Snapshot
import com.abhinavxt.debforge.data.playback.restorePlan
import com.abhinavxt.debforge.data.playback.validShuffleOrder
import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder
import com.abhinavxt.debforge.domain.DownloadState
import com.abhinavxt.debforge.download.LocalFiles
import com.abhinavxt.debforge.data.youtube.YouTubeResolver
import com.abhinavxt.debforge.domain.YouTubeFormats
import com.abhinavxt.debforge.domain.YouTubeLinks
import com.abhinavxt.debforge.domain.YouTubeStream
import com.abhinavxt.debforge.domain.YouTubeVideo
import com.abhinavxt.debforge.player.youtube.YouTubeMediaSourceFactory
import com.abhinavxt.debforge.ui.history.audioHistoryEntry
import com.abhinavxt.debforge.player.youtube.YouTubeStreamTag
import com.abhinavxt.debforge.player.youtube.YouTubeStreaming
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.FlowPreview
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
    val artistUrl: String? = null
)

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
    val sleepAtEnd: Boolean = false
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
 * its own player; [stopForVideo] makes room for it.
 */
@OptIn(UnstableApi::class, FlowPreview::class)
@Singleton
class AudioPlayer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val resume: AudioResume,
    private val youtubeResolver: YouTubeResolver,
    private val queueStore: AudioQueueStore,
    private val downloads: DownloadDao,
    private val history: PlayHistory
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

        override fun onIsPlayingChanged(isPlaying: Boolean) { if (!isPlaying) { saveCurrent(); savePositionNow() } }

        override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
            val p = player ?: return
            val id = tracks.getOrNull(old.mediaItemIndex)?.id ?: return
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
            savePositionNow()
            // Repeat-one keeps the same item, so the timer still applies; any other change of item drops it.
            if (sleepAtEnd && !sleepEndStillApplies(sleepItemId, mediaItem?.mediaId)) cancelSleep()
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED || reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) return
            applyResume(mediaItem?.mediaId ?: return)
        }

        override fun onPlayerError(error: PlaybackException) {
            // A file deleted while queued or playing: drop it and carry on with the next one.
            val p = player ?: return
            val bad = p.currentMediaItemIndex
            val yt = tracks.getOrNull(bad)
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
                recoverJob = scope.launch { delay(wait); if (!recover(yt, bad, at)) dropTrack(bad, yt.id) }
                return
            }
            dropTrack(bad, yt?.id)
        }
    }

    private val recoverAttempts = HashMap<String, Long>()
    private val recoverCounts = HashMap<String, Int>()
    private var recoverJob: Job? = null

    /** [track] with a fresh stream URL (and itag, when the old one is gone), or null when it could not be looked up. */
    private suspend fun refreshed(track: AudioTrack): AudioTrack? {
        val yt = track.youtube ?: return null
        val stream = try {
            freshAudioStream(youtubeResolver.resolve("https://www.youtube.com/watch?v=${yt.videoId}"), yt.audioItag, android.os.Build.VERSION.SDK_INT)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // play()/clear() replaced the queue: not a failure
        } catch (e: Exception) {
            null
        } ?: return null
        return track.copy(uri = Uri.parse(stream.url), youtube = yt.copy(audioItag = stream.itag))
    }

    /** Swaps in a fresh URL for [track] at [index] and resumes at [positionMs]; false when it could not be refreshed. */
    private suspend fun recover(track: AudioTrack, index: Int, positionMs: Long): Boolean {
        val fresh = refreshed(track) ?: return false
        val p = player ?: return true // cleared meanwhile: nothing to drop
        if (tracks.getOrNull(index)?.id != track.id) return true // the queue changed under us
        tracks = tracks.toMutableList().also { it[index] = fresh }
        p.replaceMediaItem(index, fresh.toMediaItem())
        p.seekTo(index, positionMs)
        p.prepare()
        p.play()
        queueChanged.value++
        return true
    }

    /** A file deleted while queued or playing: drop it and carry on with the next one. */
    private fun dropTrack(bad: Int, id: String?) {
        val p = player ?: return
        // The queue may have changed since the error: find the track again by id.
        val at = if (id == null) bad else tracks.indexOfFirst { it.id == id }
        if (at < 0) return
        if (p.mediaItemCount <= 1) { clear(); return }
        tracks = tracks.filterIndexed { i, _ -> i != at }
        p.removeMediaItem(at)
        p.prepare()
        p.play()
        queueChanged.value++
    }

    init {
        // Save the position now and then while playing, so a killed process loses little.
        scope.launch { playing.collectLatest { if (it) while (true) { delay(15_000); saveSpot(); savePositionNow() } } }
        // Play history: the same cadence as the video player (about every 5 s while playing); pause and track changes record too.
        scope.launch { playing.collectLatest { if (it) while (true) { delay(5_000); recordCurrent() } } }
        // The queue itself, saved a moment after it stops changing.
        scope.launch { queueChanged.debounce(500).collect { saveQueueNow() } }
        // Republish the position only while playing and somebody is watching.
        scope.launch {
            combine(_state.subscriptionCount.map { it > 0 }, playing) { watched, on -> watched && on }
                .distinctUntilChanged()
                .collectLatest { if (it) while (true) { publish(); delay(500) } }
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
        .also { it.addListener(listener); player = it }

    private var playJob: Job? = null

    /**
     * Replaces the queue and starts at [startIndex], where it was left if it is a remembered long track.
     * To stream a YouTube video's audio: `play(listOf(youtubeAudioTrack(video, audioStream)), 0)`.
     */
    fun play(queue: List<AudioTrack>, startIndex: Int) {
        if (queue.isEmpty()) return
        playJob?.cancel()
        playJob = scope.launch { start(queue, startIndex, resumeFor(queue[startIndex.coerceIn(queue.indices)].id)) }
    }

    /** Seeks the current item to where it was left, unless playback has already moved on or past the start. */
    private fun applyResume(id: String) {
        scope.launch {
            val at = resumeFor(id)
            val p = player ?: return@launch
            if (at > 0 && p.currentMediaItem?.mediaId == id && p.currentPosition < AUDIO_RESUME_EDGE_MS) p.seekTo(at)
        }
    }

    private suspend fun resumeFor(id: String): Long =
        resume.get(id)?.let { resumePositionMs(it.positionMs, it.durationMs) } ?: 0L

    private fun start(queue: List<AudioTrack>, startIndex: Int, startMs: Long) {
        saveCurrent() // the queue being replaced
        val p = exo()
        cancelSleep() // "End of track" was for a track of the old queue
        recoverJob?.cancel()
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
        context.startService(Intent(context, BackgroundPlaybackService::class.java))
        publish()
        queueChanged.value++
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
                if (video == null || itag == null) null
                else AudioTrack(t.id, t.streamUrl?.let { Uri.parse(it) } ?: Uri.EMPTY, t.title, t.artist, null, t.artworkUrl,
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
    private suspend fun activate(): Boolean {
        val p = player ?: return false
        if (!restoredPending) return true
        activating = true // the play button flips at once, even while the link is looked up
        publish()
        try {
            val at = p.currentMediaItemIndex
            val track = tracks.getOrNull(at)
            var failed = false
            if (track?.youtube != null) {
                val fresh = refreshed(track)
                if (player !== p) return false
                if (fresh == null) failed = true
                else if (tracks.getOrNull(at)?.id == track.id) {
                    tracks = tracks.toMutableList().also { it[at] = fresh }
                    val pos = p.currentPosition // the replace resets it when the item cannot update in place
                    p.replaceMediaItem(at, fresh.toMediaItem())
                    p.seekTo(at, pos)
                    queueChanged.value++
                }
            }
            pauseBackgroundedVideo()
            BackgroundPlayback.openIntent = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(BackgroundPlayback.EXTRA_EXPAND_NOW_PLAYING, true)
            BackgroundPlayback.player = p
            try {
                context.startService(Intent(context, BackgroundPlaybackService::class.java))
            } catch (e: IllegalStateException) { // not allowed to start a service from the background
                BackgroundPlayback.player = null
                BackgroundPlayback.openIntent = null
                return false
            }
            restoredPending = false
            if (failed && track != null) { dropTrack(at, track.id); return false }
            p.prepare()
            p.play()
            return true
        } finally {
            activating = false
            publish()
        }
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
                if (!activate()) return@withContext false
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
        player?.let { if (wantsToPlay(it.playWhenReady, it.playbackState)) it.pause() else it.play() }
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
        val id = player?.let { tracks.getOrNull(it.currentMediaItemIndex)?.id } ?: return
        scope.launch { resume.forget(id) }
    }
    fun setShuffle(on: Boolean) { player?.shuffleModeEnabled = on; queueChanged.value++ }
    fun cycleRepeat() { player?.let { it.repeatMode = repeatFromPlayerMode(it.repeatMode).next().toPlayerMode(); queueChanged.value++ } }

    /** Plays [index] of the current queue (tapping "Up next"). */
    fun skipTo(index: Int) = whenActive { it.seekToDefaultPosition(index); it.play() }

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
        if (tracks.isEmpty()) return
        val queue = QueueSnapshot(
            tracks.map { it.toSaved() }, p.shuffleModeEnabled, repeatFromPlayerMode(p.repeatMode), p.playbackParameters.speed,
            shuffleOrder = if (p.shuffleModeEnabled) shuffleOrderOf(p) else null
        )
        val position = SavedPosition(tracks.getOrNull(p.currentMediaItemIndex)?.id, p.currentPosition, currentDurationMs(p))
        scope.launch { queueStore.saveQueue(Snapshot(queue, position)) }
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
        id = id, title = title, artist = artist, local = youtube == null,
        videoId = youtube?.videoId, itag = youtube?.audioItag,
        streamUrl = if (youtube != null) uri.toString() else null,
        artworkUrl = if (youtube != null) artworkUrl else null,
        artistUrl = artistUrl
    )

    private fun saveCurrent() { saveSpot(); recordCurrent() }

    private fun saveSpot() { player?.let { save(it, it.currentMediaItemIndex, it.currentPosition) } }

    private fun recordCurrent() { player?.let { recordHistory(it, it.currentMediaItemIndex, it.currentPosition) } }

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
        val id = tracks.getOrNull(index)?.id ?: return
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
        recoverJob?.cancel()
        activateJob?.cancel()
        pendingAction = null
        restoredPending = false
        activating = false
        scope.launch { queueStore.forget() } // closing the queue (or a video taking over) forgets it
        playing.value = false
        BackgroundPlayback.openIntent = null // only audio sets it; a later video must open itself
        val p = player ?: return
        cancelSleep()
        player = null
        if (BackgroundPlayback.player === p) {
            BackgroundPlayback.player = null
            context.stopService(Intent(context, BackgroundPlaybackService::class.java))
        }
        p.removeListener(listener)
        p.release()
        tracks = emptyList()
        _state.value = AudioState()
    }

    /** Video is about to play: let go of the audio session. */
    fun stopForVideo() = clear()

    /** A video left playing in the background owns the service; pause it and take over. */
    private fun pauseBackgroundedVideo() {
        val other = BackgroundPlayback.player
        if (other != null && other !== player) {
            other.pause()
            BackgroundPlayback.player = null
            context.stopService(Intent(context, BackgroundPlaybackService::class.java))
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
