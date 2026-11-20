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
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.lifecycle.lifecycleScope
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.abhinavxt.debforge.R
import com.abhinavxt.debforge.domain.ProviderId
import com.abhinavxt.debforge.domain.SubtitleLink
import com.abhinavxt.debforge.player.controls.ControlsActions
import com.abhinavxt.debforge.player.controls.Fill
import com.abhinavxt.debforge.player.controls.PlayerControls
import com.abhinavxt.debforge.player.controls.fillLabel
import com.abhinavxt.debforge.player.gestures.GestureEngine
import com.abhinavxt.debforge.player.gestures.GestureMath
import com.abhinavxt.debforge.player.gestures.GestureOverlay
import com.abhinavxt.debforge.player.gestures.GestureUiState
import com.abhinavxt.debforge.player.session.VideoHost
import com.abhinavxt.debforge.player.session.VideoIdentity
import com.abhinavxt.debforge.player.session.VideoSession
import com.abhinavxt.debforge.player.session.VideoTarget
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.abhinavxt.debforge.ui.browse.PlayRequest
import com.abhinavxt.debforge.ui.browse.playExternally

/**
 * DebForge's own video player screen (Media3 PlayerView over [VideoSession], the engine that owns the
 * ExoPlayer). On phones and tablets the controls are DebForge's own (player/controls: title bar with
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
 *
 * This screen is the engine's host ([VideoHost]): the overlay Views, dialogs, picture-in-picture,
 * orientation, system bars, keys and touch, brightness, cast and screenshots. The player, the file and
 * everything about playing it live in [VideoSession].
 */
@OptIn(UnstableApi::class)
@AndroidEntryPoint
class PlayerActivity : ComponentActivity() {

    @Inject lateinit var session: VideoSession
    @Inject lateinit var settings: com.abhinavxt.debforge.data.prefs.SettingsStore
    @Inject lateinit var caster: com.abhinavxt.debforge.player.cast.DlnaCaster
    @Inject lateinit var downloads: com.abhinavxt.debforge.download.DownloadController
    @Inject @com.abhinavxt.debforge.di.ApiHttpClient lateinit var apiHttp: okhttp3.OkHttpClient
    @Inject lateinit var downloadDao: com.abhinavxt.debforge.data.local.DownloadDao

    /** The video this screen shows ([VideoSession.open]); another token there means it was replaced. */
    private var token = 0L
    private val controls get() = session.controls
    private val gestureUi get() = session.gestureUi
    private val player get() = session.player

    // --- picture-in-picture ---
    /** The device has PiP (most TVs don't) and Settings allow it. */
    private val pipSupported by lazy { packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) }
    private var pipAllowed = true

    /** Play/pause and "Next episode" buttons in the floating window. */
    private val pipReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!session.isCurrent(token)) return // a replaced video's screen
            when (intent.action) {
                ACTION_PIP_PLAY_PAUSE -> player?.let { if (it.isPlaying) it.pause() else it.play() }
                ACTION_PIP_NEXT -> session.playNext()
            }
        }
    }

    private lateinit var playerView: PlayerView
    private lateinit var titleView: TextView
    private lateinit var startOverView: TextView
    private lateinit var nextPlayNow: View

    // --- touch gestures ---
    /** Settings → "Touch gestures". */
    private var gesturesEnabled = true
    private var gestureEngine: GestureEngine? = null
    /** The current touch belongs to the gestures (not to a button). */
    private var gesturing = false
    /** Compose layer over the video: DebForge's controls (not on TV) and the gesture feedback. */
    private lateinit var gestureOverlay: androidx.compose.ui.platform.ComposeView
    /** The engine's controls actions plus this screen's own (back, rotate, fill, PiP…). */
    private lateinit var controlsActions: ControlsActions
    /** The user turned the screen with the rotate button: stop following the video's shape. */
    private var userRotated = false

    // --- stats for nerds ---
    private lateinit var statsView: TextView
    private lateinit var skipView: TextView

    private val gestureHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val gestureTimer = Runnable {
        gestureEngine?.timer(android.os.SystemClock.uptimeMillis())
        scheduleGestureTimer()
    }
    /** Pinch zoom of the picture (1 = fit). */
    private var zoom = 1f

    private var networkDialog: AlertDialog? = null

    /** Android TV: move focus to overlays when they appear (no touch screen). */
    private val isTv get() = session.isTv

    /** Back while "Next: …" is showing cancels it instead of leaving the player. */
    private val cancelNextOnBack = object : androidx.activity.OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = session.cancelNext()
    }
    private lateinit var nextCard: View
    private lateinit var nextTitle: TextView
    private lateinit var nextCountdown: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent.data ?: run { finish(); return }
        val savedToken = savedInstanceState?.getLong(STATE_TOKEN, -1L) ?: -1L
        if (savedInstanceState != null && session.isCurrent(savedToken)) {
            // Recreated (the video kept going in the engine): show it again.
            session.audioPlayer.parkForVideo() // the audio queue waits, paused, in the mini player
            token = savedToken
        } else {
            // Handed over by the audio player: its position and speed, not the saved ones.
            val start = if (savedInstanceState == null) VideoStartExtras.from(intent) else null
            token = session.open(
                VideoTarget(uri, intent.getStringExtra(EXTRA_TITLE).orEmpty(), identityFrom(intent), subtitlesFrom(intent), ytStreamFrom(intent)),
                start = start,
                // Fresh start (not a recreate after the process died): pick up where the user left off.
                restoreSaved = savedInstanceState == null && restoresSavedPosition(start),
                resumeAtMs = savedInstanceState?.getLong(STATE_POSITION),
                playWhenReady = savedInstanceState?.getBoolean(STATE_PLAY_WHEN_READY, true) ?: true,
                offeredFallback = savedInstanceState?.getBoolean(STATE_OFFERED, false) ?: false,
            )
        }
        controlsActions = ScreenActions(session.actions)
        lifecycleScope.launch {
            pipAllowed = settings.pipFlow.first()
            gesturesEnabled = settings.playerGesturesFlow.first()
            controls.pipAvailable = pipSupported && pipAllowed
            updatePip()
        }
        ContextCompat.registerReceiver(
            this, pipReceiver,
            IntentFilter().apply { addAction(ACTION_PIP_PLAY_PAUSE); addAction(ACTION_PIP_NEXT) },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

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
            resizeMode = resizeModeFor(controls.fill)
        }
        titleView = TextView(this).apply {
            text = session.title
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
        val shownControls = controls
        val shownGestureUi = gestureUi
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
                        if (!isTv) PlayerControls(shownControls, controlsActions)
                        GestureOverlay(shownGestureUi)
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
        session.attach(playerView, host)
        hideSystemBars()
        onBackPressedDispatcher.addCallback(this, cancelNextOnBack)
    }

    /** What the engine asks of this screen. */
    private val host = object : VideoHost {
        override val inPictureInPicture get() = isInPictureInPictureMode
        override val isClosing get() = isFinishing

        override fun close() = finish()

        override fun titleChanged(title: String) {
            titleView.text = title
        }

        override fun showStartOver(show: Boolean) {
            if (show) {
                startOverView.visibility = View.VISIBLE
                if (isTv) startOverView.requestFocus()
            } else {
                startOverView.visibility = View.GONE
            }
        }

        override fun showSkip(text: String, onSkip: () -> Unit) {
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

        override fun hideSkip() {
            if (skipView.visibility != View.GONE) skipView.visibility = View.GONE
        }

        override fun showNextCard(text: String) {
            nextTitle.text = text
            if (!isInPictureInPictureMode) nextCard.visibility = View.VISIBLE
            cancelNextOnBack.isEnabled = true
            if (isTv) nextPlayNow.requestFocus()
        }

        override fun setNextCountdown(text: String?) {
            if (text == null) {
                nextCountdown.visibility = View.GONE
            } else {
                nextCountdown.visibility = View.VISIBLE
                nextCountdown.text = text
            }
        }

        override fun hideNextCard() {
            cancelNextOnBack.isEnabled = false
            if (::nextCard.isInitialized) nextCard.visibility = View.GONE
        }

        override fun showStats(on: Boolean) {
            statsView.visibility = if (on) View.VISIBLE else View.GONE
        }

        override fun statsText(text: String) {
            statsView.text = text
        }

        override fun updatePip() = this@PlayerActivity.updatePip()

        override fun videoShape(portrait: Boolean) {
            if (userRotated) return
            requestedOrientation = if (portrait) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            } else {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
        }

        override fun showNetworkError() = this@PlayerActivity.showNetworkError()

        override fun dismissNetworkError() {
            networkDialog?.dismiss()
        }

        override fun showStreamFailed(youtube: com.abhinavxt.debforge.ui.browse.YtStream) {
            AlertDialog.Builder(this@PlayerActivity)
                .setMessage(R.string.yt_stream_failed)
                .setPositiveButton(R.string.yt_stream_download) { _, _ -> downloadFailedStream(youtube) }
                .setNegativeButton(R.string.action_close) { _, _ -> finish() }
                .setOnCancelListener { finish() }
                .show()
        }

        override fun showYouTubeProblem(message: String) {
            AlertDialog.Builder(this@PlayerActivity)
                .setMessage(message)
                .setPositiveButton(R.string.action_close) { _, _ -> finish() }
                .setOnCancelListener { finish() }
                .show()
        }

        override fun offerExternal(message: String, offerSoftware: Boolean) {
            AlertDialog.Builder(this@PlayerActivity)
                .setTitle(R.string.player_unsupported_title)
                .setMessage(message)
                .setPositiveButton(R.string.action_open_other_player) { _, _ -> openExternally() }
                .setNegativeButton(R.string.player_keep_watching) { _, _ -> player?.play() }
                .apply {
                    if (offerSoftware) {
                        setNeutralButton(R.string.player_try_software) { _, _ -> session.trySoftwareDecoding() }
                    }
                }
                .setOnCancelListener { player?.play() }
                .show()
        }
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
                android.view.KeyEvent.KEYCODE_MEDIA_NEXT -> if (session.hasNext) { session.playNext(); return true }
                android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS -> if (session.hasPrevious) { session.playPrevious(); return true }
                android.view.KeyEvent.KEYCODE_INFO -> { session.setStats(!controls.stats); return true }
                android.view.KeyEvent.KEYCODE_MENU -> if (isTv && showTvOptions()) return true
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
        if (hit(startOverView, x, y) || hit(skipView, x, y) || hit(nextCard, x, y)) return true
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

    private fun applyZoom() {
        val frame = playerView.findViewById<View>(androidx.media3.ui.R.id.exo_content_frame) ?: return
        val z = if (isInPictureInPictureMode) 1f else zoom
        frame.scaleX = z
        frame.scaleY = z
    }

    private fun haptic(constant: Int) {
        window.decorView.performHapticFeedback(constant)
    }

    /** TV: the remote's MENU key offers "Play as audio" (Media3's controller has no slot for it). False when there is nothing to offer. */
    private fun showTvOptions(): Boolean {
        if (!session.canPlayAsAudio()) return false
        AlertDialog.Builder(this)
            .setItems(arrayOf(getString(R.string.player_play_as_audio))) { _, _ -> session.handOffToAudio() }
            .show()
        return true
    }

    private fun resizeModeFor(fill: Fill) = when (fill) {
        Fill.FIT -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
        Fill.CROP -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        Fill.STRETCH -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
    }

    /** The engine's controls actions, plus what needs this screen. */
    private inner class ScreenActions(engine: ControlsActions) : ControlsActions by engine {
        override fun back() = onBackPressedDispatcher.onBackPressed()

        override fun openExternal() = openExternally()
        override fun cast() = castToTv()

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
            playerView.resizeMode = resizeModeFor(fill)
            zoom = 1f // pinch zoom starts over from the new fill
            applyZoom()
            session.showMessage(getString(fillLabel(fill)))
        }

        override fun enterPip() {
            runCatching { enterPictureInPictureMode(pipParams()) }
        }

        override fun screenshot() {
            controls.panel = null
            takeScreenshot()
        }

        override fun onBarsVisible(visible: Boolean) {
            if (isInPictureInPictureMode) return
            if (visible) showSystemBars() else hideSystemBars()
        }
    }

    /**
     * The current frame (without subtitles or controls) as a JPEG in
     * Pictures/DebForge. No permission needed: MediaStore owns the folder.
     */
    private fun takeScreenshot() {
        val surface = playerView.videoSurfaceView as? android.view.SurfaceView
        val v = player?.videoSize
        if (surface == null || v == null || v.width <= 0 || v.height <= 0) {
            session.showMessage(getString(R.string.player_screenshot_failed))
            return
        }
        val w = (v.width * v.pixelWidthHeightRatio).toInt().coerceAtLeast(1)
        val scale = minOf(1f, 3840f / maxOf(w, v.height))
        val bitmap = android.graphics.Bitmap.createBitmap(
            (w * scale).toInt().coerceAtLeast(1), (v.height * scale).toInt().coerceAtLeast(1), android.graphics.Bitmap.Config.ARGB_8888
        )
        val title = session.title
        android.view.PixelCopy.request(surface, bitmap, { result ->
            if (result != android.view.PixelCopy.SUCCESS) {
                session.showMessage(getString(R.string.player_screenshot_failed))
                return@request
            }
            lifecycleScope.launch {
                val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { saveImage(bitmap, title) }
                bitmap.recycle()
                session.showMessage(getString(if (ok) R.string.player_screenshot_saved else R.string.player_screenshot_failed))
            }
        }, gestureHandler)
    }

    private fun saveImage(bitmap: android.graphics.Bitmap, title: String): Boolean = runCatching {
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
            p.seekTo(GestureMath.seekTarget(anchor, forward, seconds, session.durationOrNull()))
            session.keepShowing("seek")
            gestureUi.seekTaps = GestureUiState.SeekTaps(forward, seconds)
        }

        override fun onSeekTapsEnd() {
            seekAnchor = null
            session.hideLater("seek", 300) { gestureUi.seekTaps = null }
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
                volume = if (system >= max && session.boostLevel > 1f) session.boostLevel else system.toFloat() / max
            }
        }

        override fun onVertical(side: GestureEngine.Side, delta: Float) {
            if (!gesturesEnabled) return
            session.keepShowing("level")
            if (side == GestureEngine.Side.LEFT) {
                brightness = GestureMath.level(brightness, delta, min = 0.01f)
                window.attributes = window.attributes.apply { screenBrightness = brightness }
                gestureUi.level = GestureUiState.Level(GestureUiState.LevelKind.BRIGHTNESS, brightness)
            } else {
                // Past 100 % (with the loudness enhancer available): boost.
                val top = if (session.canBoost) GestureMath.MAX_VOLUME_LEVEL else 1f
                volume = GestureMath.level(volume, delta, max = top)
                val max = audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
                session.setBoost(volume.coerceAtLeast(1f))
                val index = kotlin.math.round(volume.coerceAtMost(1f) * max).toInt()
                if (index != audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)) {
                    // Can be refused (Do Not Disturb): the bar still follows the finger.
                    runCatching { audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, index, 0) }
                }
                gestureUi.level = GestureUiState.Level(GestureUiState.LevelKind.VOLUME, volume, max = top)
            }
        }

        override fun onVerticalEnd(side: GestureEngine.Side) {
            session.hideLater("level", 700) { gestureUi.level = null }
        }

        private fun systemBrightness(): Float =
            runCatching {
                android.provider.Settings.System.getInt(contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS) / 255f
            }.getOrDefault(0.5f)

        private var scrubbing = false

        override fun onScrubStart() {
            if (!gesturesEnabled) return
            scrubbing = true
            session.actions.beginScrub()
        }

        override fun onScrub(fraction: Float) {
            if (scrubbing) session.actions.scrubToTarget(GestureMath.scrubTarget(session.actions.scrubAnchor, fraction, session.durationOrNull()))
        }

        override fun onScrubEnd() {
            if (scrubbing) session.actions.endScrub(cancel = false)
            scrubbing = false
        }

        override fun onScrubCancel() {
            if (scrubbing) session.actions.endScrub(cancel = true)
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
            session.keepShowing("zoom")
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
            session.hideLater("zoom", 800) { gestureUi.zoomPercent = null }
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

    /** Queues the stream's video like the YouTube preview does, tells the user what happened, then closes. */
    private fun downloadFailedStream(yt: com.abhinavxt.debforge.ui.browse.YtStream) {
        val ref = com.abhinavxt.debforge.domain.YouTubeLinks.parseSourceRef(yt.ref)
        val title = session.title
        val uri = session.uri
        val subtitles = session.subtitles
        lifecycleScope.launch {
            // Same rule as the YouTube tab: no writable download folder yet means ask, never queue to the filesystem root.
            if (ref == null || !com.abhinavxt.debforge.download.StorageAccess.canWrite(this@PlayerActivity, settings.downloadDirFlow.first())) {
                android.widget.Toast.makeText(this@PlayerActivity, R.string.add_needs_storage, android.widget.Toast.LENGTH_LONG).show()
                finish()
                return@launch
            }
            val item = com.abhinavxt.debforge.ui.youtube.youTubeDownloadItem(
                videoId = yt.videoId, title = title, uploader = yt.uploader.ifBlank { null }, videoItag = ref.videoItag,
                audioItag = ref.audioItag, extension = yt.extension, height = yt.height, estimatedBytes = 0, url = uri.toString(),
            )
            val sub = subtitles.firstOrNull { !it.url.startsWith("file:") && it.language != null }?.let {
                com.abhinavxt.debforge.ui.youtube.youTubeSubtitleItem(item, com.abhinavxt.debforge.domain.YouTubeSubtitle(it.language!!, it.label, it.url, false))
            }
            val message = try {
                if (downloadDao.getById(item.id) != null) R.string.yt_already_queued
                else { downloads.enqueue(item, foreground = true); R.string.yt_queued }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                R.string.yt_err_queue
            }
            var toast = message
            if (message == R.string.yt_queued) {
                com.abhinavxt.debforge.ui.youtube.saveYouTubeThumbnail(
                    this@PlayerActivity, apiHttp, com.abhinavxt.debforge.ui.youtube.youTubeThumbnailUrl(yt.videoId), item.id,
                )
                if (sub != null) {
                    try {
                        if (downloadDao.getById(sub.id) == null) downloads.enqueue(sub)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        toast = R.string.yt_queued_no_subtitle
                    }
                }
            }
            android.widget.Toast.makeText(this@PlayerActivity, toast, android.widget.Toast.LENGTH_LONG).show()
            finish()
        }
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
            if (session.hasNext) {
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
            session.hostResumed(token) // in the background service? the window plays it now
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
        if (::playerView.isInitialized) {
            session.hostDestroyed(token, isFinishing)
            session.detach(playerView)
        }
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
            nextPlayNow = pill(R.string.player_play_now, primary = true) { session.playNext() }
            addView(nextPlayNow)
            addView(
                pill(R.string.action_cancel, primary = false) { session.cancelNext() },
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

    override fun onStart() {
        super.onStart()
        // Another video replaced this one in the engine meanwhile: nothing to show here any more.
        if (!session.hostStarted(token)) {
            finish()
            return
        }
        playerView.onResume()
    }

    /**
     * Leaving while playing with "Keep playing in the background" on: the
     * engine hands the player to the media service now, while still in the
     * foreground (Android only lets a foreground app start it).
     */
    override fun onPause() {
        super.onPause()
        session.hostPaused(token, mayLeave = !isFinishing && !isChangingConfigurations && !isInPictureInPictureMode)
    }

    override fun onResume() {
        super.onResume()
        session.hostResumed(token)
    }

    override fun onStop() {
        session.hostStopped(token, isFinishing)
        playerView.onPause()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val (position, playWhenReady) = session.positionForState()
        outState.putLong(STATE_POSITION, position)
        outState.putBoolean(STATE_PLAY_WHEN_READY, playWhenReady)
        outState.putBoolean(STATE_OFFERED, session.offeredFallback)
        outState.putLong(STATE_TOKEN, token)
    }

    /** Play on TV: finds DLNA renderers on the network and hands the picked one the stream. */
    private fun castToTv() {
        val p = player ?: return
        p.pause()
        var search: kotlinx.coroutines.Job? = null
        val searching = AlertDialog.Builder(this)
            .setTitle(R.string.cast_title)
            .setMessage(R.string.cast_searching)
            .setNegativeButton(R.string.action_cancel) { _, _ -> search?.cancel() }
            .setOnCancelListener { search?.cancel() }
            .show()
        search = lifecycleScope.launch {
            val found = caster.discover()
            searching.dismiss()
            if (isFinishing) return@launch
            if (found.isEmpty()) {
                AlertDialog.Builder(this@PlayerActivity)
                    .setTitle(R.string.cast_title)
                    .setMessage(R.string.cast_none)
                    .setPositiveButton(R.string.action_retry) { _, _ -> castToTv() }
                    .setNegativeButton(R.string.action_close, null)
                    .show()
                return@launch
            }
            AlertDialog.Builder(this@PlayerActivity)
                .setTitle(R.string.cast_title)
                .setItems(found.map { it.name }.toTypedArray()) { _, i -> startCast(found[i]) }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
        }
    }

    private fun startCast(to: com.abhinavxt.debforge.player.cast.Renderer) {
        val at = player?.currentPosition ?: 0L
        val uri = session.uri
        val title = session.title
        lifecycleScope.launch {
            if (runCatching { caster.play(to, uri.toString(), title, at) }.isFailure) {
                Toast.makeText(this@PlayerActivity, getString(R.string.cast_failed, to.name), Toast.LENGTH_LONG).show()
                return@launch
            }
            if (!isFinishing) showCastRemote(to)
        }
    }

    /** While the TV plays: pause / play it, take the video back here, or leave it there and close. */
    private fun showCastRemote(on: com.abhinavxt.debforge.player.cast.Renderer) {
        var paused = false
        controls.casting = true
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.cast_playing_on, on.name))
            .setMessage(session.title)
            .setCancelable(false)
            .setPositiveButton(R.string.cast_pause, null)
            .setNegativeButton(R.string.cast_stop) { _, _ ->
                lifecycleScope.launch {
                    // Carry on here from where the TV got to.
                    val at = caster.positionMs(on)
                    runCatching { caster.stop(on) }
                    controls.casting = false
                    player?.let { p -> at?.let(p::seekTo); p.play() }
                }
            }
            .setNeutralButton(R.string.cast_leave) { _, _ -> finish() }
            .create()
        dialog.setOnShowListener {
            // Set here so the button doesn't close the dialog.
            val toggle = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            toggle.setOnClickListener {
                lifecycleScope.launch {
                    val ok = runCatching { if (paused) caster.resume(on) else caster.pause(on) }.isSuccess
                    if (!ok) return@launch
                    paused = !paused
                    toggle.setText(if (paused) R.string.cast_play else R.string.cast_pause)
                }
            }
        }
        dialog.show()
    }

    private fun openExternally() {
        if (session.youtube != null) return // the audio is a separate URL: another player could not play it
        // Subtitles saved in DebForge's cache (OpenSubtitles) aren't readable by other apps.
        if (!playExternally(PlayRequest(session.uri, session.title, subtitles = session.subtitles.filterNot { it.url.startsWith("file:") }))) {
            Toast.makeText(this, R.string.msg_no_player, Toast.LENGTH_LONG).show()
            player?.play()
            return
        }
        // Nothing to come back to: the other player has it now.
        finish()
    }

    private fun showNetworkError() {
        networkDialog?.dismiss()
        networkDialog = AlertDialog.Builder(this)
            .setTitle(R.string.player_network_title)
            .setMessage(R.string.player_network_body)
            .setPositiveButton(R.string.action_retry) { _, _ -> session.retryAfterNetworkError() }
            .setNegativeButton(R.string.action_close) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .setOnDismissListener { dismissed ->
                // A newer dialog's watch must survive this one's late dismiss callback.
                if (networkDialog === dismissed) session.networkDialogGone()
            }
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

    companion object {
        private const val ACTION_PIP_PLAY_PAUSE = "com.abhinavxt.debforge.player.PIP_PLAY_PAUSE"
        private const val ACTION_PIP_NEXT = "com.abhinavxt.debforge.player.PIP_NEXT"
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

        private const val EXTRA_YT_REF = "ytRef"
        private const val EXTRA_YT_ID = "ytId"
        private const val EXTRA_YT_AUDIO = "ytAudio"
        private const val EXTRA_YT_HEIGHT = "ytHeight"
        private const val EXTRA_YT_EXT = "ytExt"
        private const val EXTRA_YT_UPLOADER = "ytUploader"

        /** The file being played, from the intent; null for a plain link. */
        private fun identityFrom(intent: Intent): VideoIdentity? {
            val id = intent.getStringExtra(EXTRA_ITEM_ID) ?: return null
            val provider = ProviderId.fromName(intent.getStringExtra(EXTRA_PROVIDER)) ?: return null
            return VideoIdentity(
                itemId = id,
                provider = provider,
                sourceRef = intent.getStringExtra(EXTRA_SOURCE_REF) ?: return null,
                filename = intent.getStringExtra(EXTRA_FILENAME).orEmpty(),
                showKey = intent.getStringExtra(EXTRA_SHOW_KEY),
                parentRef = intent.getStringExtra(EXTRA_PARENT_REF)
            )
        }

        private fun ytStreamFrom(intent: Intent): com.abhinavxt.debforge.ui.browse.YtStream? {
            val ref = intent.getStringExtra(EXTRA_YT_REF) ?: return null
            return com.abhinavxt.debforge.ui.browse.YtStream(
                ref = ref,
                videoId = intent.getStringExtra(EXTRA_YT_ID) ?: return null,
                audioUrl = intent.getStringExtra(EXTRA_YT_AUDIO) ?: return null,
                height = intent.getIntExtra(EXTRA_YT_HEIGHT, 0),
                extension = intent.getStringExtra(EXTRA_YT_EXT) ?: "mp4",
                uploader = intent.getStringExtra(EXTRA_YT_UPLOADER).orEmpty()
            )
        }

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
        /** The engine's video this screen showed, so a recreated screen shows the same one. */
        private const val STATE_TOKEN = "videoToken"

        /** The intent that opens [request]; [start] (from the audio player) sets where and how fast it begins, instead of the saved position. */
        fun intent(context: Context, request: PlayRequest, start: VideoStart? = null): Intent =
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
                    request.youtube?.let {
                        putExtra(EXTRA_YT_REF, it.ref)
                        putExtra(EXTRA_YT_ID, it.videoId)
                        putExtra(EXTRA_YT_AUDIO, it.audioUrl)
                        putExtra(EXTRA_YT_HEIGHT, it.height)
                        putExtra(EXTRA_YT_EXT, it.extension)
                        putExtra(EXTRA_YT_UPLOADER, it.uploader)
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
                .apply { start?.let { VideoStartExtras.put(this, it) } }
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
