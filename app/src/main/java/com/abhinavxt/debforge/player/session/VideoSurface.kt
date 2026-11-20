package com.abhinavxt.debforge.player.session

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.abhinavxt.debforge.R
import com.abhinavxt.debforge.data.prefs.SettingsStore
import com.abhinavxt.debforge.player.controls.ControlsActions
import com.abhinavxt.debforge.player.controls.Fill
import com.abhinavxt.debforge.player.controls.PlayerControls
import com.abhinavxt.debforge.player.gestures.GestureEngine
import com.abhinavxt.debforge.player.gestures.GestureMath
import com.abhinavxt.debforge.player.gestures.GestureOverlay
import com.abhinavxt.debforge.player.gestures.GestureUiState
import com.abhinavxt.debforge.ui.theme.AppTheme
import com.abhinavxt.debforge.ui.theme.DebforgeTheme

/**
 * The video and everything over it: Media3's PlayerView (the engine's [VideoSession] draws into it), DebForge's
 * controls and gesture feedback, the "Start over", skip and next-episode cards, stats, and the touch handling
 * that turns the finger into gestures. The screen showing it (PlayerActivity, later the in-app watch page)
 * keeps the window, dialogs and orientation, and passes in what the view needs: [window] for brightness and
 * [actions] for the controls' buttons.
 *
 * [mode] decides which gestures and overlays are active ([SurfaceFeatures.of]).
 */
@OptIn(UnstableApi::class)
@android.annotation.SuppressLint("ViewConstructor") // built in code by its host, never inflated
class VideoSurface(
    context: Context,
    private val session: VideoSession,
    private val settings: SettingsStore,
    private val window: () -> Window?,
    private val isTv: Boolean,
    private val actions: ControlsActions,
    mode: SurfaceMode = SurfaceMode.FULL
) : FrameLayout(context) {

    val playerView: PlayerView
    private val titleView: TextView
    private val startOverView: TextView
    private val statsView: TextView
    private val skipView: TextView
    private val nextCard: View
    private lateinit var nextTitle: TextView
    private lateinit var nextCountdown: TextView
    private lateinit var nextPlayNow: View
    private val overlay: ComposeView

    private val controls get() = session.controls
    private val gestureUi get() = session.gestureUi
    private val player get() = session.player

    private var features = SurfaceFeatures.of(mode)
    var mode: SurfaceMode = mode
        set(value) {
            if (field == value) return
            field = value
            features = SurfaceFeatures.of(value)
            applyMode()
        }

    /** Settings → "Touch gestures" (the gestures themselves; taps on the video still toggle the controls). */
    var gesturesEnabled = true

    /** In the floating picture-in-picture window: just the video. Set by the host through [setPictureInPicture]. */
    var inPictureInPicture = false
        private set

    /** Android TV: Media3's controller shows or hides (the host shows or hides the system bars with it). */
    var onControllerVisibility: ((visible: Boolean) -> Unit)? = null

    private var gestureEngine: GestureEngine? = null
    /** The current touch belongs to the gestures (not to a button). */
    private var gesturing = false
    private val gestureHandler = Handler(Looper.getMainLooper())
    private val gestureTimer = Runnable {
        gestureEngine?.timer(SystemClock.uptimeMillis())
        scheduleGestureTimer()
    }
    /** Pinch zoom of the picture (1 = fit). */
    private var zoom = 1f

    init {
        playerView = PlayerView(context).apply {
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
        titleView = TextView(context).apply {
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
        if (!isTv) titleView.visibility = GONE // the controls have their own title bar
        // TV: the title shows and hides together with Media3's controls.
        playerView.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
            titleView.visibility = visibility
            onControllerVisibility?.invoke(visibility == VISIBLE)
        })

        // "Start over" after resuming mid-way; hides itself after a few seconds.
        startOverView = TextView(context).apply {
            text = context.getString(R.string.player_start_over)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            val h = dp(16); val v = dp(10)
            setPadding(h, v, h, v)
            background = focusable(0xCC202020.toInt(), dp(20))
            visibility = GONE
            isFocusable = true // reachable with the TV remote
            setOnClickListener {
                player?.seekTo(0)
                visibility = GONE
            }
        }

        nextCard = buildNextCard()

        // Stats for nerds (More, or the remote's Info key on TV).
        statsView = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = android.graphics.Typeface.MONOSPACE
            val pad = dp(10)
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(0xB3000000.toInt())
            visibility = GONE
        }

        // "Skip intro" / "Skip recap" / "Skip credits": focusable for the TV remote.
        skipView = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            val h = dp(20); val v = dp(12)
            setPadding(h, v, h, v)
            background = focusable(0xE6202020.toInt(), dp(24))
            visibility = GONE
            isFocusable = true
        }

        // What the gestures show (seek taps, levels, speed…). Never takes touches.
        val shownControls = controls
        val shownGestureUi = gestureUi
        overlay = ComposeView(context).apply {
            setContent {
                val dynamic by settings.dynamicColorFlow.collectAsState(initial = false)
                val themeName by settings.appThemeFlow.collectAsState(initial = null)
                DebforgeTheme(
                    darkTheme = true,
                    dynamicColor = dynamic,
                    appTheme = AppTheme.fromName(themeName)
                ) {
                    androidx.compose.foundation.layout.Box {
                        if (!isTv) PlayerControls(shownControls, actions)
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

        addView(playerView, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(overlay, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(titleView, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        addView(
            startOverView,
            LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START)
                .apply { setMargins(dp(24), 0, 0, dp(if (isTv) 96 else 150)) }
        )
        addView(
            statsView,
            LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START)
                .apply { setMargins(dp(16), dp(72), dp(16), 0) }
        )
        addView(
            skipView,
            LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END)
                .apply { setMargins(0, 0, dp(24), dp(if (isTv) 96 else 150)) }
        )
        addView(
            nextCard,
            LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END)
                .apply { setMargins(0, 0, dp(24), dp(if (isTv) 96 else 150)) }
        )
        applyMode()
    }

    // --- what the engine's host forwards ---------------------------------------------

    fun titleChanged(title: String) {
        titleView.text = title
    }

    fun showStartOver(show: Boolean) {
        if (show) {
            startOverView.visibility = VISIBLE
            if (isTv) startOverView.requestFocus()
        } else {
            startOverView.visibility = GONE
        }
    }

    fun showSkip(text: String, onSkip: () -> Unit) {
        skipView.setOnClickListener {
            hideSkip()
            onSkip()
        }
        if (skipView.visibility == VISIBLE && skipView.text == text) return
        skipView.text = text
        skipView.visibility = VISIBLE
        // TV: reachable with OK at once, unless the user is busy in the controller.
        if (isTv && !playerView.isControllerFullyVisible) skipView.requestFocus()
    }

    fun hideSkip() {
        if (skipView.visibility != GONE) skipView.visibility = GONE
    }

    fun showNextCard(text: String) {
        nextTitle.text = text
        if (!inPictureInPicture && features.cards) nextCard.visibility = VISIBLE
        if (isTv) nextPlayNow.requestFocus()
    }

    fun setNextCountdown(text: String?) {
        if (text == null) {
            nextCountdown.visibility = GONE
        } else {
            nextCountdown.visibility = VISIBLE
            nextCountdown.text = text
        }
    }

    fun hideNextCard() {
        nextCard.visibility = GONE
    }

    fun showStats(on: Boolean) {
        statsView.visibility = if (on) VISIBLE else GONE
    }

    fun statsText(text: String) {
        statsView.text = text
    }

    /** Picture-in-picture entered or left: in the small window only the video shows. */
    fun setPictureInPicture(on: Boolean) {
        inPictureInPicture = on
        playerView.useController = !on
        overlay.visibility = if (on) GONE else if (features.overlays) VISIBLE else GONE
        applyZoom() // the small window always shows the whole picture
        if (on) {
            playerView.hideController()
            titleView.visibility = GONE
            startOverView.visibility = GONE
            nextCard.visibility = GONE
        }
    }

    /** The picture's fit / crop / stretch; pinch zoom starts over from it. */
    fun setFill(fill: Fill) {
        controls.fill = fill
        playerView.resizeMode = resizeModeFor(fill)
        zoom = 1f
        applyZoom()
    }

    /** Is [focus] one of the surface's own buttons (the TV remote works them instead of Media3's controller)? */
    fun ownsFocus(focus: View?): Boolean =
        focus != null && (focus === startOverView || focus === skipView || isInside(focus, nextCard))

    /** Stops the gesture timer; the host is going away. */
    fun release() {
        gestureHandler.removeCallbacksAndMessages(null)
    }

    override fun onDetachedFromWindow() {
        gestureHandler.removeCallbacksAndMessages(null)
        super.onDetachedFromWindow()
    }

    // --- mode ---------------------------------------------------------------------

    private fun applyMode() {
        if (!features.overlays) {
            playerView.useController = false
            overlay.visibility = GONE
        } else {
            playerView.useController = isTv && !inPictureInPicture
            overlay.visibility = if (inPictureInPicture) GONE else VISIBLE
        }
        if (!features.cards) {
            startOverView.visibility = GONE
            skipView.visibility = GONE
            nextCard.visibility = GONE
            statsView.visibility = GONE
        }
        if (!features.pinchZoom) {
            zoom = 1f
            applyZoom()
        }
    }

    // --- touch gestures -------------------------------------------------------------

    /**
     * Touches on the video go to the gestures; touches on a button, the seek bar or one of DebForge's cards go
     * to them as usual. Decided once per touch, when the finger goes down.
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val engine = gestureEngine
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            // Even with gestures off, taps on the video still show / hide the controls.
            gesturing = engine != null && features.overlays && !inPictureInPicture && !touchesControl(ev)
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
        gestureHandler.postDelayed(gestureTimer, (due - SystemClock.uptimeMillis()).coerceAtLeast(0))
    }

    /** Is ([ev].x, [ev].y) on something tappable: a controller button, the seek bar, a card? */
    private fun touchesControl(ev: MotionEvent): Boolean {
        val x = ev.x.toInt()
        val y = ev.y.toInt()
        if (hit(startOverView, x, y) || hit(skipView, x, y) || hit(nextCard, x, y)) return true
        if (!isTv) return controls.isOnControl(ev.x, ev.y)
        if (!playerView.isControllerFullyVisible) return false
        // The controller's views report their bounds in the window: convert the touch to it.
        val at = IntArray(2).also { getLocationInWindow(it) }
        val wx = x + at[0]
        val wy = y + at[1]
        val content = playerView.findViewById<View>(androidx.media3.ui.R.id.exo_content_frame)
        fun find(v: View): Boolean {
            if (v === content || !windowHit(v, wx, wy)) return false
            if (v !== playerView && (v.hasOnClickListeners() || v is androidx.media3.ui.TimeBar)) return true
            return v is ViewGroup && (0 until v.childCount).any { find(v.getChildAt(it)) }
        }
        return find(playerView)
    }

    /** [x], [y] in this view; [v] is one of its direct children. */
    private fun hit(v: View, x: Int, y: Int): Boolean {
        if (!v.isShown) return false
        val r = Rect()
        v.getHitRect(r)
        return r.contains(x, y)
    }

    private fun windowHit(v: View, x: Int, y: Int): Boolean {
        if (!v.isShown) return false
        val r = Rect()
        return v.getGlobalVisibleRect(r) && r.contains(x, y)
    }

    private fun applyZoom() {
        val frame = playerView.findViewById<View>(androidx.media3.ui.R.id.exo_content_frame) ?: return
        val z = if (inPictureInPicture) 1f else zoom
        frame.scaleX = z
        frame.scaleY = z
    }

    private fun haptic(constant: Int) {
        (window()?.decorView ?: this).performHapticFeedback(constant)
    }

    /** What each gesture does to the player. */
    private inner class GestureActions : GestureEngine.Callbacks {
        private var seekAnchor: Long? = null
        private var speedBefore = 1f
        private var zoomAtStart = 1f
        private var brightness = 0.5f
        private var volume = 0f
        private val audio by lazy { context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager }

        override fun onSingleTap() {
            if (!features.tapToggle) return
            if (!isTv) controls.toggle()
            else if (playerView.isControllerFullyVisible) playerView.hideController() else playerView.showController()
        }

        override fun onCenterDoubleTap() {
            if (!gesturesEnabled || !features.doubleTaps) return onSingleTap()
            val p = player ?: return
            if (p.isPlaying) p.pause() else p.play()
        }

        override fun onSeekTaps(forward: Boolean, seconds: Int) {
            if (!gesturesEnabled || !features.doubleTaps) return
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
            if (!gesturesEnabled || !features.levels) return
            if (side == GestureEngine.Side.LEFT) {
                val current = window()?.attributes?.screenBrightness ?: -1f
                brightness = if (current >= 0f) current else systemBrightness()
            } else {
                val max = audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                val system = audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
                // At the maximum with a boost on: carry on from the boost.
                volume = if (system >= max && session.boostLevel > 1f) session.boostLevel else system.toFloat() / max
            }
        }

        override fun onVertical(side: GestureEngine.Side, delta: Float) {
            if (!gesturesEnabled || !features.levels) return
            session.keepShowing("level")
            if (side == GestureEngine.Side.LEFT) {
                brightness = GestureMath.level(brightness, delta, min = 0.01f)
                window()?.let { w -> w.attributes = w.attributes.apply { screenBrightness = brightness } }
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
                android.provider.Settings.System.getInt(context.contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS) / 255f
            }.getOrDefault(0.5f)

        private var scrubbing = false

        override fun onScrubStart() {
            if (!gesturesEnabled || !features.scrub) return
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
            if (!gesturesEnabled || !features.holdSpeed) return false
            val p = player ?: return false
            if (!p.isPlaying) return false
            speedBefore = p.playbackParameters.speed
            p.setPlaybackSpeed(GestureMath.HOLD_SPEED)
            gestureUi.speed = GestureMath.HOLD_SPEED
            haptic(HapticFeedbackConstants.LONG_PRESS)
            return true
        }

        override fun onLongPressSlide(steps: Int) {
            val speed = GestureMath.speedAfterSlide(steps)
            if (speed == gestureUi.speed) return
            player?.setPlaybackSpeed(speed)
            gestureUi.speed = speed
            haptic(HapticFeedbackConstants.CLOCK_TICK)
        }

        override fun onLongPressEnd() {
            player?.setPlaybackSpeed(speedBefore)
            gestureUi.speed = null
        }

        private var pinching = false

        override fun onPinchStart() {
            pinching = gesturesEnabled && features.pinchZoom
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

    // --- views --------------------------------------------------------------------

    private fun buildNextCard(): View {
        nextTitle = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            maxWidth = dp(280)
        }
        nextCountdown = TextView(context).apply {
            setTextColor(0xCCFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(2), 0, dp(10))
        }
        fun pill(textRes: Int, primary: Boolean, onClick: () -> Unit) = TextView(context).apply {
            text = context.getString(textRes)
            setTextColor(if (primary) Color.BLACK else Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            val h = dp(16); val v = dp(8)
            setPadding(h, v, h, v)
            background = focusable(if (primary) Color.WHITE else 0x33FFFFFF, dp(18))
            isFocusable = true // TV remote
            setOnClickListener { onClick() }
        }
        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            nextPlayNow = pill(R.string.player_play_now, primary = true) { session.playNext() }
            addView(nextPlayNow)
            addView(
                pill(R.string.action_cancel, primary = false) { session.cancelNext() },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { marginStart = dp(8) }
            )
        }
        return LinearLayout(context).apply {
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
            visibility = GONE
        }
    }

    /** Rounded background with a white ring when focused (TV remote). */
    private fun focusable(color: Int, radius: Int): Drawable {
        fun shape(ring: Boolean) = GradientDrawable().apply {
            cornerRadius = radius.toFloat()
            setColor(color)
            if (ring) setStroke(dp(2), Color.WHITE)
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), shape(ring = true))
            addState(intArrayOf(), shape(ring = false))
        }
    }

    private fun isInside(view: View, parent: View): Boolean {
        var v: View? = view
        while (v != null) {
            if (v === parent) return true
            v = v.parent as? View
        }
        return false
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private companion object {
        fun resizeModeFor(fill: Fill) = when (fill) {
            Fill.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
            Fill.CROP -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            Fill.STRETCH -> AspectRatioFrameLayout.RESIZE_MODE_FILL
        }
    }
}
