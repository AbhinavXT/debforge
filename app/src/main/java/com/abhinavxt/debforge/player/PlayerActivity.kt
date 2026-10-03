package com.abhinavxt.debforge.player

import android.app.AlertDialog
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Rational
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
import androidx.lifecycle.lifecycleScope
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.abhinavxt.debforge.R
import com.abhinavxt.debforge.data.playback.PlaybackEntity
import com.abhinavxt.debforge.data.playback.PlaybackPositions
import com.abhinavxt.debforge.data.repository.DownloadsRepository
import com.abhinavxt.debforge.domain.DataResult
import com.abhinavxt.debforge.domain.IntroSkip
import com.abhinavxt.debforge.player.controls.SKIP_INTRO_SECONDS
import com.abhinavxt.debforge.domain.LinkRefresh
import com.abhinavxt.debforge.domain.ProviderId
import com.abhinavxt.debforge.domain.Resume
import com.abhinavxt.debforge.domain.SubtitleLink
import com.abhinavxt.debforge.player.controls.ControlsActions
import com.abhinavxt.debforge.player.controls.ControlsState
import com.abhinavxt.debforge.player.controls.END_OF_EPISODE
import com.abhinavxt.debforge.player.controls.Fill
import com.abhinavxt.debforge.player.controls.PlaybackSnapshot
import com.abhinavxt.debforge.player.controls.PlayerControls
import com.abhinavxt.debforge.player.controls.TrackChoice
import com.abhinavxt.debforge.player.controls.fillLabel
import com.abhinavxt.debforge.player.gestures.GestureEngine
import com.abhinavxt.debforge.player.tracks.SubtitleStyle
import com.abhinavxt.debforge.player.tracks.TrackPicker
import kotlinx.coroutines.async
import com.abhinavxt.debforge.player.gestures.GestureMath
import com.abhinavxt.debforge.player.gestures.GestureOverlay
import com.abhinavxt.debforge.player.gestures.GestureUiState
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.abhinavxt.debforge.ui.browse.PlayRequest
import com.abhinavxt.debforge.ui.browse.playExternally

/**
 * DebForge's own video player (Media3 ExoPlayer + PlayerView). On phones and
 * tablets the controls are DebForge's own (player/controls: title bar with
 * audio / subtitle / speed pickers, play / pause and ±10 s, seek bar with
 * time left, lock, fill, rotate, picture-in-picture, skip 85 s, next
 * episode; More: sleep timer, screenshot, A-B loop, keep playing in the
 * background). Android TV keeps Media3's controller, made for the D-pad.
 *
 * Decoding uses the device's own codecs, with an FFmpeg audio decoder
 * (Jellyfin's prebuilt Media3 extension) for what the device can't handle:
 * E-AC3/AC3 on phones without a Dolby licence, DTS, TrueHD. If something
 * still can't be decoded (e.g. HEVC 10-bit on an old phone), the user is
 * offered their external player (VLC, mpv…) instead of a black screen or
 * silent video.
 *
 * Touch gestures (player/gestures): double-tap the sides to skip, swipe for
 * brightness / volume or to scrub, hold for fast playback, pinch to zoom.
 * Touches on the controller's buttons and seek bar still go to them.
 *
 * Positions are saved per file (every few seconds, on leaving, at the end)
 * and restored on the next play, with a "Start over" chip. Later steps: the
 * torrent's own subtitle files, next episode, link refresh.
 */
@OptIn(UnstableApi::class)
@AndroidEntryPoint
class PlayerActivity : ComponentActivity() {

    @Inject lateinit var positions: PlaybackPositions
    @Inject lateinit var upNext: UpNext
    @Inject lateinit var subtitleResolver: SubtitleResolver
    @Inject lateinit var repository: DownloadsRepository
    @Inject lateinit var settings: com.abhinavxt.debforge.data.prefs.SettingsStore
    @Inject lateinit var trackMemory: com.abhinavxt.debforge.data.playback.TrackMemory
    @Inject lateinit var scrobbler: TraktScrobbler
    @Inject lateinit var openSubtitles: com.abhinavxt.debforge.data.subtitles.OpenSubtitlesRepository
    @Inject lateinit var movieHasher: MovieHasher
    @Inject lateinit var chapterReader: ChapterReader

    /** Settings → "Autoplay next episode". Off: the card still offers Play now, without a countdown. */
    private var autoplay = true

    // --- picture-in-picture ---
    /** The device has PiP (most TVs don't) and Settings allow it. */
    private val pipSupported by lazy { packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) }
    private var pipAllowed = true

    /** Play/pause and "Next episode" buttons in the floating window. */
    private val pipReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_PIP_PLAY_PAUSE -> player?.let { if (it.isPlaying) it.pause() else it.play() }
                ACTION_PIP_NEXT -> playNext()
            }
        }
    }

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var titleView: TextView
    private lateinit var startOverView: TextView

    /** Which file this is (null for plain links): positions are saved only when known. */
    private var identity: Identity? = null
    private var tickJob: Job? = null

    // --- next episode ---
    /** Episodes after this one (same show), best copy of each. */
    private val queue = ArrayDeque<QueuedEpisode>()
    /** Episodes before this one, nearest first ("Previous episode"). */
    private val previousQueue = ArrayDeque<QueuedEpisode>()
    /** The episode playing, so going to the next or previous one can come back to it. */
    private var currentEpisode: QueuedEpisode? = null
    /** The next episode with its links fetched ahead of time. */
    private var prepared: Prepared? = null
    private var prefetchJob: Job? = null
    /** User tapped Cancel on the card: no autoplay for this file. */
    private var nextCancelled = false
    /** Switching files: the countdown and "ended" can both fire; only one may. */
    private var switching = false
    private lateinit var nextPlayNow: View

    // --- touch gestures ---
    /** Settings → "Touch gestures". */
    private var gesturesEnabled = true
    private var gestureEngine: GestureEngine? = null
    /** The current touch belongs to the gestures (not to a button). */
    private var gesturing = false
    private val gestureUi = GestureUiState()
    /** Compose layer over the video: DebForge's controls (not on TV) and the gesture feedback. */
    private lateinit var gestureOverlay: androidx.compose.ui.platform.ComposeView
    /** DebForge's own controls. Android TV keeps Media3's, made for the remote. */
    private val controls = ControlsState()
    /** The user turned the screen with the rotate button: stop following the video's shape. */
    private var userRotated = false
    private val trackNames by lazy { androidx.media3.ui.DefaultTrackNameProvider(resources) }

    // --- tracks, subtitles, volume boost ---
    /** This file's tracks have been chosen (preferences / last time); only once per file. */
    private var tracksApplied = false
    /** What was chosen last time for this show / file, loading. */
    private var remembered: kotlinx.coroutines.Deferred<TrackPicker.Remembered?>? = null
    /** Kept across the player being recreated (app switch), with the speed. */
    private var savedTrackParams: androidx.media3.common.TrackSelectionParameters? = null
    private var savedSpeed = 1f
    /** Volume past the phone's maximum (1 = none, up to [GestureMath.MAX_VOLUME_LEVEL]). */
    private var boostLevel = 1f
    private var loudness: android.media.audiofx.LoudnessEnhancer? = null
    private var nightMode: NightMode? = null
    private var audioSessionId = C.AUDIO_SESSION_ID_UNSET
    /** Sync and decoder choice, read by the renderers ([SyncedRenderersFactory]). */
    private val tuning = RendererTuning()

    // --- media session (headset, watch, lock screen, output switcher) ---
    /** The player with next / previous mapped to episodes; what sessions control. */
    private var episodePlayer: EpisodePlayer? = null
    /** While watching; the background service has its own while away. */
    private var session: androidx.media3.session.MediaSession? = null

    // --- stats for nerds ---
    private lateinit var statsView: TextView
    private var videoDecoder: String? = null
    private var audioDecoder: String? = null
    private var droppedFrames = 0
    private var bandwidthBps = 0L
    /** This file's OpenSubtitles hash, worked out on the first search. */
    private var movieHash: kotlinx.coroutines.Deferred<String?>? = null
    private var onlineSubsJob: Job? = null
    /** A subtitle just added from OpenSubtitles: switch to it once the reloaded file lists it. */
    private var pendingTextLabel: String? = null

    // --- skip intro / recap / credits ---
    /** This file's chapters (Matroska only); intro, recap and credits come from their names. */
    private var chapters: List<com.abhinavxt.debforge.domain.MkvChapters.Chapter> = emptyList()
    private var segments: List<IntroSkip.Segment> = emptyList()
    private var segmentsFor = -1L
    /** Where the intro was skipped in an earlier episode of this show. */
    private var learntIntroAt: Long? = null
    /** Start of each segment already skipped or passed on in this file ([LEARNT_SKIP] for the learnt one). */
    private val doneSkips = HashSet<Long>()
    private var autoSkipIntro = false
    /** Bumped per file, so a slow chapter read for the last one is dropped. */
    private var fileGeneration = 0
    private lateinit var skipView: TextView

    // --- extras ---
    /** The player was handed to [BackgroundPlaybackService]: the sound goes on while we're away. */
    private var inBackground = false
    private var sleepJob: Job? = null
    /** Jumps back to A whenever playback reaches B. */
    private var loopMessage: androidx.media3.exoplayer.PlayerMessage? = null
    // Seek-bar / swipe scrubbing.
    private var scrubAnchor = 0L
    private var scrubTarget = 0L
    private var scrubWasPlaying = false
    private var lastScrubSeek = 0L
    private val gestureHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val gestureTimer = Runnable {
        gestureEngine?.timer(android.os.SystemClock.uptimeMillis())
        scheduleGestureTimer()
    }
    /** Pinch zoom of the picture (1 = fit). */
    private var zoom = 1f
    private val hideJobs = HashMap<String, Job>()

    /** Quiet link refreshes after network errors (expired debrid links). */
    private var refreshState = LinkRefresh.State()
    private var refreshing = false

    /** Android TV: move focus to overlays when they appear (no touch screen). */
    private val isTv by lazy {
        (getSystemService(UI_MODE_SERVICE) as android.app.UiModeManager).currentModeType ==
            android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
    }

    /** Back while "Next: …" is showing cancels it instead of leaving the player. */
    private val cancelNextOnBack = object : androidx.activity.OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            nextCancelled = true
            hideNextCard()
        }
    }
    /** When the card appeared (elapsedRealtime), for its countdown; null = hidden. */
    private var cardShownAt: Long? = null
    private lateinit var nextCard: View
    private lateinit var nextTitle: TextView
    private lateinit var nextCountdown: TextView

    private data class Prepared(val episode: QueuedEpisode, val url: String, val subtitles: List<SubtitleLink>)

    private lateinit var uri: Uri
    private var title: String = ""
    private var subtitles: List<SubtitleLink> = emptyList()

    // Survive onStop/onStart (app switch) and rotation-free recreation.
    private var resumePosition = 0L
    private var resumePlayWhenReady = true
    /** Asked once per file whether to switch players: don't nag on every track change. */
    private var offeredFallback = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        uri = intent.data ?: run { finish(); return }
        title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        identity = Identity.from(intent)
        subtitles = subtitlesFrom(intent)
        identity?.let { id ->
            val q = upNext.take(id.itemId)
            queue.addAll(q.next)
            previousQueue.addAll(q.previous)
            currentEpisode = q.current
        }
        startFile()
        lifecycleScope.launch {
            autoplay = settings.autoplayNextFlow.first()
            pipAllowed = settings.pipFlow.first()
            gesturesEnabled = settings.playerGesturesFlow.first()
            controls.showRemaining = settings.playerShowRemainingFlow.first()
            controls.subStyle = SubtitleStyle.decode(settings.subtitleStyleFlow.first())
            controls.background = settings.playerBackgroundFlow.first()
            controls.onlineSubsAvailable = !isTv && openSubtitles.enabled.first()
            autoSkipIntro = settings.autoSkipIntroFlow.first()
            controls.nightMode = settings.nightModeFlow.first()
            applyNightMode()
            applySubtitleStyle()
            controls.pipAvailable = pipSupported && pipAllowed
            updatePip()
        }
        ContextCompat.registerReceiver(
            this, pipReceiver,
            IntentFilter().apply { addAction(ACTION_PIP_PLAY_PAUSE); addAction(ACTION_PIP_NEXT) },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        savedInstanceState?.let {
            resumePosition = it.getLong(STATE_POSITION)
            resumePlayWhenReady = it.getBoolean(STATE_PLAY_WHEN_READY, true)
            offeredFallback = it.getBoolean(STATE_OFFERED, false)
        }

        // Video is landscape: open that way (either side up, following the
        // sensor). Portrait videos switch to portrait once their size is known.
        if (savedInstanceState == null) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.decorView.setBackgroundColor(Color.BLACK)

        playerView = PlayerView(this).apply {
            // Phones and tablets: DebForge's controls (PlayerControls). TV: Media3's, for the remote.
            useController = isTv
            // A spinner over the video while it loads (Media3's controller showed it on its own).
            if (!isTv) setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            setShowSubtitleButton(true)
            setShowNextButton(false)
            setShowPreviousButton(false)
            keepScreenOn = true
            setBackgroundColor(Color.BLACK)
        }
        titleView = TextView(this).apply {
            text = title
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            val pad = dp(16)
            setPadding(pad, pad, pad, dp(28))
            // Soft top scrim so the title reads over bright video.
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x99000000.toInt(), Color.TRANSPARENT)
            )
        }
        controls.title = title
        if (!isTv) titleView.visibility = View.GONE // the controls have their own title bar
        // TV: the title shows and hides together with Media3's controls.
        playerView.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
            titleView.visibility = visibility
            if (visibility == View.VISIBLE) showSystemBars() else hideSystemBars()
        })

        // "Start over" after resuming mid-way; hides itself after a few seconds.
        startOverView = TextView(this).apply {
            text = getString(R.string.player_start_over)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            val h = dp(16); val v = dp(10)
            setPadding(h, v, h, v)
            background = focusable(0xCC202020.toInt(), dp(20))
            visibility = View.GONE
            isFocusable = true // reachable with the TV remote
            setOnClickListener {
                player?.seekTo(0)
                visibility = View.GONE
            }
        }

        nextCard = buildNextCard()

        // Stats for nerds (More, or the remote's Info key on TV).
        statsView = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = android.graphics.Typeface.MONOSPACE
            val pad = dp(10)
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(0xB3000000.toInt())
            visibility = View.GONE
        }

        // "Skip intro" / "Skip recap" / "Skip credits": focusable for the TV remote.
        skipView = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            val h = dp(20); val v = dp(12)
            setPadding(h, v, h, v)
            background = focusable(0xE6202020.toInt(), dp(24))
            visibility = View.GONE
            isFocusable = true
        }

        // What the gestures show (seek taps, levels, speed…). Never takes touches.
        gestureOverlay = androidx.compose.ui.platform.ComposeView(this).apply {
            setContent {
                val dynamic by settings.dynamicColorFlow.collectAsState(initial = false)
                val themeName by settings.appThemeFlow.collectAsState(initial = null)
                com.abhinavxt.debforge.ui.theme.DebforgeTheme(
                    darkTheme = true,
                    dynamicColor = dynamic,
                    appTheme = com.abhinavxt.debforge.ui.theme.AppTheme.fromName(themeName)
                ) {
                    androidx.compose.foundation.layout.Box {
                        if (!isTv) PlayerControls(controls, controlsActions)
                        GestureOverlay(gestureUi)
                    }
                }
            }
        }
        gestureEngine = GestureEngine(
            GestureEngine.Config(
                width = resources.displayMetrics.widthPixels.toFloat(),
                height = resources.displayMetrics.heightPixels.toFloat(),
                density = resources.displayMetrics.density
            ),
            GestureActions()
        )

        setContentView(FrameLayout(this).apply {
            addView(playerView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(gestureOverlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(
                titleView,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP)
            )
            addView(
                startOverView,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START)
                    .apply { setMargins(dp(24), 0, 0, dp(if (isTv) 96 else 150)) }
            )
            addView(
                statsView,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START)
                    .apply { setMargins(dp(16), dp(72), dp(16), 0) }
            )
            addView(
                skipView,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END)
                    .apply { setMargins(0, 0, dp(24), dp(if (isTv) 96 else 150)) }
            )
            addView(
                nextCard,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END)
                    .apply { setMargins(0, 0, dp(24), dp(if (isTv) 96 else 150)) }
            )
        })
        hideSystemBars()
        onBackPressedDispatcher.addCallback(this, cancelNextOnBack)

        // Fresh start (not a rotation/recreate): pick up where the user left off.
        if (savedInstanceState == null) restoreSavedPosition()
    }

    /** Looks up the saved position; seeks there once known, unless the user already moved. */
    private fun restoreSavedPosition() {
        val id = identity ?: return
        lifecycleScope.launch {
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
            startOverView.visibility = View.VISIBLE
            if (isTv) startOverView.requestFocus()
            delay(START_OVER_VISIBLE_MS)
            startOverView.visibility = View.GONE
        }
    }

    /**
     * Once a second: the next-episode card and prefetch; every few seconds
     * while playing, save the position (so a crash or a swipe-away loses little).
     */
    private fun startTicking() {
        tickJob?.cancel()
        tickJob = lifecycleScope.launch {
            var ticks = 0
            while (true) {
                delay(1_000)
                ticks++
                if (ticks % SAVE_EVERY_TICKS == 0 && player?.isPlaying == true) savePosition()
                controls.hasNext = queue.isNotEmpty()
                controls.hasPrevious = previousQueue.isNotEmpty()
                updateNextEpisode()
                updateSkip()
                if (controls.stats) updateStats()
            }
        }
    }

    /**
     * Once a second: offer to skip the intro / recap / credits playing now
     * (from the file's chapters, or where the user skipped this show's intro
     * before), or skip it by itself when Settings say so.
     */
    private fun updateSkip() {
        val p = player
        val duration = durationOrNull()
        if (p == null || duration == null || switching || cardShownAt != null || isInPictureInPictureMode) {
            hideSkip()
            return
        }
        if (segmentsFor != duration && chapters.isNotEmpty()) {
            segments = IntroSkip.fromChapters(chapters, duration)
            segmentsFor = duration
        }
        val position = p.currentPosition
        val segment = IntroSkip.at(segments, position)?.takeIf { it.startMs !in doneSkips }
        if (segment != null) {
            if (autoSkipIntro && (segment.kind == IntroSkip.Kind.INTRO || segment.kind == IntroSkip.Kind.RECAP)) {
                doneSkips += segment.startMs
                hideSkip()
                skipSegment(segment)
                showMessage(getString(if (segment.kind == IntroSkip.Kind.INTRO) R.string.player_skipped_intro else R.string.player_skipped_recap))
                return
            }
            showSkip(
                when (segment.kind) {
                    IntroSkip.Kind.INTRO -> R.string.player_skip_intro
                    IntroSkip.Kind.RECAP -> R.string.player_skip_recap
                    IntroSkip.Kind.CREDITS -> R.string.player_skip_credits
                    IntroSkip.Kind.PREVIEW -> R.string.player_skip_preview
                }
            ) {
                doneSkips += segment.startMs
                skipSegment(segment)
            }
            return
        }
        // No intro chapter: offer it where this show's intro was skipped before.
        val learnt = learntIntroAt
        if (learnt != null && LEARNT_SKIP !in doneSkips && segments.none { it.kind == IntroSkip.Kind.INTRO } &&
            IntroSkip.inLearntWindow(learnt, position)
        ) {
            showSkip(R.string.player_skip_intro) {
                val at = player?.currentPosition ?: return@showSkip
                rememberIntro(at)
                player?.seekTo((at + SKIP_INTRO_SECONDS * 1000L).coerceAtMost(duration))
            }
            return
        }
        hideSkip()
    }

    /** Jumps past [segment]; credits running to the end go straight to the next episode. */
    private fun skipSegment(segment: IntroSkip.Segment) {
        val duration = durationOrNull() ?: return
        if (segment.endMs >= duration - END_SLACK_MS && queue.isNotEmpty()) playNext()
        else player?.seekTo(segment.endMs.coerceAtMost(duration))
    }

    /** The intro of this show is around [positionMs]: offer the skip there in the next episodes. */
    private fun rememberIntro(positionMs: Long) {
        doneSkips += LEARNT_SKIP
        if (identity?.showKey == null || !IntroSkip.isLikelyIntro(positionMs, durationOrNull() ?: 0)) return
        learntIntroAt = positionMs
        remember { it.copy(introAtMs = positionMs) }
    }

    private fun showSkip(textRes: Int, onSkip: () -> Unit) {
        val text = getString(textRes)
        skipView.setOnClickListener {
            hideSkip()
            onSkip()
        }
        if (skipView.visibility == View.VISIBLE && skipView.text == text) return
        skipView.text = text
        skipView.visibility = View.VISIBLE
        // TV: reachable with OK at once, unless the user is busy in the controller.
        if (isTv && !playerView.isControllerFullyVisible) skipView.requestFocus()
    }

    private fun hideSkip() {
        if (skipView.visibility != View.GONE) skipView.visibility = View.GONE
    }

    /** Prefetch near the end, then show the card and count down to the next episode. */
    private fun updateNextEpisode() {
        if (switching) return
        val p = player ?: return
        val next = queue.firstOrNull()
        val duration = p.duration.takeIf { it != C.TIME_UNSET && it > 0 }
        if (next == null || duration == null || nextCancelled) return hideNextCard()
        val remaining = duration - p.currentPosition
        if (remaining <= PREFETCH_BEFORE_END_MS) prefetch(next)
        if (remaining > CARD_BEFORE_END_MS) return hideNextCard() // e.g. seeked back
        val shownAt = cardShownAt ?: android.os.SystemClock.elapsedRealtime().also {
            cardShownAt = it
            nextTitle.text = getString(R.string.player_next, next.title)
            if (!isInPictureInPictureMode) nextCard.visibility = View.VISIBLE
            cancelNextOnBack.isEnabled = true
            if (isTv) nextPlayNow.requestFocus()
        }
        if (!autoplay || controls.sleepAtEnd) {
            // Offer only: the user starts it with Play now (or the sleep timer stops here).
            nextCountdown.visibility = View.GONE
            return
        }
        nextCountdown.visibility = View.VISIBLE
        val left = ((COUNTDOWN_MS - (android.os.SystemClock.elapsedRealtime() - shownAt)) / 1000).toInt()
        if (left <= 0) {
            playNext()
        } else {
            nextCountdown.text = resources.getQuantityString(R.plurals.player_next_in, left, left)
        }
    }

    private fun hideNextCard() {
        cardShownAt = null
        cancelNextOnBack.isEnabled = false
        if (::nextCard.isInitialized) nextCard.visibility = View.GONE
    }

    /** Rounded background with a white ring when focused (TV remote). */
    private fun focusable(color: Int, radius: Int): android.graphics.drawable.Drawable {
        fun shape(ring: Boolean) = GradientDrawable().apply {
            cornerRadius = radius.toFloat()
            setColor(color)
            if (ring) setStroke(dp(2), Color.WHITE)
        }
        return android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), shape(ring = true))
            addState(intArrayOf(), shape(ring = false))
        }
    }

    /**
     * Keys go to PlayerView (D-pad shows the controls, media keys play and
     * pause), except while one of DebForge's own overlays has focus.
     *
     * RestrictedApi is a lint false positive: this is Activity's public
     * dispatchKeyEvent, which androidx.core's ComponentActivity overrides and
     * marks library-internal.
     */
    @android.annotation.SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (!isTv && handlePhoneKey(event)) return true
        val focus = currentFocus
        // Media keys (remote, headset, keyboard): next / previous episode.
        if (event.action == android.view.KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                android.view.KeyEvent.KEYCODE_MEDIA_NEXT -> if (queue.isNotEmpty()) { playNext(); return true }
                android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS -> if (previousQueue.isNotEmpty()) { playPrevious(); return true }
                android.view.KeyEvent.KEYCODE_INFO -> { showStats(!controls.stats); return true }
            }
        }
        val overlayFocused = focus != null && (focus === startOverView || focus === skipView || isInside(focus, nextCard))
        if (!overlayFocused && ::playerView.isInitialized && playerView.dispatchKeyEvent(event)) return true
        return super.dispatchKeyEvent(event)
    }

    // --- gestures ---------------------------------------------------------------

    /**
     * Touches on the video go to the gestures; touches on a button, the seek
     * bar or one of DebForge's cards go to them as usual. Decided once per
     * touch, when the finger goes down.
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val engine = gestureEngine
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            // Even with gestures off, taps on the video still show / hide the controls.
            gesturing = engine != null && !isInPictureInPictureMode && !touchesControl(ev)
            if (gesturing && playerView.width > 0) engine?.resize(playerView.width.toFloat(), playerView.height.toFloat())
        }
        if (!gesturing || engine == null) return super.dispatchTouchEvent(ev)
        val t = ev.eventTime
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> engine.down(ev.x, ev.y, t)
            MotionEvent.ACTION_POINTER_DOWN -> engine.pointerDown(ev.pointerCount, span(ev), t)
            MotionEvent.ACTION_MOVE -> engine.move(ev.getX(0), ev.getY(0), t, ev.pointerCount, span(ev))
            MotionEvent.ACTION_POINTER_UP -> engine.pointerUp(ev.pointerCount - 1, t)
            MotionEvent.ACTION_UP -> engine.up(ev.x, ev.y, t)
            MotionEvent.ACTION_CANCEL -> engine.cancel(t)
        }
        scheduleGestureTimer()
        return true
    }

    private fun span(ev: MotionEvent): Float =
        if (ev.pointerCount < 2) 0f else kotlin.math.hypot(ev.getX(0) - ev.getX(1), ev.getY(0) - ev.getY(1))

    private fun scheduleGestureTimer() {
        gestureHandler.removeCallbacks(gestureTimer)
        val due = gestureEngine?.deadline ?: return
        gestureHandler.postDelayed(gestureTimer, (due - android.os.SystemClock.uptimeMillis()).coerceAtLeast(0))
    }

    /** Is ([ev].x, [ev].y) on something tappable: a controller button, the seek bar, a card? */
    private fun touchesControl(ev: MotionEvent): Boolean {
        val x = ev.x.toInt()
        val y = ev.y.toInt()
        if (hit(startOverView, x, y) || hit(nextCard, x, y)) return true
        if (!isTv) return controls.isOnControl(ev.x, ev.y)
        if (!playerView.isControllerFullyVisible) return false
        val content = playerView.findViewById<View>(androidx.media3.ui.R.id.exo_content_frame)
        fun find(v: View): Boolean {
            if (v === content || !hit(v, x, y)) return false
            if (v !== playerView && (v.hasOnClickListeners() || v is androidx.media3.ui.TimeBar)) return true
            return v is ViewGroup && (0 until v.childCount).any { find(v.getChildAt(it)) }
        }
        return find(playerView)
    }

    private fun hit(v: View, x: Int, y: Int): Boolean {
        if (!v.isShown) return false
        val r = android.graphics.Rect()
        return v.getGlobalVisibleRect(r) && r.contains(x, y)
    }

    /** Shows [key]'s feedback until [ms] after the last update, then [clear]s it. */
    private fun hideLater(key: String, ms: Long, clear: () -> Unit) {
        hideJobs[key]?.cancel()
        hideJobs[key] = lifecycleScope.launch {
            delay(ms)
            clear()
        }
    }

    private fun keepShowing(key: String) {
        hideJobs.remove(key)?.cancel()
    }

    private fun durationOrNull(): Long? = player?.duration?.takeIf { it != C.TIME_UNSET && it > 0 }

    private fun applyZoom() {
        val frame = playerView.findViewById<View>(androidx.media3.ui.R.id.exo_content_frame) ?: return
        val z = if (isInPictureInPictureMode) 1f else zoom
        frame.scaleX = z
        frame.scaleY = z
    }

    private fun haptic(constant: Int) {
        window.decorView.performHapticFeedback(constant)
    }

    /** Start dragging through the video (swipe or seek bar): pause, fast keyframe seeks. */
    private fun beginScrub() {
        val p = player ?: return
        scrubAnchor = p.currentPosition
        scrubTarget = scrubAnchor
        scrubWasPlaying = p.isPlaying
        p.pause()
        // Nearest keyframe while dragging: fast, even on a stream.
        p.setSeekParameters(androidx.media3.exoplayer.SeekParameters.CLOSEST_SYNC)
        keepShowing("scrub")
    }

    private fun scrubTo(target: Long) {
        val p = player ?: return
        scrubTarget = target
        gestureUi.scrub = GestureUiState.Scrub(target, target - scrubAnchor)
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastScrubSeek >= SCRUB_SEEK_EVERY_MS) {
            lastScrubSeek = now
            p.seekTo(target)
        }
    }

    /** Land exactly where the finger stopped ([cancel]: where it started) and play on. */
    private fun endScrub(cancel: Boolean) {
        val p = player ?: return
        p.setSeekParameters(androidx.media3.exoplayer.SeekParameters.DEFAULT)
        p.seekTo(if (cancel) scrubAnchor else scrubTarget)
        if (scrubWasPlaying) p.play()
        hideLater("scrub", 400) { gestureUi.scrub = null }
    }

    /** Brief text over the video ("Crop", "Fit"…). */
    private fun showMessage(text: String) {
        keepShowing("msg")
        gestureUi.message = text
        hideLater("msg", 1_200) { gestureUi.message = null }
    }

    private val controlsActions = object : ControlsActions {
        override fun snapshot(): PlaybackSnapshot {
            val p = player ?: return PlaybackSnapshot()
            return PlaybackSnapshot(
                isPlaying = p.isPlaying,
                buffering = p.playbackState == Player.STATE_BUFFERING,
                positionMs = p.currentPosition,
                bufferedMs = p.bufferedPosition,
                durationMs = durationOrNull() ?: 0L,
                speed = p.playbackParameters.speed
            )
        }

        override fun back() = onBackPressedDispatcher.onBackPressed()

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
            p.seekTo(GestureMath.seekTarget(p.currentPosition, ms > 0, (kotlin.math.abs(ms) / 1000).toInt(), durationOrNull()))
            keepShowing("seek")
            gestureUi.seekTaps = GestureUiState.SeekTaps(ms > 0, (kotlin.math.abs(ms) / 1000).toInt())
            hideLater("seek", 600) { gestureUi.seekTaps = null }
        }

        override fun skipIntro() {
            val p = player ?: return
            rememberIntro(p.currentPosition)
            seekBy(SKIP_INTRO_SECONDS * 1000L)
        }

        override fun scrubStart() = beginScrub()
        override fun scrubTo(ms: Long) = this@PlayerActivity.scrubTo(ms)
        override fun scrubEnd() = endScrub(cancel = false)
        override fun next() = playNext()
        override fun previous() = playPrevious()

        override fun setSubtitleDelay(ms: Long) {
            applyDelays(ms, controls.audioDelayMs)
            remember { it.copy(subtitleDelayMs = ms.takeIf { d -> d != 0L }) }
        }

        override fun setAudioDelay(ms: Long) {
            applyDelays(controls.subtitleDelayMs, ms)
            remember { it.copy(audioDelayMs = ms.takeIf { d -> d != 0L }) }
        }

        override fun setNightMode(on: Boolean) {
            controls.nightMode = on
            applyNightMode()
            lifecycleScope.launch { settings.setNightMode(on) }
        }

        override fun seekTo(ms: Long) {
            player?.seekTo(ms)
            controls.panel = null
        }

        override fun setStats(on: Boolean) = showStats(on)

        override fun setSoftwareDecoding(on: Boolean) = this@PlayerActivity.setSoftwareDecoding(on, save = true)
        override fun openExternal() = openExternally()

        override fun rotate() {
            userRotated = true
            requestedOrientation =
                if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                } else {
                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                }
        }

        override fun setFill(fill: Fill) {
            controls.fill = fill
            playerView.resizeMode = when (fill) {
                Fill.FIT -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                Fill.CROP -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                Fill.STRETCH -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
            }
            zoom = 1f // pinch zoom starts over from the new fill
            applyZoom()
            showMessage(getString(fillLabel(fill)))
        }

        override fun enterPip() {
            runCatching { enterPictureInPictureMode(pipParams()) }
        }

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
                remember { it.copy(audioLanguage = f.language, audioLabel = f.label) }
            } else if (type == C.TRACK_TYPE_TEXT) {
                remember { it.copy(textOff = f == null, textLanguage = f?.language, textLabel = f?.label) }
            }
        }

        override fun searchOnlineSubtitles(anyLanguage: Boolean) = this@PlayerActivity.searchOnlineSubtitles(anyLanguage)

        override fun pickOnlineSubtitle(sub: com.abhinavxt.debforge.data.subtitles.OnlineSubtitle) =
            this@PlayerActivity.pickOnlineSubtitle(sub)

        override fun setSpeed(speed: Float) {
            player?.setPlaybackSpeed(speed)
            controls.panel = null
            remember { it.copy(speed = speed.takeIf { s -> s != 1f }) }
        }

        override fun setSleep(minutes: Int?) {
            sleepJob?.cancel()
            controls.sleepEndsAt = 0L
            controls.sleepAtEnd = false
            controls.panel = null
            when (minutes) {
                null -> Unit
                END_OF_EPISODE -> controls.sleepAtEnd = true
                else -> {
                    val ms = minutes * 60_000L
                    controls.sleepEndsAt = android.os.SystemClock.elapsedRealtime() + ms
                    sleepJob = lifecycleScope.launch {
                        delay(ms)
                        sleepNow()
                    }
                }
            }
            if (minutes != null) {
                showMessage(
                    if (minutes == END_OF_EPISODE) getString(R.string.player_sleep_end)
                    else resources.getQuantityString(R.plurals.player_sleep_in_n, minutes, minutes)
                )
            }
        }

        override fun screenshot() {
            controls.panel = null
            takeScreenshot()
        }

        override fun abLoop() {
            val p = player ?: return
            val pos = p.currentPosition
            val a = controls.loopA
            when {
                a == null -> {
                    controls.loopA = pos
                    showMessage("A  " + GestureMath.time(pos))
                }
                controls.loopB == null -> {
                    if (pos <= a + MIN_LOOP_MS) return // B must come after A
                    controls.loopB = pos
                    startLoop()
                    p.seekTo(a)
                    showMessage("A\u2013B  " + GestureMath.time(a) + " \u2013 " + GestureMath.time(pos))
                }
                else -> clearLoop()
            }
            controls.panel = null
        }

        override fun setBackground(on: Boolean) {
            controls.background = on
            lifecycleScope.launch { settings.setPlayerBackground(on) }
        }

        override fun setSubtitleStyle(style: SubtitleStyle) {
            controls.subStyle = style
            applySubtitleStyle()
            lifecycleScope.launch { settings.setSubtitleStyle(style.encode()) }
        }

        override fun setShowRemaining(show: Boolean) {
            controls.showRemaining = show
            lifecycleScope.launch { settings.setPlayerShowRemaining(show) }
        }

        override fun onBarsVisible(visible: Boolean) {
            if (isInPictureInPictureMode) return
            if (visible) showSystemBars() else hideSystemBars()
        }
    }

    /** Sleep timer ran out (or the episode ended with "end of episode"). */
    private fun sleepNow() {
        sleepJob?.cancel()
        sleepJob = null
        controls.sleepEndsAt = 0L
        controls.sleepAtEnd = false
        player?.pause()
        showMessage(getString(R.string.player_sleep_done))
    }

    private fun startLoop() {
        val p = player ?: return
        val a = controls.loopA ?: return
        val b = controls.loopB ?: return
        loopMessage?.cancel()
        loopMessage = p.createMessage { _, _ -> player?.seekTo(a) }
            .setPosition(b)
            .setLooper(mainLooper)
            .setDeleteAfterDelivery(false)
            .send()
    }

    private fun clearLoop() {
        loopMessage?.cancel()
        loopMessage = null
        controls.loopA = null
        controls.loopB = null
    }

    /**
     * The current frame (without subtitles or controls) as a JPEG in
     * Pictures/DebForge. No permission needed: MediaStore owns the folder.
     */
    private fun takeScreenshot() {
        val surface = playerView.videoSurfaceView as? android.view.SurfaceView
        val v = player?.videoSize
        if (surface == null || v == null || v.width <= 0 || v.height <= 0) {
            showMessage(getString(R.string.player_screenshot_failed))
            return
        }
        val w = (v.width * v.pixelWidthHeightRatio).toInt().coerceAtLeast(1)
        val scale = minOf(1f, 3840f / maxOf(w, v.height))
        val bitmap = android.graphics.Bitmap.createBitmap(
            (w * scale).toInt().coerceAtLeast(1), (v.height * scale).toInt().coerceAtLeast(1), android.graphics.Bitmap.Config.ARGB_8888
        )
        android.view.PixelCopy.request(surface, bitmap, { result ->
            if (result != android.view.PixelCopy.SUCCESS) {
                showMessage(getString(R.string.player_screenshot_failed))
                return@request
            }
            lifecycleScope.launch {
                val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { saveImage(bitmap) }
                bitmap.recycle()
                showMessage(getString(if (ok) R.string.player_screenshot_saved else R.string.player_screenshot_failed))
            }
        }, gestureHandler)
    }

    private fun saveImage(bitmap: android.graphics.Bitmap): Boolean = runCatching {
        val safeTitle = title.replace(Regex("""[^\p{L}\p{N} ._-]"""), "_").trim().take(60).ifEmpty { "video" }
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "${safeTitle}_${System.currentTimeMillis()}.jpg")
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, android.os.Environment.DIRECTORY_PICTURES + "/DebForge")
            put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return@runCatching false
        val written = contentResolver.openOutputStream(uri)?.use { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, it) } == true
        if (!written) {
            contentResolver.delete(uri, null, null)
            return@runCatching false
        }
        values.clear()
        values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
        contentResolver.update(uri, values, null, null)
        true
    }.getOrDefault(false)

    /** A new file starts: its tracks get chosen once they're known. */
    private fun startFile() {
        tracksApplied = false
        clearLoop()
        movieHash = null
        chapters = emptyList()
        segmentsFor = -1L
        learntIntroAt = null
        doneSkips.clear()
        val file = identity?.filename?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment.orEmpty()
        val source = uri
        val generation = ++fileGeneration
        controls.chapters = emptyList()
        if (isTv && ::playerView.isInitialized) playerView.setExtraAdGroupMarkers(null, null)
        lifecycleScope.launch {
            val found = runCatching { chapterReader.chapters(source, file) }.getOrDefault(emptyList())
            if (generation != fileGeneration) return@launch // another file by now
            chapters = found
            controls.chapters = found
            // TV: Media3's seek bar marks them (its ad markers).
            if (isTv && ::playerView.isInitialized) {
                val marks = found.map { it.startMs }.filter { it > 0 }
                playerView.setExtraAdGroupMarkers(marks.toLongArray(), BooleanArray(marks.size))
            }
        }
        onlineSubsJob?.cancel()
        controls.onlineSubs = com.abhinavxt.debforge.player.controls.OnlineSubs.Idle
        pendingTextLabel = null
        val key = identity?.let { it.showKey ?: it.itemId }
        remembered = key?.let { k -> lifecycleScope.async { trackMemory.get(k) } }
        if (identity?.showKey != null) {
            val memory = remembered
            lifecycleScope.launch { learntIntroAt = memory?.await()?.introAtMs }
        }
        // Sync starts at zero, then last time's for this show (or file); the
        // decoder choice too (switching only if it differs, which reloads).
        applyDelays(0, 0)
        droppedFrames = 0
        lifecycleScope.launch {
            val r = remembered?.await()
            if (generation != fileGeneration) return@launch
            if (r != null) applyDelays(r.subtitleDelayMs ?: 0, r.audioDelayMs ?: 0)
            val software = r?.softwareDecoding == true
            if (software != tuning.preferSoftwareVideo) setSoftwareDecoding(software, save = false)
        }
    }

    private fun remember(change: (TrackPicker.Remembered) -> TrackPicker.Remembered) {
        val key = identity?.let { it.showKey ?: it.itemId } ?: return
        lifecycleScope.launch { trackMemory.update(key, change) }
    }

    /**
     * Audio and subtitles for a file that just started: last time's choice
     * for this show, else Settings' languages (skipping commentary and
     * "signs & songs"), else the file's own defaults. Also last time's speed.
     */
    private suspend fun applyTrackChoices(tracks: Tracks, memory: TrackPicker.Remembered?) {
        val p = player ?: return
        val audioLanguage = settings.audioLanguageFlow.first()
        val subtitleSetting = settings.subtitleLanguageFlow.first()
        fun options(type: Int) = tracks.groups.filter { it.type == type }.flatMap { g ->
            (0 until g.length).filter { g.isTrackSupported(it) }.map { i -> g to i }
        }
        fun candidate(f: androidx.media3.common.Format) = TrackPicker.Candidate(
            language = f.language,
            label = f.label,
            isDefault = f.selectionFlags and C.SELECTION_FLAG_DEFAULT != 0,
            isForced = f.selectionFlags and C.SELECTION_FLAG_FORCED != 0,
            commentary = f.roleFlags and (C.ROLE_FLAG_COMMENTARY or C.ROLE_FLAG_DESCRIBES_VIDEO) != 0
        )
        val audio = options(C.TRACK_TYPE_AUDIO)
        val text = options(C.TRACK_TYPE_TEXT)
        // A fresh start for this file: the last file's picks don't carry over.
        val params = p.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
        val audioChoice = TrackPicker.pickAudio(audio.map { (g, i) -> candidate(g.getTrackFormat(i)) }, audioLanguage, memory)
        if (audioChoice is TrackPicker.Choice.Pick) {
            val (g, i) = audio[audioChoice.index]
            params.setOverrideForType(androidx.media3.common.TrackSelectionOverride(g.mediaTrackGroup, i))
        }
        when (val c = TrackPicker.pickText(text.map { (g, i) -> candidate(g.getTrackFormat(i)) }, subtitleSetting, memory)) {
            TrackPicker.Choice.Off -> params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            is TrackPicker.Choice.Pick -> {
                val (g, i) = text[c.index]
                params.setOverrideForType(androidx.media3.common.TrackSelectionOverride(g.mediaTrackGroup, i))
            }
            TrackPicker.Choice.Leave -> Unit
        }
        p.trackSelectionParameters = params.build()
        memory?.speed?.let { if (it != p.playbackParameters.speed) p.setPlaybackSpeed(it) }
    }

    /**
     * Searches OpenSubtitles for this file: by its hash (exact timing) and
     * name, in the subtitle language from Settings (else the phone's), or
     * in any language.
     */
    private fun searchOnlineSubtitles(anyLanguage: Boolean) {
        onlineSubsJob?.cancel()
        controls.onlineSubs = com.abhinavxt.debforge.player.controls.OnlineSubs.Searching
        val info = com.abhinavxt.debforge.domain.ReleaseNameParser.parse(identity?.filename?.takeIf { it.isNotBlank() } ?: title)
        val source = uri
        onlineSubsJob = lifecycleScope.launch {
            val languages = if (anyLanguage) emptyList() else OnlineSubtitleLanguages.forSetting(
                settings.subtitleLanguageFlow.first(), java.util.Locale.getDefault()
            )
            controls.onlineSubs = try {
                // Not a child of this search: switching language mid-search mustn't cancel the shared hash.
                val hash = (movieHash ?: lifecycleScope.async { movieHasher.hashOf(source) }.also { movieHash = it }).await()
                com.abhinavxt.debforge.player.controls.OnlineSubs.Results(openSubtitles.search(info, hash, languages), anyLanguage)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                com.abhinavxt.debforge.player.controls.OnlineSubs.Failed(R.string.player_subs_search_failed, anyLanguage)
            }
        }
    }

    /** Downloads [sub], adds it to the file (reloaded at the same second) and switches to it. */
    private fun pickOnlineSubtitle(sub: com.abhinavxt.debforge.data.subtitles.OnlineSubtitle) {
        val results = controls.onlineSubs as? com.abhinavxt.debforge.player.controls.OnlineSubs.Results ?: return
        controls.onlineSubs = com.abhinavxt.debforge.player.controls.OnlineSubs.Downloading(results.subs, results.anyLanguage, sub.fileId)
        onlineSubsJob = lifecycleScope.launch {
            val file = try {
                openSubtitles.download(sub)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: com.abhinavxt.debforge.data.subtitles.OpenSubtitlesRepository.QuotaException) {
                controls.onlineSubs = com.abhinavxt.debforge.player.controls.OnlineSubs.Failed(R.string.player_subs_quota, results.anyLanguage)
                return@launch
            } catch (e: Exception) {
                controls.onlineSubs = results
                showMessage(getString(R.string.player_subs_download_failed))
                return@launch
            }
            controls.onlineSubs = results
            val language = sub.language?.let { java.util.Locale.forLanguageTag(it).displayName.ifBlank { it } }
            val label = getString(R.string.player_subs_online_label, language ?: getString(R.string.player_subtitles))
            // One online subtitle per label: picking another English one replaces the last.
            subtitles = subtitles.filterNot { it.label == label } + SubtitleLink(
                url = Uri.fromFile(file).toString(),
                mimeType = "application/x-subrip",
                language = sub.language?.substringBefore('-'),
                label = label,
                forced = false
            )
            pendingTextLabel = label
            controls.panel = null
            player?.let { p ->
                p.setMediaItem(buildMediaItem(), p.currentPosition)
                p.prepare()
            }
        }
    }

    /** After an OpenSubtitles file was added: turn it on as soon as it's listed. */
    private fun selectPendingSubtitle(tracks: Tracks) {
        val label = pendingTextLabel ?: return
        val p = player ?: return
        val target = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }.firstNotNullOfOrNull { g ->
            (0 until g.length).firstOrNull { g.getTrackFormat(it).label == label }?.let { g to it }
        } ?: return
        pendingTextLabel = null
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setOverrideForType(androidx.media3.common.TrackSelectionOverride(target.first.mediaTrackGroup, target.second))
            .build()
    }

    /** Size, colours and height of subtitles (the Subtitles panel). */
    private fun applySubtitleStyle() {
        val view = playerView.subtitleView ?: return
        val style = controls.subStyle
        view.setFractionalTextSize(style.size.fraction)
        view.setBottomPaddingFraction(style.position.bottomFraction)
        view.setApplyEmbeddedStyles(style.fileStyling)
        // Our size always: files' own sizes are often tiny or huge on a phone.
        view.setApplyEmbeddedFontSizes(false)
        val outline = androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_OUTLINE
        view.setStyle(
            when (style.look) {
                SubtitleStyle.Look.OUTLINE -> androidx.media3.ui.CaptionStyleCompat(
                    Color.WHITE, Color.TRANSPARENT, Color.TRANSPARENT, outline, Color.BLACK, android.graphics.Typeface.DEFAULT_BOLD
                )
                SubtitleStyle.Look.BOX -> androidx.media3.ui.CaptionStyleCompat(
                    Color.WHITE, 0xCC000000.toInt(), Color.TRANSPARENT,
                    androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_NONE, Color.TRANSPARENT, android.graphics.Typeface.DEFAULT
                )
                SubtitleStyle.Look.YELLOW -> androidx.media3.ui.CaptionStyleCompat(
                    0xFFFFEB3B.toInt(), Color.TRANSPARENT, Color.TRANSPARENT, outline, Color.BLACK, android.graphics.Typeface.DEFAULT_BOLD
                )
            }
        )
    }

    /** Volume boost past the phone's maximum, on the player's audio session. */
    private fun attachLoudness(sessionId: Int) {
        audioSessionId = sessionId
        loudness?.release()
        loudness = null
        applyNightMode()
        if (sessionId == C.AUDIO_SESSION_ID_UNSET) return
        loudness = runCatching { android.media.audiofx.LoudnessEnhancer(sessionId) }.getOrNull()
        applyBoost()
    }

    /** Night mode on the current audio session (re-attached when the session changes). */
    private fun applyNightMode() {
        nightMode?.release()
        nightMode = null
        if (!controls.nightMode || audioSessionId == C.AUDIO_SESSION_ID_UNSET) return
        nightMode = NightMode.attach(audioSessionId)
        if (nightMode == null) {
            controls.nightMode = false
            showMessage(getString(R.string.player_night_mode_unavailable))
        }
    }

    private fun showStats(on: Boolean) {
        controls.stats = on
        statsView.visibility = if (on) View.VISIBLE else View.GONE
        if (on) updateStats()
    }

    private fun updateStats() {
        val p = player ?: return
        fun hdr(f: androidx.media3.common.Format): String? = when {
            f.sampleMimeType == androidx.media3.common.MimeTypes.VIDEO_DOLBY_VISION -> "Dolby Vision"
            f.colorInfo?.colorTransfer == C.COLOR_TRANSFER_ST2084 -> "HDR10"
            f.colorInfo?.colorTransfer == C.COLOR_TRANSFER_HLG -> "HLG"
            else -> null
        }
        val v = p.videoFormat
        val a = p.audioFormat
        statsView.text = PlaybackStats.format(
            PlaybackStats.Snapshot(
                video = v?.let {
                    PlaybackStats.Video(it.sampleMimeType, it.codecs, it.width, it.height, it.frameRate, it.bitrate, hdr(it))
                },
                videoDecoder = videoDecoder,
                audio = a?.let { PlaybackStats.Audio(it.sampleMimeType, it.channelCount, it.sampleRate, it.bitrate, it.language) },
                audioDecoder = audioDecoder,
                bufferAheadMs = p.bufferedPosition - p.currentPosition,
                networkBps = bandwidthBps,
                droppedFrames = droppedFrames,
                subtitleDelayMs = tuning.subtitleDelayMs,
                audioDelayMs = tuning.audioDelayMs
            )
        )
    }

    /**
     * Video on the CPU (Android's own decoders) instead of the chip's, for a
     * file the hardware decoder shows badly. Decoders are picked when they
     * start, so the file reloads at the same second. [save]: remember it for
     * this show (or file).
     */
    private fun setSoftwareDecoding(on: Boolean, save: Boolean) {
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

    private fun applyDelays(subtitleMs: Long, audioMs: Long) {
        tuning.subtitleDelayMs = subtitleMs
        tuning.audioDelayMs = audioMs
        controls.subtitleDelayMs = subtitleMs
        controls.audioDelayMs = audioMs
    }

    private fun applyBoost() {
        val gain = GestureMath.boostGainMb(boostLevel)
        loudness?.let { l ->
            runCatching {
                l.setTargetGain(gain)
                l.enabled = gain > 0
            }
        }
    }

    /** What each gesture does to the player. */
    private inner class GestureActions : GestureEngine.Callbacks {
        private var seekAnchor: Long? = null
        private var speedBefore = 1f
        private var zoomAtStart = 1f
        private var brightness = 0.5f
        private var volume = 0f
        private val audio by lazy { getSystemService(AUDIO_SERVICE) as android.media.AudioManager }

        override fun onSingleTap() {
            if (!isTv) controls.toggle()
            else if (playerView.isControllerFullyVisible) playerView.hideController() else playerView.showController()
        }

        override fun onCenterDoubleTap() {
            if (!gesturesEnabled) return onSingleTap()
            val p = player ?: return
            if (p.isPlaying) p.pause() else p.play()
        }

        override fun onSeekTaps(forward: Boolean, seconds: Int) {
            if (!gesturesEnabled) return
            val p = player ?: return
            val anchor = seekAnchor ?: p.currentPosition.also { seekAnchor = it }
            p.seekTo(GestureMath.seekTarget(anchor, forward, seconds, durationOrNull()))
            keepShowing("seek")
            gestureUi.seekTaps = GestureUiState.SeekTaps(forward, seconds)
        }

        override fun onSeekTapsEnd() {
            seekAnchor = null
            hideLater("seek", 300) { gestureUi.seekTaps = null }
        }

        override fun onVerticalStart(side: GestureEngine.Side) {
            if (!gesturesEnabled) return
            if (side == GestureEngine.Side.LEFT) {
                val current = window.attributes.screenBrightness
                brightness = if (current >= 0f) current else systemBrightness()
            } else {
                val max = audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                val system = audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
                // At the maximum with a boost on: carry on from the boost.
                volume = if (system >= max && boostLevel > 1f) boostLevel else system.toFloat() / max
            }
        }

        override fun onVertical(side: GestureEngine.Side, delta: Float) {
            if (!gesturesEnabled) return
            keepShowing("level")
            if (side == GestureEngine.Side.LEFT) {
                brightness = GestureMath.level(brightness, delta, min = 0.01f)
                window.attributes = window.attributes.apply { screenBrightness = brightness }
                gestureUi.level = GestureUiState.Level(GestureUiState.LevelKind.BRIGHTNESS, brightness)
            } else {
                // Past 100 % (with the loudness enhancer available): boost.
                val top = if (loudness != null) GestureMath.MAX_VOLUME_LEVEL else 1f
                volume = GestureMath.level(volume, delta, max = top)
                val max = audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
                val newBoost = volume.coerceAtLeast(1f)
                if (newBoost != boostLevel) {
                    boostLevel = newBoost
                    applyBoost()
                }
                val index = kotlin.math.round(volume.coerceAtMost(1f) * max).toInt()
                if (index != audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)) {
                    // Can be refused (Do Not Disturb): the bar still follows the finger.
                    runCatching { audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, index, 0) }
                }
                gestureUi.level = GestureUiState.Level(GestureUiState.LevelKind.VOLUME, volume, max = top)
            }
        }

        override fun onVerticalEnd(side: GestureEngine.Side) {
            hideLater("level", 700) { gestureUi.level = null }
        }

        private fun systemBrightness(): Float =
            runCatching {
                android.provider.Settings.System.getInt(contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS) / 255f
            }.getOrDefault(0.5f)

        private var scrubbing = false

        override fun onScrubStart() {
            if (!gesturesEnabled) return
            scrubbing = true
            beginScrub()
        }

        override fun onScrub(fraction: Float) {
            if (scrubbing) scrubTo(GestureMath.scrubTarget(scrubAnchor, fraction, durationOrNull()))
        }

        override fun onScrubEnd() {
            if (scrubbing) endScrub(cancel = false)
            scrubbing = false
        }

        override fun onScrubCancel() {
            if (scrubbing) endScrub(cancel = true)
            scrubbing = false
        }

        override fun onLongPressStart(): Boolean {
            if (!gesturesEnabled) return false
            val p = player ?: return false
            if (!p.isPlaying) return false
            speedBefore = p.playbackParameters.speed
            p.setPlaybackSpeed(GestureMath.HOLD_SPEED)
            gestureUi.speed = GestureMath.HOLD_SPEED
            haptic(android.view.HapticFeedbackConstants.LONG_PRESS)
            return true
        }

        override fun onLongPressSlide(steps: Int) {
            val speed = GestureMath.speedAfterSlide(steps)
            if (speed == gestureUi.speed) return
            player?.setPlaybackSpeed(speed)
            gestureUi.speed = speed
            haptic(android.view.HapticFeedbackConstants.CLOCK_TICK)
        }

        override fun onLongPressEnd() {
            player?.setPlaybackSpeed(speedBefore)
            gestureUi.speed = null
        }

        private var pinching = false

        override fun onPinchStart() {
            pinching = gesturesEnabled
            zoomAtStart = zoom
            keepShowing("zoom")
        }

        override fun onPinch(scale: Float) {
            if (!pinching) return
            zoom = GestureMath.zoom(zoomAtStart, scale)
            applyZoom()
            gestureUi.zoomPercent = (zoom * 100).toInt()
        }

        override fun onPinchEnd() {
            if (!pinching) return
            pinching = false
            zoom = GestureMath.snapZoom(zoom)
            applyZoom()
            gestureUi.zoomPercent = (zoom * 100).toInt()
            hideLater("zoom", 800) { gestureUi.zoomPercent = null }
        }
    }

    /** A keyboard or media buttons on a phone (Media3's controller, which handled them, is off). */
    private fun handlePhoneKey(event: android.view.KeyEvent): Boolean {
        val p = player ?: return false
        val down = event.action == android.view.KeyEvent.ACTION_DOWN
        when (event.keyCode) {
            android.view.KeyEvent.KEYCODE_SPACE, android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            android.view.KeyEvent.KEYCODE_HEADSETHOOK -> if (down) controlsActions.playPause()
            android.view.KeyEvent.KEYCODE_MEDIA_PLAY -> if (down) p.play()
            android.view.KeyEvent.KEYCODE_MEDIA_PAUSE -> if (down) p.pause()
            android.view.KeyEvent.KEYCODE_DPAD_LEFT, android.view.KeyEvent.KEYCODE_MEDIA_REWIND ->
                if (down) controlsActions.seekBy(-10_000)
            android.view.KeyEvent.KEYCODE_DPAD_RIGHT, android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD ->
                if (down) controlsActions.seekBy(10_000)
            else -> return false
        }
        return true
    }

    private fun isInside(view: View, parent: View): Boolean {
        var v: View? = view
        while (v != null) {
            if (v === parent) return true
            v = v.parent as? View
        }
        return false
    }

    /**
     * The stream failed with a network error: most often the debrid link
     * expired (a long pause, or hours in). Get a fresh one for the same
     * file, and fresh subtitle links, and continue from the same second.
     * Capped by [LinkRefresh]; after that, or for plain links, the Retry
     * dialog.
     */
    private fun refreshLinkOrAsk() {
        val id = identity
        val next = LinkRefresh.next(refreshState, android.os.SystemClock.elapsedRealtime())
        if (id == null || next == null || refreshing) {
            if (!refreshing) showNetworkError()
            return
        }
        refreshState = next
        refreshing = true
        val position = player?.currentPosition ?: resumePosition
        lifecycleScope.launch {
            try {
                val fresh = (repository.resolveLink(id.provider, id.sourceRef) as? DataResult.Success)?.data?.url
                if (fresh == null || id != identity) {
                    if (id == identity) showNetworkError()
                    return@launch
                }
                uri = Uri.parse(fresh)
                subtitles = subtitleResolver.refresh(subtitles)
                player?.apply {
                    setMediaItem(buildMediaItem(), position)
                    prepare()
                    play()
                }
            } finally {
                refreshing = false
            }
        }
    }

    /** Fetches the next episode's link and subtitles once, ahead of time. */
    private fun prefetch(episode: QueuedEpisode) {
        if (prefetchJob != null || prepared?.episode == episode) return
        prefetchJob = lifecycleScope.launch {
            prepared = prepare(episode)
            prefetchJob = null
        }
    }

    private suspend fun prepare(episode: QueuedEpisode): Prepared? {
        val item = episode.item
        val url = item.downloadUrl
            ?: (repository.resolveLink(item.provider, item.sourceRef) as? DataResult.Success)?.data?.url
            ?: return null
        return Prepared(episode, url, subtitleResolver.resolve(episode.subtitles))
    }

    /**
     * Switches to the next episode in this same player: marks the current one
     * watched, then plays the next from its saved position (if any).
     */
    private fun playNext() {
        if (switching) return
        val episode = queue.firstOrNull() ?: return
        switching = true
        hideNextCard()
        savePosition(ended = true)
        scrobble(scrobbler::stopped, ended = true)
        player?.pause()
        lifecycleScope.launch {
            try {
                switchTo(episode)
            } finally {
                switching = false
            }
        }
    }

    /** Back to the episode before this one, from where it was left (like [playNext], backwards). */
    private fun playPrevious() {
        if (switching) return
        val episode = previousQueue.firstOrNull() ?: return
        switching = true
        hideNextCard()
        savePosition()
        scrobble(scrobbler::stopped)
        player?.pause()
        lifecycleScope.launch {
            try {
                switchTo(episode, backwards = true)
            } finally {
                switching = false
            }
        }
    }

    private suspend fun switchTo(episode: QueuedEpisode, backwards: Boolean = false) {
        prefetchJob?.join()
        val ready = prepared?.takeIf { it.episode == episode } ?: prepare(episode)
        prepared = null
        if (ready == null) {
            Toast.makeText(this@PlayerActivity, R.string.player_next_failed, Toast.LENGTH_LONG).show()
            return
        }
        // This episode moves to the other side: Previous after going forward, Next after going back.
        if (backwards) {
            previousQueue.removeFirst()
            currentEpisode?.let { queue.addFirst(it) }
        } else {
            queue.removeFirst()
            currentEpisode?.let { previousQueue.addFirst(it) }
        }
        currentEpisode = episode
        episodePlayer?.queueChanged()
        val item = episode.item
        identity = Identity(item.id, item.provider, item.sourceRef, item.filename, episode.showKey, item.parentRef)
        uri = Uri.parse(ready.url)
        title = episode.title
        subtitles = ready.subtitles
        titleView.text = title
        controls.title = title
        startFile()
        offeredFallback = false
        nextCancelled = false
        refreshState = LinkRefresh.State()
        val saved = positions.get(item.id)
        val at = saved?.let { Resume.resumeAt(it.positionMs, it.durationMs, it.finished) } ?: 0L
        player?.apply {
            setMediaItem(buildMediaItem(), at)
            prepare()
            play()
        }
        updatePip()
    }

    /**
     * Current PiP settings: the video's shape, the play/pause and next
     * buttons, and (Android 12+) whether leaving should enter PiP by itself.
     */
    private fun pipParams(): PictureInPictureParams {
        val p = player
        val builder = PictureInPictureParams.Builder()
        p?.videoSize?.takeIf { it.width > 0 && it.height > 0 }?.let { v ->
            // Android only accepts ratios between about 1:2.39 and 2.39:1.
            val w = (v.width * v.pixelWidthHeightRatio).toInt().coerceAtLeast(1)
            val ratio = (w.toFloat() / v.height).coerceIn(0.42f, 2.38f)
            builder.setAspectRatio(Rational((ratio * 1000).toInt(), 1000))
        }
        val bounds = android.graphics.Rect()
        if (::playerView.isInitialized && playerView.getGlobalVisibleRect(bounds)) builder.setSourceRectHint(bounds)
        val playing = p?.isPlaying == true
        val actions = buildList {
            add(
                RemoteAction(
                    Icon.createWithResource(this@PlayerActivity, if (playing) R.drawable.ic_action_pause else R.drawable.ic_action_play),
                    getString(if (playing) R.string.action_pause else R.string.action_play),
                    getString(if (playing) R.string.action_pause else R.string.action_play),
                    pipIntent(ACTION_PIP_PLAY_PAUSE, 1)
                )
            )
            if (queue.isNotEmpty()) {
                add(
                    RemoteAction(
                        Icon.createWithResource(this@PlayerActivity, R.drawable.ic_action_next),
                        getString(R.string.player_next_episode),
                        getString(R.string.player_next_episode),
                        pipIntent(ACTION_PIP_NEXT, 2)
                    )
                )
            }
        }
        builder.setActions(actions)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+: the system enters PiP itself (smooth animation) on
            // Home or app switch, but only while something is playing.
            builder.setAutoEnterEnabled(pipAllowed && playing)
            builder.setSeamlessResizeEnabled(true)
        }
        return builder.build()
    }

    private fun pipIntent(action: String, code: Int): PendingIntent =
        PendingIntent.getBroadcast(
            this, code, Intent(action).setPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun updatePip() {
        if (!pipSupported) return
        runCatching { setPictureInPictureParams(pipParams()) }
    }

    /** Android 11: no auto-enter, so enter PiP when the user leaves while playing. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && pipSupported && pipAllowed && player?.isPlaying == true) {
            runCatching { enterPictureInPictureMode(pipParams()) }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        // Just the video in the small window: no controls, title or cards.
        playerView.useController = !isInPictureInPictureMode
        gestureOverlay.visibility = if (isInPictureInPictureMode) View.GONE else View.VISIBLE
        if (isInPictureInPictureMode) {
            controls.panel = null
            controls.visible = false
            if (inBackground) takeBackFromBackground() // the window plays it
        }
        applyZoom() // the small window always shows the whole picture
        if (isInPictureInPictureMode) {
            playerView.hideController()
            titleView.visibility = View.GONE
            startOverView.visibility = View.GONE
            nextCard.visibility = View.GONE
        } else if (lifecycle.currentState == Lifecycle.State.CREATED) {
            // The window was closed (not expanded): the activity is stopped
            // with nothing to come back to. The position was saved in onStop.
            finish()
        } else {
            hideSystemBars()
        }
    }

    override fun onDestroy() {
        if (inBackground) {
            takeBackFromBackground()
            savePosition()
        }
        releasePlayer() // no-op if already released in onStop
        gestureHandler.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(pipReceiver) }
        super.onDestroy()
    }

    private fun buildNextCard(): View {
        nextTitle = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            maxWidth = dp(280)
        }
        nextCountdown = TextView(this).apply {
            setTextColor(0xCCFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(2), 0, dp(10))
        }
        fun pill(textRes: Int, primary: Boolean, onClick: () -> Unit) = TextView(this).apply {
            text = getString(textRes)
            setTextColor(if (primary) Color.BLACK else Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            val h = dp(16); val v = dp(8)
            setPadding(h, v, h, v)
            background = focusable(if (primary) Color.WHITE else 0x33FFFFFF, dp(18))
            isFocusable = true // TV remote
            setOnClickListener { onClick() }
        }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            nextPlayNow = pill(R.string.player_play_now, primary = true) { playNext() }
            addView(nextPlayNow)
            addView(
                pill(R.string.action_cancel, primary = false) {
                    nextCancelled = true
                    hideNextCard()
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { marginStart = dp(8) }
            )
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(16)
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(0xE0181818.toInt())
            }
            addView(nextTitle)
            addView(nextCountdown)
            addView(buttons)
            visibility = View.GONE
        }
    }

    /** Tells Trakt (if signed in) about the current file; [ended] reports it as played to the end. */
    private fun scrobble(send: (String, Long, Long) -> Unit, ended: Boolean = false) {
        val p = player ?: return
        val duration = durationOrNull() ?: return
        val name = identity?.filename?.takeIf { it.isNotBlank() } ?: return
        send(name, if (ended) duration else p.currentPosition.coerceIn(0, duration), duration)
    }

    private fun savePosition(ended: Boolean = false) {
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
    }

    override fun onStart() {
        super.onStart()
        if (inBackground) takeBackFromBackground()
        initializePlayer()
        playerView.onResume()
        startTicking()
    }

    /**
     * Leaving while playing with "Keep playing in the background" on: hand
     * the player to the media service now, while still in the foreground
     * (Android only lets a foreground app start it).
     */
    override fun onPause() {
        super.onPause()
        if (controls.background && !inBackground && player?.isPlaying == true &&
            !isFinishing && !isChangingConfigurations && !isInPictureInPictureMode
        ) {
            // The service's session takes over (notification, lock screen).
            closeSession()
            BackgroundPlayback.player = episodePlayer ?: player
            startService(Intent(this, BackgroundPlaybackService::class.java))
            inBackground = true
        }
    }

    override fun onResume() {
        super.onResume()
        if (inBackground) takeBackFromBackground()
    }

    override fun onStop() {
        tickJob?.cancel()
        savePosition()
        playerView.onPause()
        if (inBackground && player != null && !isFinishing) {
            // Sound only while away: no point decoding pictures nobody sees.
            player?.let { it.trackSelectionParameters = it.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true).build() }
        } else {
            releasePlayer()
        }
        super.onStop()
    }

    /** Back in the player: the service lets go, pictures come back. */
    private fun takeBackFromBackground() {
        inBackground = false
        stopService(Intent(this, BackgroundPlaybackService::class.java))
        BackgroundPlayback.player = null
        openSession()
        player?.let { it.trackSelectionParameters = it.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false).build() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        player?.let {
            resumePosition = it.currentPosition
            resumePlayWhenReady = it.playWhenReady
        }
        outState.putLong(STATE_POSITION, resumePosition)
        outState.putBoolean(STATE_PLAY_WHEN_READY, resumePlayWhenReady)
        outState.putBoolean(STATE_OFFERED, offeredFallback)
    }

    private fun initializePlayer() {
        if (player != null) return
        // Device decoders first; FFmpeg (Jellyfin's prebuilt extension) only
        // for what the device can't do: E-AC3/AC3 on phones without a Dolby
        // licence, DTS, TrueHD. Decoder fallback tries another codec
        // instance if the first one fails to start.
        val renderers = SyncedRenderersFactory(this, tuning)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)
        val exo = ExoPlayer.Builder(this, renderers)
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
        exo.addAnalyticsListener(analytics)
        playerView.player = exo
        player = exo
        episodePlayer = EpisodePlayer(
            exo,
            hasNext = { queue.isNotEmpty() },
            hasPrevious = { previousQueue.isNotEmpty() },
            onNext = ::playNext,
            onPrevious = ::playPrevious
        )
        openSession()
        attachLoudness(exo.audioSessionId)
        applySubtitleStyle()
        startLoop() // an A-B loop set before leaving the app
    }

    /** The current file, with the torrent's own subtitle files (pick them with the CC button). */
    private fun buildMediaItem(): MediaItem =
        MediaItem.Builder()
            .setUri(uri)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
            .setSubtitleConfigurations(subtitles.map { sub ->
                MediaItem.SubtitleConfiguration.Builder(Uri.parse(sub.url))
                    .setMimeType(sub.mimeType)
                    .setLanguage(sub.language)
                    .setLabel(sub.label)
                    // Forced subs (signs, foreign dialogue) show without being picked.
                    .setSelectionFlags(if (sub.forced) C.SELECTION_FLAG_FORCED else 0)
                    .build()
            })
            .build()

    private fun releasePlayer() {
        val exo = player ?: return
        if (exo.playbackState != Player.STATE_ENDED) scrobble(scrobbler::stopped)
        resumePosition = exo.currentPosition
        resumePlayWhenReady = exo.playWhenReady
        savedTrackParams = exo.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false).build()
        loopMessage = null
        savedSpeed = exo.playbackParameters.speed
        loudness?.release()
        loudness = null
        nightMode?.release()
        nightMode = null
        audioSessionId = C.AUDIO_SESSION_ID_UNSET
        closeSession()
        episodePlayer = null
        exo.removeAnalyticsListener(analytics)
        exo.removeListener(listener)
        exo.release()
        playerView.player = null
        player = null
    }

    private val listener = object : Player.Listener {
        /** Portrait video (phone recordings, shorts): allow portrait instead. */
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updatePip()
            if (isPlaying) scrobble(scrobbler::playing)
        }

        // The user's pause, not buffering (which also stops isPlaying for a moment).
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!playWhenReady && player?.playbackState != Player.STATE_ENDED) scrobble(scrobbler::paused)
        }

        override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
            if (videoSize.width <= 0 || videoSize.height <= 0) return
            updatePip()
            val displayWidth = videoSize.width * videoSize.pixelWidthHeightRatio
            if (userRotated) return
            requestedOrientation = if (videoSize.height > displayWidth) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            } else {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
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
                if (autoplay && queue.isNotEmpty() && !nextCancelled) playNext()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            when (PlayerErrors.kindOf(error.errorCode)) {
                PlayerErrors.Kind.UNSUPPORTED -> offerExternal(getString(R.string.player_unsupported_body))
                // Most likely an expired link or a dropped connection.
                PlayerErrors.Kind.NETWORK -> refreshLinkOrAsk()
                PlayerErrors.Kind.OTHER -> offerExternal(getString(R.string.player_error_body, error.errorCodeName))
            }
        }

        /**
         * Video plays but none of its audio tracks can be decoded here (DTS,
         * TrueHD…): ExoPlayer would silently play without sound.
         */
        override fun onAudioSessionIdChanged(audioSessionId: Int) = attachLoudness(audioSessionId)

        override fun onTracksChanged(tracks: Tracks) {
            controls.tracksVersion++
            selectPendingSubtitle(tracks)
            if (!tracksApplied && !tracks.isEmpty) {
                tracksApplied = true
                val memory = remembered
                lifecycleScope.launch { applyTrackChoices(tracks, memory?.await()) }
            }
            val audio = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
            if (audio.isNotEmpty() && audio.none { it.isSupported }) {
                offerExternal(getString(R.string.player_no_audio_body), videoProblem = false)
            }
        }
    }

    /** "Can't play this here" → hand the same link to VLC & co. */
    /** [videoProblem]: the picture may be the trouble, so software decoding is worth a try first. */
    private fun offerExternal(message: String, videoProblem: Boolean = true) {
        if (offeredFallback || isFinishing) return
        offeredFallback = true
        player?.pause()
        AlertDialog.Builder(this)
            .setTitle(R.string.player_unsupported_title)
            .setMessage(message)
            .setPositiveButton(R.string.action_open_other_player) { _, _ -> openExternally() }
            .setNegativeButton(R.string.player_keep_watching) { _, _ -> player?.play() }
            .apply {
                if (videoProblem && !tuning.preferSoftwareVideo) {
                    setNeutralButton(R.string.player_try_software) { _, _ ->
                        offeredFallback = false // if this fails too, ask again
                        setSoftwareDecoding(true, save = true)
                        player?.play()
                    }
                }
            }
            .setOnCancelListener { player?.play() }
            .show()
    }

    /** Session while watching, so headset / watch / output-switcher controls work here too. */
    private fun openSession() {
        if (session != null || inBackground) return
        val p = episodePlayer ?: return
        session = runCatching {
            androidx.media3.session.MediaSession.Builder(this, p).setId(SESSION_ID).build()
        }.getOrNull()
    }

    private fun closeSession() {
        session?.release()
        session = null
    }

    /** Decoders and dropped frames for the stats overlay. */
    private val analytics = object : androidx.media3.exoplayer.analytics.AnalyticsListener {
        override fun onVideoDecoderInitialized(
            eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
            decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long
        ) { videoDecoder = decoderName }

        override fun onAudioDecoderInitialized(
            eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
            decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long
        ) { audioDecoder = decoderName }

        override fun onDroppedVideoFrames(
            eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long
        ) { this@PlayerActivity.droppedFrames += droppedFrames }

        override fun onBandwidthEstimate(
            eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
            totalLoadTimeMs: Int, totalBytesLoaded: Long, bitrateEstimate: Long
        ) { bandwidthBps = bitrateEstimate }
    }

    private fun openExternally() {
        // Subtitles saved in DebForge's cache (OpenSubtitles) aren't readable by other apps.
        if (!playExternally(PlayRequest(uri, title, subtitles = subtitles.filterNot { it.url.startsWith("file:") }))) {
            Toast.makeText(this, R.string.msg_no_player, Toast.LENGTH_LONG).show()
            player?.play()
            return
        }
        // Nothing to come back to: the other player has it now.
        finish()
    }

    private fun showNetworkError() {
        if (isFinishing) return
        AlertDialog.Builder(this)
            .setTitle(R.string.player_network_title)
            .setMessage(R.string.player_network_body)
            .setPositiveButton(R.string.action_retry) { _, _ ->
                if (identity != null) {
                    // A fresh link, with a fresh budget: the user asked for it.
                    refreshState = LinkRefresh.State()
                    refreshLinkOrAsk()
                } else {
                    player?.let {
                        resumePosition = it.currentPosition
                        it.prepare()
                        it.play()
                    }
                }
            }
            .setNegativeButton(R.string.action_close) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun showSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** The file being played, from the intent; null for a plain link. */
    private data class Identity(
        val itemId: String,
        val provider: ProviderId,
        val sourceRef: String,
        val filename: String,
        val showKey: String?,
        val parentRef: String? = null
    ) {
        companion object {
            fun from(intent: Intent): Identity? {
                val id = intent.getStringExtra(EXTRA_ITEM_ID) ?: return null
                val provider = ProviderId.fromName(intent.getStringExtra(EXTRA_PROVIDER)) ?: return null
                return Identity(
                    itemId = id,
                    provider = provider,
                    sourceRef = intent.getStringExtra(EXTRA_SOURCE_REF) ?: return null,
                    filename = intent.getStringExtra(EXTRA_FILENAME).orEmpty(),
                    showKey = intent.getStringExtra(EXTRA_SHOW_KEY),
                    parentRef = intent.getStringExtra(EXTRA_PARENT_REF)
                )
            }
        }
    }

    companion object {
        private const val ACTION_PIP_PLAY_PAUSE = "com.abhinavxt.debforge.player.PIP_PLAY_PAUSE"
        private const val ACTION_PIP_NEXT = "com.abhinavxt.debforge.player.PIP_NEXT"
        private const val SAVE_EVERY_TICKS = 5
        /** The background service's session uses the default id; the two never clash. */
        private const val SESSION_ID = "player"
        /** [doneSkips] entry for the learnt intro (chapter segments use their start). */
        private const val LEARNT_SKIP = -1L
        /** Credits ending this close to the end of the file count as "to the end". */
        private const val END_SLACK_MS = 3_000L
        /** Fetch the next episode's links this long before the end. */
        private const val PREFETCH_BEFORE_END_MS = 90_000L
        /** Show "Next: …" this long before the end (end credits). */
        private const val CARD_BEFORE_END_MS = 30_000L
        /** The card counts down this long, then the next episode starts. */
        private const val COUNTDOWN_MS = 10_000L
        private const val START_OVER_VISIBLE_MS = 8_000L
        /** While scrubbing, seek at most this often (streams can't keep up with every move). */
        private const val SCRUB_SEEK_EVERY_MS = 120L
        /** The shortest A-B loop. */
        private const val MIN_LOOP_MS = 1_000L
        private const val EXTRA_ITEM_ID = "itemId"
        private const val EXTRA_PROVIDER = "provider"
        private const val EXTRA_SOURCE_REF = "sourceRef"
        private const val EXTRA_FILENAME = "filename"
        private const val EXTRA_SHOW_KEY = "showKey"
        private const val EXTRA_PARENT_REF = "parentRef"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_SUB_URLS = "subUrls"
        private const val EXTRA_SUB_MIMES = "subMimes"
        private const val EXTRA_SUB_LANGS = "subLangs"
        private const val EXTRA_SUB_LABELS = "subLabels"
        private const val EXTRA_SUB_FORCED = "subForced"
        private const val EXTRA_SUB_PROVIDERS = "subProviders"
        private const val EXTRA_SUB_REFS = "subRefs"

        /** Parallel arrays: no Parcelable class needed for a handful of strings. */
        private fun subtitlesFrom(intent: Intent): List<SubtitleLink> {
            val urls = intent.getStringArrayExtra(EXTRA_SUB_URLS) ?: return emptyList()
            val mimes = intent.getStringArrayExtra(EXTRA_SUB_MIMES) ?: return emptyList()
            val langs = intent.getStringArrayExtra(EXTRA_SUB_LANGS)
            val labels = intent.getStringArrayExtra(EXTRA_SUB_LABELS)
            val forced = intent.getBooleanArrayExtra(EXTRA_SUB_FORCED)
            val providers = intent.getStringArrayExtra(EXTRA_SUB_PROVIDERS)
            val refs = intent.getStringArrayExtra(EXTRA_SUB_REFS)
            return urls.indices.mapNotNull { i ->
                SubtitleLink(
                    url = urls[i],
                    mimeType = mimes.getOrNull(i) ?: return@mapNotNull null,
                    language = langs?.getOrNull(i)?.ifEmpty { null },
                    label = labels?.getOrNull(i).orEmpty(),
                    forced = forced?.getOrNull(i) == true,
                    provider = ProviderId.fromName(providers?.getOrNull(i)),
                    sourceRef = refs?.getOrNull(i)?.ifEmpty { null }
                )
            }
        }
        private const val STATE_POSITION = "position"
        private const val STATE_PLAY_WHEN_READY = "playWhenReady"
        private const val STATE_OFFERED = "offeredFallback"

        fun intent(context: Context, request: PlayRequest): Intent =
            Intent(context, PlayerActivity::class.java)
                .setData(request.uri)
                .putExtra(EXTRA_TITLE, request.title)
                .apply {
                    request.item?.let { item ->
                        putExtra(EXTRA_ITEM_ID, item.id)
                        putExtra(EXTRA_PROVIDER, item.provider.name)
                        putExtra(EXTRA_SOURCE_REF, item.sourceRef)
                        putExtra(EXTRA_FILENAME, item.filename)
                        putExtra(EXTRA_SHOW_KEY, request.showKey)
                        putExtra(EXTRA_PARENT_REF, item.parentRef)
                    }
                    if (request.subtitles.isNotEmpty()) {
                        val subs = request.subtitles
                        putExtra(EXTRA_SUB_URLS, subs.map { it.url }.toTypedArray())
                        putExtra(EXTRA_SUB_MIMES, subs.map { it.mimeType }.toTypedArray())
                        putExtra(EXTRA_SUB_LANGS, subs.map { it.language.orEmpty() }.toTypedArray())
                        putExtra(EXTRA_SUB_LABELS, subs.map { it.label }.toTypedArray())
                        putExtra(EXTRA_SUB_FORCED, subs.map { it.forced }.toBooleanArray())
                        putExtra(EXTRA_SUB_PROVIDERS, subs.map { it.provider?.name.orEmpty() }.toTypedArray())
                        putExtra(EXTRA_SUB_REFS, subs.map { it.sourceRef.orEmpty() }.toTypedArray())
                    }
                }
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
