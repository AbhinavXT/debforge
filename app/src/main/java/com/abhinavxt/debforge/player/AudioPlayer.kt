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
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.abhinavxt.debforge.MainActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
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
    val artworkFile: File?
)

enum class AudioRepeat { OFF, ALL, ONE }

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
@OptIn(UnstableApi::class)
@Singleton
class AudioPlayer @Inject constructor(@ApplicationContext private val context: Context) {

    private val _state = MutableStateFlow(AudioState())
    val state: StateFlow<AudioState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val playing = MutableStateFlow(false)
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

        override fun onPlayerError(error: PlaybackException) {
            // A file deleted while queued or playing: drop it and carry on with the next one.
            val p = player ?: return
            val bad = p.currentMediaItemIndex
            if (p.mediaItemCount <= 1) { clear(); return }
            tracks = tracks.filterIndexed { i, _ -> i != bad }
            p.removeMediaItem(bad)
            p.prepare()
            p.play()
        }
    }

    init {
        // Republish the position only while playing and somebody is watching.
        scope.launch {
            combine(_state.subscriptionCount.map { it > 0 }, playing) { watched, on -> watched && on }
                .distinctUntilChanged()
                .collectLatest { if (it) while (true) { publish(); delay(500) } }
        }
    }

    private fun exo(): ExoPlayer = player ?: ExoPlayer.Builder(context)
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
            true
        )
        .setHandleAudioBecomingNoisy(true)
        .setWakeMode(C.WAKE_MODE_LOCAL)
        .build()
        .also { it.addListener(listener); player = it }

    /** Replaces the queue and starts at [startIndex]. */
    fun play(queue: List<AudioTrack>, startIndex: Int) {
        if (queue.isEmpty()) return
        val p = exo()
        pauseBackgroundedVideo()
        tracks = queue
        p.setMediaItems(queue.map { it.toMediaItem() }, startIndex.coerceIn(queue.indices), 0)
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
    }

    private var tracks: List<AudioTrack> = emptyList()

    // The sleep timer lives here, with the player, so it outlasts the screen; it is not persisted.
    private var sleepJob: Job? = null
    private var sleepDeadline = 0L
    private var sleepAtEnd = false

    fun setSpeed(speed: Float) { player?.setPlaybackSpeed(speed) }

    /** [minutes] 0 = off, [SLEEP_END_OF_TRACK], or a countdown; pauses (keeps the queue) when it fires. */
    fun setSleepTimer(minutes: Int) {
        val p = player ?: return
        cancelSleep()
        if (minutes == SLEEP_END_OF_TRACK) { sleepAtEnd = true; p.pauseAtEndOfMediaItems = true }
        else if (minutes > 0) {
            val ms = minutes * 60_000L
            sleepDeadline = SystemClock.uptimeMillis() + ms // uptime, like delay() on the main dispatcher
            sleepJob = scope.launch { delay(ms); p.pause(); cancelSleep() }
        }
        publish()
    }

    private fun cancelSleep() {
        sleepJob?.cancel()
        sleepJob = null
        sleepAtEnd = false
        player?.pauseAtEndOfMediaItems = false
        publish()
    }

    fun toggle() { player?.let { if (wantsToPlay(it.playWhenReady, it.playbackState)) it.pause() else it.play() } }
    fun next() { player?.seekToNextMediaItem() }
    /** Past 3 seconds restarts the track, like every music player. */
    fun previous() { player?.seekToPrevious() }
    fun seekTo(ms: Long) { player?.seekTo(ms); publish() }
    fun setShuffle(on: Boolean) { player?.shuffleModeEnabled = on }
    fun cycleRepeat() { player?.let { it.repeatMode = repeatFromPlayerMode(it.repeatMode).next().toPlayerMode() } }

    /** Plays [index] of the current queue (tapping "Up next"). */
    fun skipTo(index: Int) { player?.let { it.seekToDefaultPosition(index); it.play() } }

    /** Files were deleted: take them out of the queue, moving on if the current one went. */
    fun removeTracks(ids: Set<String>) {
        val p = player ?: return
        val gone = tracks.indices.filter { tracks[it].id in ids }
        if (gone.isEmpty()) return
        if (gone.size == tracks.size) { clear(); return }
        tracks = queueWithout(tracks, ids) { it.id }
        gone.asReversed().forEach { p.removeMediaItem(it) }
        if (p.playbackState == Player.STATE_IDLE) { p.prepare(); p.play() }
        publish()
    }

    /** Stops, releases the player and the notification. */
    fun clear() {
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
            isPlaying = wantsToPlay(p.playWhenReady, p.playbackState),
            positionMs = p.currentPosition,
            durationMs = p.duration.takeIf { it != C.TIME_UNSET } ?: 0,
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
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setArtworkUri(artworkFile?.takeIf { it.exists() }?.toUri())
                .build()
        )
        .build()
}
