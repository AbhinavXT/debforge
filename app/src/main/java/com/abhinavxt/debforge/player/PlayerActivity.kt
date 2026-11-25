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
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.lifecycle.lifecycleScope
import com.abhinavxt.debforge.R
import com.abhinavxt.debforge.domain.ProviderId
import com.abhinavxt.debforge.domain.SubtitleLink
import com.abhinavxt.debforge.player.controls.ControlsActions
import com.abhinavxt.debforge.player.controls.Fill
import com.abhinavxt.debforge.player.controls.Panel
import com.abhinavxt.debforge.player.controls.fillLabel
import com.abhinavxt.debforge.player.session.VideoHost
import com.abhinavxt.debforge.player.session.VideoIdentity
import com.abhinavxt.debforge.player.session.SurfaceMode
import com.abhinavxt.debforge.player.session.VideoSession
import com.abhinavxt.debforge.player.session.VideoSurface
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

    /** The video, its controls, cards and touch gestures (FULL: the whole screen). */
    private lateinit var surface: VideoSurface
    private val playerView get() = surface.playerView
    private val gestureHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** The engine's controls actions plus this screen's own (back, rotate, fill, PiP…). */
    private lateinit var controlsActions: ControlsActions
    /** The user turned the screen with the rotate button: stop following the video's shape. */
    private var userRotated = false

    private var networkDialog: AlertDialog? = null

    /** Android TV: move focus to overlays when they appear (no touch screen). */
    private val isTv get() = session.isTv

    /** Back while "Next: …" is showing cancels it instead of leaving the player. */
    private val cancelNextOnBack = object : androidx.activity.OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = session.cancelNext()
    }

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
        surface = VideoSurface(
            this, session, settings, { window }, isTv, controlsActions, SurfaceMode.FULL
        ).apply {
            onControllerVisibility = { hideSystemBars() }
        }
        lifecycleScope.launch {
            pipAllowed = settings.pipFlow.first()
            surface.gesturesEnabled = settings.playerGesturesFlow.first()
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

        setContentView(surface)
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
            surface.titleChanged(title)
        }

        override fun showStartOver(show: Boolean) = surface.showStartOver(show)

        override fun showSkip(text: String, onSkip: () -> Unit) = surface.showSkip(text, onSkip)

        override fun hideSkip() = surface.hideSkip()

        override fun showNextCard(text: String) {
            surface.showNextCard(text)
            cancelNextOnBack.isEnabled = true
        }

        override fun setNextCountdown(text: String?) = surface.setNextCountdown(text)

        override fun hideNextCard() {
            cancelNextOnBack.isEnabled = false
            if (::surface.isInitialized) surface.hideNextCard()
        }

        override fun showStats(on: Boolean) = surface.showStats(on)

        override fun statsText(text: String) = surface.statsText(text)

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
        val overlayFocused = ::surface.isInitialized && surface.ownsFocus(focus)
        if (!overlayFocused && ::surface.isInitialized && playerView.dispatchKeyEvent(event)) return true
        return super.dispatchKeyEvent(event)
    }


    /** TV: the remote's MENU key offers "Play as audio" (Media3's controller has no slot for it). False when there is nothing to offer. */
    private fun showTvOptions(): Boolean {
        val options = buildList<Pair<String, () -> Unit>> {
            if (session.controls.bookmarkKey != null && !controlsActions.snapshot().live) {
                add(getString(R.string.bookmarks) to { session.controls.panel = Panel.BOOKMARKS })
            }
            if (session.canPlayAsAudio()) add(getString(R.string.player_play_as_audio) to { session.handOffToAudio() })
        }
        if (options.isEmpty()) return false
        AlertDialog.Builder(this)
            .setItems(options.map { it.first }.toTypedArray()) { _, i -> options[i].second() }
            .show()
        return true
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
            surface.setFill(fill)
            session.showMessage(getString(fillLabel(fill)))
        }

        override fun enterPip() {
            if (!session.isCurrent(token)) return
            runCatching { enterPictureInPictureMode(pipParams()) }
        }

        override fun screenshot() {
            if (!session.isCurrent(token)) return
            controls.panel = null
            takeScreenshot()
        }

        override fun onBarsVisible(visible: Boolean) {
            if (isInPictureInPictureMode) return
            // Showing the controls keeps the system bars hidden: they only come back on an edge swipe.
            hideSystemBars()
        }
    }

    /** The current frame as a JPEG in Pictures/DebForge. */
    private fun takeScreenshot() = com.abhinavxt.debforge.player.session.VideoScreenshot.take(this, session, playerView, gestureHandler)

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
        if (::surface.isInitialized && playerView.getGlobalVisibleRect(bounds)) builder.setSourceRectHint(bounds)
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
        if (!session.isCurrent(token)) return // a replaced video's window: it is closing
        // Just the video in the small window: no controls, title or cards.
        surface.setPictureInPicture(isInPictureInPictureMode)
        if (isInPictureInPictureMode) {
            controls.panel = null
            controls.visible = false
            session.hostResumed(token) // in the background service? the window plays it now
        } else if (lifecycle.currentState == Lifecycle.State.CREATED) {
            // The window was closed (not expanded): the activity is stopped
            // with nothing to come back to. The position was saved in onStop.
            finish()
        } else {
            hideSystemBars()
        }
    }

    override fun onDestroy() {
        if (::surface.isInitialized) {
            session.hostDestroyed(token, isFinishing)
            session.detach(playerView)
        }
        if (::surface.isInitialized) surface.release()
        gestureHandler.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(pipReceiver) }
        super.onDestroy()
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
        if (session.isCurrent(token)) session.hostResumed(token)
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
    private val casting by lazy { com.abhinavxt.debforge.player.session.VideoCast(this, session, caster, onLeave = ::finish) }

    private fun castToTv() = casting.start()

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
