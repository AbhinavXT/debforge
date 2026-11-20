package com.abhinavxt.debforge.player.session

import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.ui.graphics.asImageBitmap
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.abhinavxt.debforge.R
import com.abhinavxt.debforge.domain.SubtitleLink
import com.abhinavxt.debforge.player.OnlineSubtitleLanguages
import com.abhinavxt.debforge.player.controls.ControlsActions
import com.abhinavxt.debforge.player.controls.END_OF_EPISODE
import com.abhinavxt.debforge.player.controls.Fill
import com.abhinavxt.debforge.player.controls.PlaybackSnapshot
import com.abhinavxt.debforge.player.controls.SKIP_INTRO_SECONDS
import com.abhinavxt.debforge.player.controls.TrackChoice
import com.abhinavxt.debforge.player.gestures.GestureMath
import com.abhinavxt.debforge.player.gestures.GestureUiState
import com.abhinavxt.debforge.player.tracks.SubtitleStyle
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The engine's side of the player controls: everything that acts on the player alone. The things that
 * need the screen (back, rotate, fill, picture-in-picture, screenshot, cast, another player, system bars)
 * do nothing here; the host wraps this and supplies them. Also the scrubbing used by the swipe gesture.
 */
@OptIn(UnstableApi::class)
class VideoActions internal constructor(private val s: VideoSession) : ControlsActions {

    private val controls get() = s.controls
    private val gestureUi get() = s.gestureUi
    private val player get() = s.player
    private val trackNames by lazy { androidx.media3.ui.DefaultTrackNameProvider(s.context.resources) }

    // Seek-bar / swipe scrubbing.
    /** Where the current scrub started. */
    var scrubAnchor = 0L
        private set
    private var scrubTarget = 0L
    private var scrubWasPlaying = false
    private var lastScrubSeek = 0L

    override fun snapshot(): PlaybackSnapshot {
        val p = player ?: return PlaybackSnapshot()
        return PlaybackSnapshot(
            isPlaying = p.isPlaying,
            buffering = p.playbackState == Player.STATE_BUFFERING,
            positionMs = p.currentPosition,
            bufferedMs = p.bufferedPosition,
            durationMs = s.durationOrNull() ?: 0L,
            speed = p.playbackParameters.speed,
            live = p.isCurrentMediaItemLive
        )
    }

    override fun playPause() {
        val p = player ?: return
        when {
            p.playbackState == Player.STATE_ENDED -> { p.seekTo(0); p.play() }
            p.isPlaying -> p.pause()
            else -> p.play()
        }
    }

    override fun seekBy(ms: Long) {
        val p = player ?: return
        p.seekTo(GestureMath.seekTarget(p.currentPosition, ms > 0, (kotlin.math.abs(ms) / 1000).toInt(), s.durationOrNull()))
        s.keepShowing("seek")
        gestureUi.seekTaps = GestureUiState.SeekTaps(ms > 0, (kotlin.math.abs(ms) / 1000).toInt())
        s.hideLater("seek", 600) { gestureUi.seekTaps = null }
    }

    override fun skipIntro() {
        val p = player ?: return
        s.skips.rememberIntro(p.currentPosition)
        seekBy(SKIP_INTRO_SECONDS * 1000L)
    }

    override fun scrubStart() = beginScrub()
    override fun scrubTo(ms: Long) = scrubToTarget(ms)
    override fun scrubEnd() = endScrub(cancel = false)
    override fun next() = s.playNext()
    override fun previous() = s.playPrevious()

    override fun setSubtitleDelay(ms: Long) {
        s.applyDelays(ms, controls.audioDelayMs)
        s.remember { it.copy(subtitleDelayMs = ms.takeIf { d -> d != 0L }) }
    }

    override fun setAudioDelay(ms: Long) {
        s.applyDelays(controls.subtitleDelayMs, ms)
        s.remember { it.copy(audioDelayMs = ms.takeIf { d -> d != 0L }) }
    }

    override fun setNightMode(on: Boolean) {
        controls.nightMode = on
        s.applyNightMode()
        s.scope.launch { s.settings.setNightMode(on) }
    }

    override fun seekTo(ms: Long) {
        player?.seekTo(ms)
        controls.panel = null
    }

    override fun setStats(on: Boolean) = s.setStats(on)

    override fun setSoftwareDecoding(on: Boolean) = s.setSoftwareDecoding(on, save = true)
    override fun playAsAudio() = s.handOffToAudio()

    // The host's: they need the screen.
    override fun back() = Unit
    override fun openExternal() = Unit
    override fun cast() = Unit
    override fun rotate() = Unit
    override fun setFill(fill: Fill) = Unit
    override fun enterPip() = Unit
    override fun screenshot() = Unit
    override fun onBarsVisible(visible: Boolean) = Unit

    override fun tracks(type: Int): List<TrackChoice> {
        val p = player ?: return emptyList()
        return p.currentTracks.groups.filter { it.type == type }.flatMap { g ->
            (0 until g.length).filter { g.isTrackSupported(it) }.map { i ->
                TrackChoice(g.mediaTrackGroup, i, trackNames.getTrackName(g.getTrackFormat(i)), g.isTrackSelected(i))
            }
        }
    }

    override fun selectTrack(type: Int, choice: TrackChoice?) {
        val p = player ?: return
        val params = p.trackSelectionParameters.buildUpon()
        if (choice == null) {
            params.setTrackTypeDisabled(type, true)
        } else {
            params.setTrackTypeDisabled(type, false)
                .setOverrideForType(androidx.media3.common.TrackSelectionOverride(choice.group, choice.index))
        }
        p.trackSelectionParameters = params.build()
        controls.panel = null
        // Next time (and every episode of this show): the same choice.
        val f = choice?.let { it.group.getFormat(it.index) }
        if (type == C.TRACK_TYPE_AUDIO && f != null) {
            s.remember { it.copy(audioLanguage = f.language, audioLabel = f.label) }
        } else if (type == C.TRACK_TYPE_TEXT) {
            s.remember { it.copy(textOff = f == null, textLanguage = f?.language, textLabel = f?.label) }
        }
    }

    override fun searchOnlineSubtitles(anyLanguage: Boolean) = searchOnline(anyLanguage)

    override fun pickOnlineSubtitle(sub: com.abhinavxt.debforge.data.subtitles.OnlineSubtitle) = pickOnline(sub)

    override fun setSpeed(speed: Float) {
        player?.setPlaybackSpeed(speed)
        controls.panel = null
        s.remember { it.copy(speed = speed.takeIf { sp -> sp != 1f }) }
    }

    override fun setSleep(minutes: Int?) {
        s.sleepJob?.cancel()
        controls.sleepEndsAt = 0L
        controls.sleepAtEnd = false
        controls.panel = null
        when (minutes) {
            null -> Unit
            END_OF_EPISODE -> controls.sleepAtEnd = true
            else -> {
                val ms = minutes * 60_000L
                controls.sleepEndsAt = android.os.SystemClock.elapsedRealtime() + ms
                s.sleepJob = s.scope.launch {
                    delay(ms)
                    s.sleepNow()
                }
            }
        }
        if (minutes != null) {
            s.showMessage(
                if (minutes == END_OF_EPISODE) s.string(R.string.player_sleep_end)
                else s.context.resources.getQuantityString(R.plurals.player_sleep_in_n, minutes, minutes)
            )
        }
    }

    override fun abLoop() {
        val p = player ?: return
        val pos = p.currentPosition
        val a = controls.loopA
        when {
            a == null -> {
                controls.loopA = pos
                s.showMessage("A  " + GestureMath.time(pos))
            }
            controls.loopB == null -> {
                if (pos <= a + MIN_LOOP_MS) return // B must come after A
                controls.loopB = pos
                startLoop()
                p.seekTo(a)
                s.showMessage("A–B  " + GestureMath.time(a) + " – " + GestureMath.time(pos))
            }
            else -> clearLoop()
        }
        controls.panel = null
    }

    override fun setBackground(on: Boolean) {
        controls.background = on
        s.scope.launch { s.settings.setPlayerBackground(on) }
    }

    override fun setSubtitleStyle(style: SubtitleStyle) {
        controls.subStyle = style
        s.applySubtitleStyle()
        s.scope.launch { s.settings.setSubtitleStyle(style.encode()) }
    }

    override fun setShowRemaining(show: Boolean) {
        controls.showRemaining = show
        s.scope.launch { s.settings.setPlayerShowRemaining(show) }
    }

    internal fun startLoop() {
        val p = player ?: return
        val a = controls.loopA ?: return
        val b = controls.loopB ?: return
        s.loopMessage?.cancel()
        s.loopMessage = p.createMessage { _, _ -> s.player?.seekTo(a) }
            .setPosition(b)
            .setLooper(android.os.Looper.getMainLooper())
            .setDeleteAfterDelivery(false)
            .send()
    }

    internal fun clearLoop() {
        s.loopMessage?.cancel()
        s.loopMessage = null
        controls.loopA = null
        controls.loopB = null
    }

    // --- scrubbing --------------------------------------------------------------------

    /** Start dragging through the video (swipe or seek bar): pause, fast keyframe seeks. */
    fun beginScrub() {
        val p = player ?: return
        scrubAnchor = p.currentPosition
        scrubTarget = scrubAnchor
        scrubWasPlaying = p.isPlaying
        p.pause()
        // Nearest keyframe while dragging: fast, even on a stream.
        p.setSeekParameters(androidx.media3.exoplayer.SeekParameters.CLOSEST_SYNC)
        s.keepShowing("scrub")
    }

    fun scrubToTarget(target: Long) {
        val p = player ?: return
        scrubTarget = target
        gestureUi.scrub = GestureUiState.Scrub(target, target - scrubAnchor)
        val previews = previewsOn()
        if (previews) requestPreview(target)
        // A stream with previews: the frame shows where it'd land, and the
        // video jumps once, on release (no re-buffering at every move).
        if (previews && !s.isLocalFile()) return
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastScrubSeek >= SCRUB_SEEK_EVERY_MS) {
            lastScrubSeek = now
            p.seekTo(target)
        }
    }

    /** Land exactly where the finger stopped ([cancel]: where it started) and play on. */
    fun endScrub(cancel: Boolean) {
        val p = player ?: return
        p.setSeekParameters(androidx.media3.exoplayer.SeekParameters.DEFAULT)
        p.seekTo(if (cancel) scrubAnchor else scrubTarget)
        if (scrubWasPlaying) p.play()
        s.previewJob?.cancel()
        s.hideLater("scrub", 400) {
            gestureUi.scrub = null
            gestureUi.scrubPreview = null
        }
    }

    /** Previews for this file: downloaded ones always, streams if Settings allow, and only while they work. */
    private fun previewsOn() = s.youtube == null && (s.isLocalFile() || s.streamPreviews) && !s.thumbnails.failed

    /** The frame at [target] for the scrub bubble; a newer request replaces an older one. */
    private fun requestPreview(target: Long) {
        val duration = s.durationOrNull() ?: return
        val source = s.uri
        s.previewJob?.cancel()
        s.previewJob = s.scope.launch {
            val frame = s.thumbnails.frame(source, target, duration)
            if (gestureUi.scrub != null) gestureUi.scrubPreview = frame?.asImageBitmap()
        }
    }

    // --- online subtitles -------------------------------------------------------------

    /**
     * Searches OpenSubtitles for this file: by its hash (exact timing) and
     * name, in the subtitle language from Settings (else the phone's), or
     * in any language.
     */
    private fun searchOnline(anyLanguage: Boolean) {
        s.onlineSubsJob?.cancel()
        controls.onlineSubs = com.abhinavxt.debforge.player.controls.OnlineSubs.Searching
        val info = com.abhinavxt.debforge.domain.ReleaseNameParser.parse(s.identity?.filename?.takeIf { it.isNotBlank() } ?: s.title)
        val source = s.uri
        s.onlineSubsJob = s.scope.launch {
            val languages = if (anyLanguage) emptyList() else OnlineSubtitleLanguages.forSetting(
                s.settings.subtitleLanguageFlow.first(), java.util.Locale.getDefault()
            )
            controls.onlineSubs = try {
                // Not a child of this search: switching language mid-search mustn't cancel the shared hash.
                val hash = if (s.youtube != null) null else (s.movieHash ?: s.scope.async { s.movieHasher.hashOf(source) }.also { s.movieHash = it }).await()
                com.abhinavxt.debforge.player.controls.OnlineSubs.Results(s.openSubtitles.search(info, hash, languages), anyLanguage)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                com.abhinavxt.debforge.player.controls.OnlineSubs.Failed(R.string.player_subs_search_failed, anyLanguage)
            }
        }
    }

    /** Downloads [sub], adds it to the file (reloaded at the same second) and switches to it. */
    private fun pickOnline(sub: com.abhinavxt.debforge.data.subtitles.OnlineSubtitle) {
        val results = controls.onlineSubs as? com.abhinavxt.debforge.player.controls.OnlineSubs.Results ?: return
        controls.onlineSubs = com.abhinavxt.debforge.player.controls.OnlineSubs.Downloading(results.subs, results.anyLanguage, sub.fileId)
        s.onlineSubsJob = s.scope.launch {
            val file = try {
                s.openSubtitles.download(sub)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: com.abhinavxt.debforge.data.subtitles.OpenSubtitlesRepository.QuotaException) {
                controls.onlineSubs = com.abhinavxt.debforge.player.controls.OnlineSubs.Failed(R.string.player_subs_quota, results.anyLanguage)
                return@launch
            } catch (e: Exception) {
                controls.onlineSubs = results
                s.showMessage(s.string(R.string.player_subs_download_failed))
                return@launch
            }
            controls.onlineSubs = results
            val language = sub.language?.let { java.util.Locale.forLanguageTag(it).displayName.ifBlank { it } }
            val label = s.string(R.string.player_subs_online_label, language ?: s.string(R.string.player_subtitles))
            // One online subtitle per label: picking another English one replaces the last.
            s.subtitles = s.subtitles.filterNot { it.label == label } + SubtitleLink(
                url = Uri.fromFile(file).toString(),
                mimeType = "application/x-subrip",
                language = sub.language?.substringBefore('-'),
                label = label,
                forced = false
            )
            s.pendingTextLabel = label
            controls.panel = null
            s.player?.let { p -> s.reloadMediaItem(p.currentPosition, play = false) }
        }
    }

    private companion object {
        /** While scrubbing, seek at most this often (streams can't keep up with every move). */
        const val SCRUB_SEEK_EVERY_MS = 120L
        /** The shortest A-B loop. */
        const val MIN_LOOP_MS = 1_000L
    }
}
