package com.abhinavxt.debforge.player.session

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.abhinavxt.debforge.R
import com.abhinavxt.debforge.data.playback.PlaybackEntity
import com.abhinavxt.debforge.data.playback.PlaybackPositions
import com.abhinavxt.debforge.data.repository.DownloadsRepository
import com.abhinavxt.debforge.domain.ProviderId
import com.abhinavxt.debforge.domain.Resume
import com.abhinavxt.debforge.domain.SubtitleLink
import com.abhinavxt.debforge.player.AudioPlayer
import com.abhinavxt.debforge.player.AudioTrack
import com.abhinavxt.debforge.player.BackgroundPlayback
import com.abhinavxt.debforge.player.ChapterReader
import com.abhinavxt.debforge.player.EpisodePlayer
import com.abhinavxt.debforge.player.MovieHasher
import com.abhinavxt.debforge.player.PlayerErrors
import com.abhinavxt.debforge.player.QueuedEpisode
import com.abhinavxt.debforge.player.RendererTuning
import com.abhinavxt.debforge.player.SubtitleResolver
import com.abhinavxt.debforge.player.SyncedRenderersFactory
import com.abhinavxt.debforge.player.ThumbnailLoader
import com.abhinavxt.debforge.player.TraktScrobbler
import com.abhinavxt.debforge.player.UpNext
import com.abhinavxt.debforge.player.VideoHandoff
import com.abhinavxt.debforge.player.VideoStart
import com.abhinavxt.debforge.player.controls.ControlsState
import com.abhinavxt.debforge.player.gestures.GestureMath
import com.abhinavxt.debforge.player.gestures.GestureUiState
import com.abhinavxt.debforge.player.playAsAudioVisible
import com.abhinavxt.debforge.player.restoresSavedPosition
import com.abhinavxt.debforge.player.tracks.SubtitleStyle
import com.abhinavxt.debforge.player.tracks.TrackPicker
import com.abhinavxt.debforge.ui.browse.YtStream
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The video player's engine: one ExoPlayer for the video being watched, and everything that goes with it
 * that is not a screen. It outlives its host (PlayerActivity), which shows it through [attach] and reports
 * its lifecycle through the host* functions; the order of those steps is [VideoLifecycle].
 *
 * Owns: building and releasing the player (device decoders + FFmpeg audio, sync, the YouTube merged
 * source), the current file (link, title, subtitles, YouTube parts), the episode queue with Next / Previous
 * and the next-episode card, positions, history and Trakt, the once-a-second tick (saving, intro / recap /
 * credits and SponsorBlock skips, stats), link refresh and the YouTube fallbacks, sleep timer, A-B loop,
 * night mode / volume boost, sync and decoder choice, remembered tracks, online subtitles, the media
 * session and the hand-over to the background service, and parking the audio queue.
 *
 * What needs an Activity (dialogs, picture-in-picture, orientation, system bars, the overlay Views) goes
 * through [VideoHost]. Everything here runs on the main thread.
 */
@OptIn(UnstableApi::class)
@Singleton
class VideoSession @Inject constructor(
    @param:ApplicationContext internal val context: Context,
    internal val positions: PlaybackPositions,
    private val history: com.abhinavxt.debforge.data.history.PlayHistory,
    internal val audioPlayer: AudioPlayer,
    private val videoHandoff: VideoHandoff,
    private val upNext: UpNext,
    internal val subtitleResolver: SubtitleResolver,
    internal val repository: DownloadsRepository,
    internal val settings: com.abhinavxt.debforge.data.prefs.SettingsStore,
    private val sponsorRepo: com.abhinavxt.debforge.data.sponsor.SponsorSegmentRepository,
    internal val trackMemory: com.abhinavxt.debforge.data.playback.TrackMemory,
    private val scrobbler: TraktScrobbler,
    internal val openSubtitles: com.abhinavxt.debforge.data.subtitles.OpenSubtitlesRepository,
    internal val movieHasher: MovieHasher,
    private val chapterReader: ChapterReader,
    internal val youTubeResolver: com.abhinavxt.debforge.data.youtube.YouTubeResolver,
    internal val networkMonitor: com.abhinavxt.debforge.download.NetworkMonitor,
) {
    private val lifecycle = VideoLifecycle(Steps())

    /** Jobs of the current video; cancelled when it closes or is replaced. */
    internal var scope = newScope()
        private set

    var player: ExoPlayer? = null
        private set
    private var view: PlayerView? = null
    internal var host: VideoHost? = null
        private set

    /** The app's picture-in-picture (MainActivity): told when the playing state, the picture's size or the episode changed. */
    var pipListener: (() -> Unit)? = null
    /** Is the app in its floating window now? Then pausing the activity must not hand the player to the background service. */
    var pipGate: (() -> Boolean)? = null

    /** The playing state or the picture's shape changed: the host and the app's floating window follow. */
    internal fun updatePip() {
        host?.updatePip()
        pipListener?.invoke()
    }

    /** DebForge's own controls' state (the same object PlayerControls shows). New for each video. */
    var controls = ControlsState()
        private set
    /** What the gestures and short notes show over the video. New for each video. */
    var gestureUi = GestureUiState()
        private set
    /** The engine's side of the controls; the host adds its own (back, rotate, fill, PiP, cast…). */
    var actions = VideoActions(this)
        private set
    internal var skips = VideoSkips(this)
        private set
    internal var refresher = VideoRefresh(this)
        private set
    internal var episodes = VideoEpisodes(this)
        private set
    internal var effects = VideoEffects(this)
        private set
    internal var stats = VideoStats(this)
        private set
    internal var tracks = VideoTracks(this)
        private set

    /** Android TV: Media3's controller and seek bar markers; no online subtitles. */
    val isTv by lazy {
        (context.getSystemService(Context.UI_MODE_SERVICE) as android.app.UiModeManager).currentModeType ==
            android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
    }

    // --- the current file ---
    /** Which file this is (null for plain links): positions are saved only when known. */
    var identity: VideoIdentity? = null
        internal set
    /** Set when streaming a YouTube video (video-only URL in [uri], audio merged in); null for everything else. */
    var youtube: YtStream? = null
        internal set
    var uri: Uri = Uri.EMPTY
        internal set
    var title: String = ""
        private set
    var subtitles: List<SubtitleLink> = emptyList()
        internal set
    private var tickJob: Job? = null

    // --- tracks, subtitles ---
    /** This file's tracks have been chosen (preferences / last time); only once per file. */
    private var tracksApplied = false
    /** What was chosen last time for this show / file, loading. */
    internal var remembered: kotlinx.coroutines.Deferred<TrackPicker.Remembered?>? = null
        private set
    /** Kept across the player being recreated (app switch), with the speed. */
    private var savedTrackParams: androidx.media3.common.TrackSelectionParameters? = null
    private var savedSpeed = 1f
    /** Set when the audio player handed this item over: its speed beats the one remembered for the show. */
    internal var handoffStart: VideoStart? = null
        private set
    /** The audio track "Play as audio" hands over; null when there is none for this file. */
    private var audioHandoffTrack: AudioTrack? = null
    /** Sync and decoder choice, read by the renderers ([SyncedRenderersFactory]). */
    internal var tuning = RendererTuning()
        private set

    // --- media session (headset, watch, lock screen, output switcher) ---
    /** The player with next / previous mapped to episodes; what sessions control. */
    internal var episodePlayer: EpisodePlayer? = null
        private set
    /** While watching; the background service has its own while away. */
    private var mediaSession: androidx.media3.session.MediaSession? = null

    // --- seek previews ---
    internal val thumbnails by lazy { ThumbnailLoader(context) }
    internal var previewJob: Job? = null
    /** Settings: previews on streams too (they cost data); downloaded files always have them. */
    internal var streamPreviews = false

    /** This file's OpenSubtitles hash, worked out on the first search. */
    internal var movieHash: kotlinx.coroutines.Deferred<String?>? = null
    internal var onlineSubsJob: Job? = null
    /** A subtitle just added from OpenSubtitles: switch to it once the reloaded file lists it. */
    internal var pendingTextLabel: String? = null

    /** Bumped per file, so a slow chapter read for the last one is dropped. */
    internal var fileGeneration = 0
        private set

    // --- extras ---
    internal var sleepJob: Job? = null
    /** Jumps back to A whenever playback reaches B. */
    internal var loopMessage: androidx.media3.exoplayer.PlayerMessage? = null
    private val hideJobs = HashMap<String, Job>()

    // Survive the player being released and built again (app switch).
    internal var resumePosition = 0L
    private var resumePlayWhenReady = true
    /** The duration of the player last released (0: unknown), for the parked audio after the player is gone. */
    private var releasedDurationMs = 0L
    /** Asked once per file whether to switch players: don't nag on every track change. */
    var offeredFallback = false
        private set

    // --- opening, hosts and lifecycle ---------------------------------------------

    /** A video is open (its host may be away). */
    val isOpen get() = lifecycle.isOpen

    /** The token of the video open now; a host holding another one shows a replaced video. */
    val token get() = lifecycle.token

    fun isCurrent(token: Long) = lifecycle.isCurrent(token)

    /**
     * Plays [target] (the video open before, if any, is saved and closed first) and returns its token.
     * [start]: handed over by the audio player (its position and speed, not the saved ones).
     * [restoreSaved]: look up the saved position (a fresh start; not after the host was recreated).
     * [resumeAtMs], [playWhenReady], [offeredFallback]: carried over a recreated host.
     */
    fun open(
        target: VideoTarget,
        start: VideoStart? = null,
        restoreSaved: Boolean = restoresSavedPosition(start),
        resumeAtMs: Long? = null,
        playWhenReady: Boolean = true,
        offeredFallback: Boolean = false,
    ): Long {
        val token = lifecycle.open() // the audio queue waits, paused, in the mini player
        uri = target.uri
        title = target.title
        youtube = target.youtube
        // A stream is saved under DIRECT with no filename: Trakt (which needs a name) skips it.
        identity = target.identity ?: youtube?.let {
            VideoIdentity(
                com.abhinavxt.debforge.player.youtube.YouTubeStreaming.streamPositionKey(it.videoId, false),
                ProviderId.DIRECT, it.ref, "", null
            )
        }
        subtitles = target.subtitles
        start?.let {
            handoffStart = it
            resumePosition = it.positionMs
            savedSpeed = it.speed
        }
        resumeAtMs?.let { resumePosition = it }
        resumePlayWhenReady = playWhenReady
        this.offeredFallback = offeredFallback
        identity?.let { episodes.load(upNext.take(it.itemId)) }
        controls.title = title
        controls.castAvailable = !isTv && !isLocalFile() && youtube == null
        controls.externalAvailable = youtube == null
        startFile()
        refreshAudioHandoff()
        scope.launch {
            episodes.autoplay = settings.autoplayNextFlow.first()
            controls.showRemaining = settings.playerShowRemainingFlow.first()
            controls.subStyle = SubtitleStyle.decode(settings.subtitleStyleFlow.first())
            controls.background = settings.playerBackgroundFlow.first()
            controls.onlineSubsAvailable = !isTv && openSubtitles.enabled.first()
            skips.autoSkipIntro = settings.autoSkipIntroFlow.first()
            controls.nightMode = settings.nightModeFlow.first()
            streamPreviews = settings.streamPreviewsFlow.first()
            effects.applyNightMode()
            tracks.applySubtitleStyle()
        }
        // Fresh start (not a recreated host): pick up where the user left off.
        if (restoreSaved) restoreSavedPosition()
        return token
    }

    /**
     * [host] shows this video in [view] from now on (one at a time). Call [detach] with the same view when
     * the host goes away for good.
     */
    fun attach(view: PlayerView, host: VideoHost) {
        if (this.view !== view) this.view?.player = null
        this.view = view
        this.host = host
        view.player = player
        tracks.applySubtitleStyle()
        if (isTv) applyChapterMarks(skips.chapters)
        // A new screen starts without the card and with stats as they were (a recreated screen counted down afresh).
        episodes.cardShownAt = null
        host.showStats(controls.stats)
    }

    /** The view showing the video, if any. */
    internal val playerView: PlayerView? get() = view

    fun detach(view: PlayerView) {
        if (this.view !== view) return
        view.player = null
        this.view = null
        host = null
    }

    /** Host visible again. False: [token] is a replaced video; the host should close. */
    fun hostStarted(token: Long): Boolean = lifecycle.hostStarted(token)

    /** Host leaving the foreground; [mayLeave] false while finishing, recreating or in picture-in-picture. */
    fun hostPaused(token: Long, mayLeave: Boolean) =
        lifecycle.hostPaused(token, mayHandOver(mayLeave, pipGate?.invoke() == true), controls.background)

    fun hostResumed(token: Long) = lifecycle.hostResumed(token)

    fun hostStopped(token: Long, finishing: Boolean) = lifecycle.hostStopped(token, finishing)

    fun hostDestroyed(token: Long, finishing: Boolean) = lifecycle.hostDestroyed(token, finishing)

    /** Ends the video: saves it, tells the parked audio where it stopped, releases the player. */
    fun close() = lifecycle.close()

    /** The player was handed to the background service. */
    val inBackground get() = lifecycle.inBackground

    /** Where to pick up if the host is recreated after its process died. */
    fun positionForState(): Pair<Long, Boolean> {
        player?.let {
            resumePosition = it.currentPosition
            resumePlayWhenReady = it.playWhenReady
        }
        return resumePosition to resumePlayWhenReady
    }

    private inner class Steps : VideoLifecycle.Steps {
        override fun hasPlayer() = player != null
        override fun closeHost() { host?.close() }
        override fun isPlaying() = player?.isPlaying == true
        override fun parkAudio() = audioPlayer.parkForVideo()
        override fun savePosition() = this@VideoSession.savePosition()

        override fun audioVideoClosed() {
            val id = identity ?: return
            // Released already (the host stopped first): where it was when released.
            val p = player
            val position = p?.currentPosition ?: resumePosition
            val duration = p?.duration?.takeIf { it != C.TIME_UNSET } ?: releasedDurationMs
            audioPlayer.videoClosed(id.itemId, youtube?.videoId, position, duration)
        }

        override fun buildPlayer() = initializePlayer()
        override fun releasePlayer() = this@VideoSession.releasePlayer()
        override fun startTicking() = this@VideoSession.startTicking()

        override fun stopTicking() {
            tickJob?.cancel()
        }

        override fun handToBackground() {
            // The service's session takes over (notification, lock screen).
            closeMediaSession()
            BackgroundPlayback.player = episodePlayer ?: player
            BackgroundPlayback.start(context)
        }

        /** Back in the player: the service lets go, pictures come back. */
        override fun takeBack() {
            audioPlayer.parkForVideo() // audio may have taken the service over meanwhile; video and audio never overlap
            BackgroundPlayback.stop(context)
            BackgroundPlayback.player = null
            openMediaSession()
            player?.let { it.trackSelectionParameters = it.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false).build() }
        }

        override fun disableVideo() {
            player?.let { it.trackSelectionParameters = it.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true).build() }
        }

        override fun discard() = resetState()
    }

    /** Forgets the closed video: its jobs, queue and per-file state. The next [open] starts from scratch. */
    private fun resetState() {
        scope.cancel()
        scope = newScope()
        hideJobs.clear()
        tickJob = null
        thumbnails.release()
        refresher.cancel()
        tracksApplied = false
        remembered = null
        savedTrackParams = null
        savedSpeed = 1f
        handoffStart = null
        audioHandoffTrack = null
        tuning = RendererTuning()
        previewJob = null
        movieHash = null
        onlineSubsJob = null
        pendingTextLabel = null
        sleepJob = null
        loopMessage = null
        resumePosition = 0L
        resumePlayWhenReady = true
        releasedDurationMs = 0L
        offeredFallback = false
        identity = null
        youtube = null
        uri = Uri.EMPTY
        title = ""
        subtitles = emptyList()
        controls = ControlsState()
        gestureUi = GestureUiState()
        actions = VideoActions(this)
        skips = VideoSkips(this)
        refresher = VideoRefresh(this)
        episodes = VideoEpisodes(this)
        effects = VideoEffects(this)
        stats = VideoStats(this)
        tracks = VideoTracks(this)
    }

    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // --- small helpers for the host and the helpers ------------------------------

    internal fun string(res: Int, vararg args: Any): String = context.getString(res, *args)

    internal fun durationOrNull(): Long? = player?.duration?.takeIf { it != C.TIME_UNSET && it > 0 }

    fun isLocalFile() = uri.scheme == "content" || uri.scheme == "file"

    val hasNext get() = episodes.hasNext
    val hasPrevious get() = episodes.hasPrevious
    /** The episodes after this one, nearest first. */
    val upcomingEpisodes get() = episodes.upcoming
    /** Plays the upcoming episode [itemId] in this same player. */
    fun playEpisode(itemId: String) = episodes.jumpTo(itemId)

    /** Switches to the next episode in this same player. */
    fun playNext() = episodes.playNext()

    /** Back to the episode before this one. */
    fun playPrevious() = episodes.playPrevious()

    /** Cancel on the card (or Back while it shows): no autoplay for this file. */
    fun cancelNext() = episodes.cancel()

    /** Volume past the phone's maximum (1 = none, up to [GestureMath.MAX_VOLUME_LEVEL]). */
    val boostLevel get() = effects.boostLevel

    /** The volume gesture can go past 100 % (the loudness enhancer is there). */
    val canBoost get() = effects.canBoost

    /** Volume past the phone's maximum from the volume gesture (1 = none). */
    fun setBoost(level: Float) = effects.setBoost(level)

    /** Stats for nerds on or off (More, or the remote's Info key on TV). */
    fun setStats(on: Boolean) {
        controls.stats = on
        host?.showStats(on)
        if (on) stats.update()
    }

    internal fun applyNightMode() = effects.applyNightMode()
    internal fun applySubtitleStyle() = tracks.applySubtitleStyle()

    /** Shows [key]'s feedback until [ms] after the last update, then [clear]s it. */
    fun hideLater(key: String, ms: Long, clear: () -> Unit) {
        hideJobs[key]?.cancel()
        hideJobs[key] = scope.launch {
            delay(ms)
            clear()
        }
    }

    fun keepShowing(key: String) {
        hideJobs.remove(key)?.cancel()
    }

    /** Brief text over the video ("Crop", "Fit"…). */
    fun showMessage(text: String) {
        keepShowing("msg")
        gestureUi.message = text
        hideLater("msg", 1_200) { gestureUi.message = null }
    }

    // --- positions ------------------------------------------------------------------

    /** Looks up the saved position; seeks there once known, unless the user already moved. */
    private fun restoreSavedPosition() {
        val id = identity ?: return
        scope.launch {
            val saved = positions.get(id.itemId) ?: return@launch
            val at = Resume.resumeAt(saved.positionMs, saved.durationMs, saved.finished)
            if (at <= 0) return@launch
            val p = player
            if (p == null) {
                resumePosition = at
            } else if (p.currentPosition < Resume.MIN_RESUME_MS) {
                p.seekTo(at)
            } else {
                return@launch
            }
            host?.showStartOver(true)
            delay(START_OVER_VISIBLE_MS)
            host?.showStartOver(false)
        }
    }

    /**
     * Once a second: the next-episode card and prefetch; every few seconds
     * while playing, save the position (so a crash or a swipe-away loses little).
     */
    private fun startTicking() {
        tickJob?.cancel()
        tickJob = scope.launch {
            var ticks = 0
            while (true) {
                delay(1_000)
                ticks++
                if (ticks % SAVE_EVERY_TICKS == 0 && player?.isPlaying == true) savePosition()
                controls.hasNext = episodes.hasNext
                controls.hasPrevious = episodes.hasPrevious
                episodes.update()
                skips.update()
                if (controls.stats) stats.update()
            }
        }
    }

    /** Tells Trakt (if signed in) about the current file; [ended] reports it as played to the end. */
    internal fun scrobble(send: (String, Long, Long) -> Unit, ended: Boolean = false) {
        val p = player ?: return
        val duration = durationOrNull() ?: return
        val name = identity?.filename?.takeIf { it.isNotBlank() } ?: return
        send(name, if (ended) duration else p.currentPosition.coerceIn(0, duration), duration)
    }

    /** Tells Trakt the file stopped ([ended]: played to the end). */
    internal fun scrobbleStopped(ended: Boolean = false) = scrobble(scrobbler::stopped, ended)

    internal fun savePosition(ended: Boolean = false) {
        val id = identity ?: return
        val p = player ?: return
        val duration = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return
        val position = if (ended) duration else p.currentPosition.coerceIn(0, duration)
        positions.save(
            PlaybackEntity(
                itemId = id.itemId,
                provider = id.provider,
                sourceRef = id.sourceRef,
                filename = id.filename,
                title = title,
                showKey = id.showKey,
                parentRef = id.parentRef,
                positionMs = position,
                durationMs = duration,
                finished = ended || Resume.isFinished(position, duration),
                updatedAt = System.currentTimeMillis()
            )
        )
        if (com.abhinavxt.debforge.data.history.shouldRecord(position, ended)) {
            val yt = youtube
            history.record(
                com.abhinavxt.debforge.data.history.HistoryEntity(
                    itemId = id.itemId,
                    kind = com.abhinavxt.debforge.data.history.HistoryKind.VIDEO,
                    origin = com.abhinavxt.debforge.data.history.historyOriginFor(yt != null, isLocalFile()),
                    provider = if (yt != null) null else id.provider.name,
                    sourceRef = yt?.videoId ?: id.sourceRef,
                    parentRef = id.parentRef,
                    showKey = id.showKey,
                    title = title,
                    subtitle = yt?.uploader?.takeIf { it.isNotBlank() },
                    artworkUrl = null,
                    positionMs = position,
                    durationMs = duration,
                    playedAt = System.currentTimeMillis()
                )
            )
        }
    }

    // --- play as audio -------------------------------------------------------------

    /** Looks up the audio track of the current file for "Play as audio"; none for a debrid/Library stream. */
    private fun refreshAudioHandoff() {
        audioHandoffTrack = null
        controls.audioHandoffReady = false
        val yt = youtube
        val id = identity ?: return
        controls.debridStream = yt == null && !isLocalFile()
        if (controls.debridStream) return
        val generation = fileGeneration
        val name = title
        scope.launch {
            val track = runCatching {
                videoHandoff.audioTrackForVideo(
                    itemId = id.itemId,
                    videoId = yt?.videoId,
                    title = name,
                    channel = yt?.uploader,
                    audioUrl = yt?.audioUrl,
                    audioItag = yt?.let { com.abhinavxt.debforge.domain.YouTubeLinks.parseSourceRef(it.ref)?.audioItag }
                )
            }.getOrNull()
            if (generation != fileGeneration) return@launch // another file by now
            audioHandoffTrack = track
            controls.audioHandoffReady = track != null
        }
    }

    /** TV's MENU key: whether "Play as audio" can be offered now. */
    fun canPlayAsAudio(): Boolean {
        val p = player ?: return false
        val offer = playAsAudioVisible(p.isCurrentMediaItemLive, durationOrNull() ?: 0L, audioHandoffTrack != null, controls.casting, controls.debridStream)
        return offer && !lifecycle.handingOff
    }

    /** "Play as audio": save the spot, hand the item to the audio player at this position and speed, and close. */
    fun handOffToAudio() {
        val track = audioHandoffTrack ?: return
        val p = player ?: return
        if (lifecycle.handingOff) return
        lifecycle.handingOff = true
        val at = p.currentPosition
        val speed = p.playbackParameters.speed
        p.pause()
        savePosition()
        audioPlayer.playHandoff(track, at, speed)
        host?.close()
    }

    // --- a new file ---------------------------------------------------------------

    /** A new file starts: its tracks get chosen once they're known. */
    private fun startFile() {
        tracksApplied = false
        actions.clearLoop()
        movieHash = null
        previewJob?.cancel()
        gestureUi.scrubPreview = null
        scope.launch { thumbnails.reset() }
        skips.reset()
        val generation0 = fileGeneration + 1
        val videoId = youtube?.videoId
            ?: identity?.sourceRef?.let { com.abhinavxt.debforge.domain.YouTubeLinks.parseSourceRef(it)?.videoId }
        if (videoId != null) scope.launch {
            val found = runCatching { sponsorRepo.segmentsFor(videoId) }.getOrDefault(emptyList())
            if (com.abhinavxt.debforge.domain.SponsorSkip.isCurrent(generation0, fileGeneration)) skips.sponsorRaw = found
        }
        val file = identity?.filename?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment.orEmpty()
        val source = uri
        val generation = ++fileGeneration
        controls.chapters = emptyList()
        if (isTv) view?.setExtraAdGroupMarkers(null, null)
        scope.launch {
            val found = if (youtube != null) emptyList() else runCatching { chapterReader.chapters(source, file) }.getOrDefault(emptyList())
            if (generation != fileGeneration) return@launch // another file by now
            skips.chapters = found
            controls.chapters = found
            if (isTv) applyChapterMarks(found)
        }
        onlineSubsJob?.cancel()
        controls.onlineSubs = com.abhinavxt.debforge.player.controls.OnlineSubs.Idle
        pendingTextLabel = null
        val key = identity?.let { it.showKey ?: it.itemId }
        remembered = key?.let { k -> scope.async { trackMemory.get(k) } }
        if (identity?.showKey != null) {
            val memory = remembered
            scope.launch { skips.learntIntroAt = memory?.await()?.introAtMs }
        }
        // Sync starts at zero, then last time's for this show (or file); the
        // decoder choice too (switching only if it differs, which reloads).
        applyDelays(0, 0)
        stats.droppedFrames = 0
        scope.launch {
            val r = remembered?.await()
            if (generation != fileGeneration) return@launch
            if (r != null) applyDelays(r.subtitleDelayMs ?: 0, r.audioDelayMs ?: 0)
            val software = r?.softwareDecoding == true
            if (software != tuning.preferSoftwareVideo) setSoftwareDecoding(software, save = false)
        }
    }

    /** [episode] of the queue starts in this same player, with its fetched [url] and [subtitles]. */
    internal fun switchFile(episode: QueuedEpisode, url: String, subtitles: List<SubtitleLink>) {
        val item = episode.item
        identity = VideoIdentity(item.id, item.provider, item.sourceRef, item.filename, episode.showKey, item.parentRef)
        uri = Uri.parse(url)
        title = episode.title
        this.subtitles = subtitles
        host?.titleChanged(title)
        controls.title = title
        controls.castAvailable = !isTv && !isLocalFile() && youtube == null
        controls.externalAvailable = youtube == null
        handoffStart = null // a later episode is not the handed-over item
        startFile()
        refreshAudioHandoff()
        offeredFallback = false
        refresher.resetBudget()
    }

    /** TV: Media3's seek bar marks the chapters (its ad markers). */
    private fun applyChapterMarks(chapters: List<com.abhinavxt.debforge.domain.MkvChapters.Chapter>) {
        val v = view ?: return
        val marks = chapters.map { it.startMs }.filter { it > 0 }
        v.setExtraAdGroupMarkers(marks.toLongArray(), BooleanArray(marks.size))
    }

    internal fun remember(change: (TrackPicker.Remembered) -> TrackPicker.Remembered) {
        val key = identity?.let { it.showKey ?: it.itemId } ?: return
        scope.launch { trackMemory.update(key, change) }
    }

    /** Reloads the current file at the same second (new subtitles, a fresh link). */
    internal fun reloadMediaItem(position: Long, play: Boolean) {
        player?.apply {
            setMediaItem(buildMediaItem(), position)
            prepare()
            if (play) play()
        }
    }

    // --- sync and decoders -------------------------------------------------------

    /**
     * Video on the CPU (Android's own decoders) instead of the chip's, for a
     * file the hardware decoder shows badly. Decoders are picked when they
     * start, so the file reloads at the same second. [save]: remember it for
     * this show (or file).
     */
    internal fun setSoftwareDecoding(on: Boolean, save: Boolean) {
        tuning.preferSoftwareVideo = on
        controls.softwareDecoding = on
        if (save) remember { it.copy(softwareDecoding = on.takeIf { s -> s }) }
        val p = player ?: return
        if (p.mediaItemCount == 0) return
        val at = p.currentPosition
        val play = p.playWhenReady
        p.stop() // releases the decoders
        p.setMediaItem(buildMediaItem(), at)
        p.prepare()
        p.playWhenReady = play
    }

    internal fun applyDelays(subtitleMs: Long, audioMs: Long) {
        tuning.subtitleDelayMs = subtitleMs
        tuning.audioDelayMs = audioMs
        controls.subtitleDelayMs = subtitleMs
        controls.audioDelayMs = audioMs
    }

    // --- sleep ---------------------------------------------------------------------

    /** Sleep timer ran out (or the episode ended with "end of episode"). */
    internal fun sleepNow() {
        sleepJob?.cancel()
        sleepJob = null
        controls.sleepEndsAt = 0L
        controls.sleepAtEnd = false
        player?.pause()
        showMessage(string(R.string.player_sleep_done))
    }

    // --- errors ----------------------------------------------------------------------

    /** "Can't play this here" → hand the same link to VLC & co. */
    /** [videoProblem]: the picture may be the trouble, so software decoding is worth a try first. */
    private fun offerExternal(message: String, videoProblem: Boolean = true) {
        val host = host
        if (offeredFallback || host == null || host.isClosing) return
        if (youtube != null) { // another player would only get the picture, without the audio stream
            offeredFallback = true
            showYouTubeProblem(message)
            return
        }
        offeredFallback = true
        player?.pause()
        host.offerExternal(message, offerSoftware = videoProblem && !tuning.preferSoftwareVideo)
    }

    /** Retry in the network-error dialog. */
    fun retryAfterNetworkError() = refresher.retry()

    /** The network-error dialog went away: stop waiting for the connection to come back. */
    fun networkDialogGone() = refresher.networkDialogGone()

    /** "Try software decoding" in the [offerExternal] dialog. */
    fun trySoftwareDecoding() {
        offeredFallback = false // if this fails too, ask again
        setSoftwareDecoding(true, save = true)
        player?.play()
    }

    /** A message for a stream that can't continue and that downloading would not fix. */
    internal fun showYouTubeProblem(message: String) {
        val host = host ?: return
        if (host.isClosing) return
        player?.pause()
        host.showYouTubeProblem(message)
    }

    /** The stream can't be kept going: offer to download the video instead, or close. */
    internal fun showStreamFailed() {
        val yt = youtube ?: return
        val host = host ?: return
        if (host.isClosing) return
        player?.pause()
        host.showStreamFailed(yt)
    }

    // --- the player ------------------------------------------------------------------

    private fun initializePlayer() {
        if (player != null) return
        // Device decoders first; FFmpeg (Jellyfin's prebuilt extension) only
        // for what the device can't do: E-AC3/AC3 on phones without a Dolby
        // licence, DTS, TrueHD. Decoder fallback tries another codec
        // instance if the first one fails to start.
        val renderers = SyncedRenderersFactory(context, tuning)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)
        val exo = ExoPlayer.Builder(context, renderers)
            .apply {
                // Video-only + audio merged, read in byte windows; other items go to the default factory.
                if (youtube != null) setMediaSourceFactory(com.abhinavxt.debforge.player.youtube.YouTubeMediaSourceFactory(context))
            }
            // Pause for calls / other apps' audio, and when headphones come out.
            .setAudioAttributes(
                androidx.media3.common.AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        exo.addListener(listener)
        // Back from the background: same tracks and speed as before.
        savedTrackParams?.let { exo.trackSelectionParameters = it }
        exo.setPlaybackSpeed(savedSpeed)
        exo.setMediaItem(buildMediaItem())
        exo.seekTo(resumePosition)
        exo.playWhenReady = resumePlayWhenReady
        exo.prepare()
        exo.addAnalyticsListener(stats.analytics)
        view?.player = exo
        player = exo
        episodePlayer = EpisodePlayer(
            exo,
            hasNext = { episodes.hasNext },
            hasPrevious = { episodes.hasPrevious },
            onNext = ::playNext,
            onPrevious = ::playPrevious
        )
        openMediaSession()
        effects.attach(exo.audioSessionId)
        tracks.applySubtitleStyle()
        actions.startLoop() // an A-B loop set before leaving the app
    }

    /** The current file, with the torrent's own subtitle files (pick them with the CC button). */
    internal fun buildMediaItem(): MediaItem =
        MediaItem.Builder()
            .setUri(uri)
            .apply { youtube?.let { setTag(com.abhinavxt.debforge.player.youtube.YouTubeStreamTag(it.audioUrl)) } }
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
            .setSubtitleConfigurations(subtitles.map { sub ->
                MediaItem.SubtitleConfiguration.Builder(Uri.parse(sub.url))
                    .setMimeType(sub.mimeType)
                    .setLanguage(sub.language)
                    .setLabel(sub.label)
                    // Forced subs (signs, foreign dialogue) show without being picked.
                    // Subtitles chosen in the YouTube preview are shown from the start.
                    .setSelectionFlags(if (sub.forced) C.SELECTION_FLAG_FORCED else if (youtube != null) C.SELECTION_FLAG_DEFAULT else 0)
                    .build()
            })
            .build()

    private fun releasePlayer() {
        val exo = player ?: return
        if (exo.playbackState != Player.STATE_ENDED) scrobble(scrobbler::stopped)
        resumePosition = exo.currentPosition
        resumePlayWhenReady = exo.playWhenReady
        releasedDurationMs = exo.duration.takeIf { it != C.TIME_UNSET } ?: releasedDurationMs
        savedTrackParams = exo.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false).build()
        loopMessage = null
        savedSpeed = exo.playbackParameters.speed
        effects.release()
        closeMediaSession()
        episodePlayer = null
        exo.removeAnalyticsListener(stats.analytics)
        exo.removeListener(listener)
        exo.release()
        view?.player = null
        player = null
    }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updatePip()
            if (isPlaying) scrobble(scrobbler::playing)
        }

        // The user's pause, not buffering (which also stops isPlaying for a moment).
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!playWhenReady && player?.playbackState != Player.STATE_ENDED) scrobble(scrobbler::paused)
        }

        /** Portrait video (phone recordings, shorts): allow portrait instead. */
        override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
            if (videoSize.width <= 0 || videoSize.height <= 0) return
            updatePip()
            val displayWidth = videoSize.width * videoSize.pixelWidthHeightRatio
            host?.videoShape(portrait = videoSize.height > displayWidth)
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_ENDED) {
                savePosition(ended = true)
                scrobble(scrobbler::stopped, ended = true)
                // Reached the end before the countdown did (e.g. seeked there).
                if (controls.sleepAtEnd) {
                    sleepNow()
                    return
                }
                episodes.ended()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            if (youtube != null) { refresher.onYouTubeError(error); return }
            when (PlayerErrors.kindOf(error.errorCode)) {
                PlayerErrors.Kind.UNSUPPORTED -> offerExternal(string(R.string.player_unsupported_body))
                // Most likely an expired link or a dropped connection.
                PlayerErrors.Kind.NETWORK -> refresher.refreshLinkOrAsk()
                PlayerErrors.Kind.OTHER -> offerExternal(string(R.string.player_error_body, error.errorCodeName))
            }
        }

        override fun onAudioSessionIdChanged(audioSessionId: Int) = effects.attach(audioSessionId)

        override fun onTracksChanged(tracks: Tracks) {
            controls.tracksVersion++
            this@VideoSession.tracks.selectPending(tracks)
            if (!tracksApplied && !tracks.isEmpty) {
                tracksApplied = true
                val memory = remembered
                scope.launch { this@VideoSession.tracks.applyChoices(tracks, memory?.await()) }
            }
            /*
             * Video plays but none of its audio tracks can be decoded here (DTS,
             * TrueHD…): ExoPlayer would silently play without sound.
             */
            val audio = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
            if (audio.isNotEmpty() && audio.none { it.isSupported }) {
                offerExternal(string(R.string.player_no_audio_body), videoProblem = false)
            }
        }
    }

    /** Session while watching, so headset / watch / output-switcher controls work here too. */
    private fun openMediaSession() {
        if (mediaSession != null || lifecycle.inBackground) return
        val p = episodePlayer ?: return
        mediaSession = runCatching {
            androidx.media3.session.MediaSession.Builder(context, p).setId(SESSION_ID).build()
        }.getOrNull()
    }

    private fun closeMediaSession() {
        mediaSession?.release()
        mediaSession = null
    }

    companion object {
        private const val SAVE_EVERY_TICKS = 5
        /** The background service's session uses the default id; the two never clash. */
        private const val SESSION_ID = "player"
        private const val START_OVER_VISIBLE_MS = 8_000L
    }
}
