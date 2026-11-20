package com.abhinavxt.debforge.ui.video

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.abhinavxt.debforge.R
import com.abhinavxt.debforge.data.prefs.SettingsStore
import com.abhinavxt.debforge.player.VideoLauncher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Pure: may the app enter picture-in-picture when the user leaves? Only a video that is shown (watch page,
 * full screen or mini bar) and playing, with the device and the setting allowing it, and never while a web page's
 * own full-screen video covers the Browser tab.
 */
internal fun shouldEnterPip(
    supported: Boolean,
    allowed: Boolean,
    videoShown: Boolean,
    playing: Boolean,
    webFullscreen: Boolean,
): Boolean = supported && allowed && videoShown && playing && !webFullscreen

/**
 * Pure: Android 11 (no automatic entry) enters the floating window when the user leaves, but not when the app itself
 * opened another activity (share sheet, file picker, external player): that is not leaving.
 */
internal fun pipOnLeaveHint(sdk: Int, canEnter: Boolean, ownActivityStarting: Boolean): Boolean =
    sdk < Build.VERSION_CODES.S && canEnter && !ownActivityStarting

/** The mode shown after the floating window is expanded: the mini bar opens the watch page, the others stay. */
internal fun modeAfterPipExpand(mode: VideoMode): VideoMode = if (mode == VideoMode.MINI) VideoMode.WATCH else mode

/** Is the app in the floating window? Read by the in-app video host to draw only the picture. */
object PipState {
    private val _inPip = MutableStateFlow(false)
    val inPip: StateFlow<Boolean> = _inPip.asStateFlow()
    internal fun set(value: Boolean) { _inPip.value = value }
}

/** A web page's own video is full screen in the Browser tab: the app must not enter picture-in-picture then. */
object WebFullscreen {
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()
    fun set(value: Boolean) { _active.value = value }
}

@Composable
fun rememberInPip(): Boolean {
    val inPip by PipState.inPip.collectAsStateWithLifecycle()
    return inPip
}

/**
 * Picture-in-picture for the in-app video, run by MainActivity: keeps the window's settings (shape, buttons,
 * automatic entry) up to date, answers the buttons, and ends the video when the floating window is closed.
 */
internal class VideoPip(
    private val activity: ComponentActivity,
    private val launcher: VideoLauncher,
    private val settings: SettingsStore,
) {
    private val session get() = launcher.session
    private val supported = activity.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    private var allowed = true
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!session.isOpen) return
            when (intent.action) {
                ACTION_PLAY_PAUSE -> session.actions.playPause()
                ACTION_BACK -> session.actions.seekBy(-SKIP_MS)
                ACTION_FORWARD -> session.actions.seekBy(SKIP_MS)
                ACTION_NEXT -> session.playNext()
            }
        }
    }

    private val listener: () -> Unit = { update() }
    private val gate: () -> Boolean = { activity.isInPictureInPictureMode }

    fun start() {
        if (!supported) return
        ContextCompat.registerReceiver(
            activity, receiver,
            IntentFilter().apply { addAction(ACTION_PLAY_PAUSE); addAction(ACTION_BACK); addAction(ACTION_FORWARD); addAction(ACTION_NEXT) },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        registered = true
        session.pipListener = listener
        session.pipGate = gate
        activity.lifecycleScope.launch {
            allowed = settings.pipFlow.first()
            update()
        }
        activity.lifecycleScope.launch { settings.pipFlow.collect { allowed = it; update() } }
        activity.lifecycleScope.launch {
            combine(launcher.active, launcher.mode, WebFullscreen.active) { a, m, w -> Triple(a, m, w) }.collect { update() }
        }
    }

    fun stop() {
        if (registered) runCatching { activity.unregisterReceiver(receiver) }
        registered = false
        if (session.pipListener === listener) session.pipListener = null
        if (session.pipGate === gate) session.pipGate = null
    }

    private fun videoShown() = launcher.active.value != null && launcher.mode.value != null && session.isOpen

    private fun canEnter() = shouldEnterPip(
        supported, allowed, videoShown(), session.player?.isPlaying == true, WebFullscreen.active.value
    )

    fun update() {
        if (!supported) return
        runCatching { activity.setPictureInPictureParams(params()) }
    }

    /** The app is opening another activity of its own: until it is back, that is not the user leaving. */
    private var ownActivityStarting = false

    /** Called by the activity before it starts any activity (startActivity, result launchers, intent senders). */
    fun ownActivityStarting() { ownActivityStarting = true }

    /** The activity is in front again. */
    fun resumed() { ownActivityStarting = false }

    /** Android 11 has no automatic entry: enter when the user leaves. */
    fun onUserLeaveHint() {
        if (pipOnLeaveHint(Build.VERSION.SDK_INT, canEnter(), ownActivityStarting)) {
            runCatching { activity.enterPictureInPictureMode(params()) }
        }
    }

    fun modeChanged(inPip: Boolean) {
        PipState.set(inPip)
        if (!session.isOpen) return
        if (inPip) {
            session.controls.panel = null
            session.controls.visible = false
            session.hostResumed(session.token) // handed to the service? the window plays it now
        } else if (activity.lifecycle.currentState == Lifecycle.State.CREATED) {
            // The window was closed (not expanded): the activity is stopped with nothing to come back to.
            launcher.close()
        } else {
            val mode = launcher.mode.value
            if (mode != null && modeAfterPipExpand(mode) != mode) launcher.dispatch(VideoModeEvent.Expand)
            if (launcher.mode.value == VideoMode.FULL) FullScreen.bars(activity, false)
        }
    }

    private fun params(): PictureInPictureParams {
        val player = session.player
        val builder = PictureInPictureParams.Builder()
        player?.videoSize?.takeIf { it.width > 0 && it.height > 0 }?.let { v ->
            // Android only accepts ratios between about 1:2.39 and 2.39:1.
            val w = (v.width * v.pixelWidthHeightRatio).toInt().coerceAtLeast(1)
            val ratio = (w.toFloat() / v.height).coerceIn(0.42f, 2.38f)
            builder.setAspectRatio(Rational((ratio * 1000).toInt(), 1000))
        }
        val bounds = android.graphics.Rect()
        session.playerView?.takeIf { it.getGlobalVisibleRect(bounds) && !bounds.isEmpty }?.let { builder.setSourceRectHint(bounds) }
        val playing = player?.isPlaying == true
        val actions = buildList {
            add(action(R.drawable.ic_action_replay, activity.getString(R.string.audio_back_n, SKIP_MS / 1000), ACTION_BACK, 1))
            val label = activity.getString(if (playing) R.string.action_pause else R.string.action_play)
            add(action(if (playing) R.drawable.ic_action_pause else R.drawable.ic_action_play, label, ACTION_PLAY_PAUSE, 2))
            add(action(R.drawable.ic_action_forward, activity.getString(R.string.audio_forward_n, SKIP_MS / 1000), ACTION_FORWARD, 3))
            if (session.hasNext && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                activity.maxNumPictureInPictureActions > 3
            ) {
                add(action(R.drawable.ic_action_next, activity.getString(R.string.player_next_episode), ACTION_NEXT, 4))
            }
        }
        builder.setActions(actions)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(canEnter())
            builder.setSeamlessResizeEnabled(true)
        }
        return builder.build()
    }

    private fun action(icon: Int, label: String, action: String, code: Int) = RemoteAction(
        Icon.createWithResource(activity, icon), label, label,
        PendingIntent.getBroadcast(
            activity, code, Intent(action).setPackage(activity.packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    )

    private companion object {
        const val ACTION_PLAY_PAUSE = "com.abhinavxt.debforge.video.PIP_PLAY_PAUSE"
        const val ACTION_BACK = "com.abhinavxt.debforge.video.PIP_BACK"
        const val ACTION_FORWARD = "com.abhinavxt.debforge.video.PIP_FORWARD"
        const val ACTION_NEXT = "com.abhinavxt.debforge.video.PIP_NEXT"
        const val SKIP_MS = 10_000L
    }
}
