package com.abhinavxt.debforge.ui.video

import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import com.abhinavxt.debforge.R
import com.abhinavxt.debforge.player.cast.DlnaCaster
import com.abhinavxt.debforge.player.cast.Renderer
import com.abhinavxt.debforge.player.controls.ControlsActions
import com.abhinavxt.debforge.player.controls.Fill
import com.abhinavxt.debforge.player.controls.fillLabel
import com.abhinavxt.debforge.player.session.VideoCast
import com.abhinavxt.debforge.player.session.VideoScreenshot
import com.abhinavxt.debforge.player.session.VideoSession
import com.abhinavxt.debforge.player.session.VideoSurface
import com.abhinavxt.debforge.ui.browse.PlayRequest
import com.abhinavxt.debforge.ui.browse.playExternally
import kotlinx.coroutines.launch

/**
 * The player controls' actions for the in-app player: the engine's own plus what needs the screen (back,
 * the full-screen / rotate button, fill, another player, casting, screenshots, the system bars).
 * Picture-in-picture is not offered here yet (the controls hide its button).
 */
@androidx.annotation.OptIn(UnstableApi::class)
class InAppVideoActions(
    private val activity: ComponentActivity,
    private val session: VideoSession,
    engine: ControlsActions,
    private val caster: DlnaCaster,
    private val surface: () -> VideoSurface?,
    private val onBack: () -> Unit,
    private val onRotate: () -> Unit,
    /** More → Rotate in full screen. */
    private val onRotateScreen: () -> Unit,
    private val onBars: (Boolean) -> Unit,
    /** Closes the video (leaving it on the TV, or handing it to another player). */
    private val onClose: () -> Unit,
) : ControlsActions by engine {

    private val controls get() = session.controls
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    /** Casting (before init: a recreated screen reopens the remote right away). */
    private val casting = VideoCast(activity, session, caster, onLeave = onClose, track = { it.tracked() }, onCasting = { castingTo = it })

    /** Framework dialogs this screen opened: a recreated activity (theme, locale) must not leak their windows. */
    private val dialogs = mutableListOf<android.app.Dialog>()

    private fun <T : android.app.Dialog> T.tracked(): T {
        dialogs.removeAll { !it.isShowing }
        dialogs += this
        return this
    }

    init {
        activity.lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onDestroy(owner: androidx.lifecycle.LifecycleOwner) {
                dialogs.toList().forEach { runCatching { it.dismiss() } }
                dialogs.clear()
                owner.lifecycle.removeObserver(this)
            }
        })
        // The activity was recreated while casting: the remote's dialog went with the old one, the TV still plays.
        if (controls.casting) {
            val renderer = castingTo
            if (renderer == null) controls.casting = false else activity.lifecycleScope.launch { showCastRemote(renderer) }
        }
    }

    override fun back() = onBack()
    override fun rotate() = onRotate()
    override fun rotateScreen() = onRotateScreen()
    override fun enterPip() {}
    override fun onBarsVisible(visible: Boolean) = onBars(visible)

    override fun setFill(fill: Fill) {
        surface()?.setFill(fill)
        session.showMessage(activity.getString(fillLabel(fill)))
    }

    override fun openExternal() = openExternally()

    override fun screenshot() {
        controls.panel = null
        takeScreenshot()
    }

    /** Another player: not for YouTube (its audio is a separate URL). */
    fun openExternally() {
        if (session.youtube != null) return
        // Subtitles saved in DebForge's cache (OpenSubtitles) aren't readable by other apps.
        if (!activity.playExternally(PlayRequest(session.uri, session.title, subtitles = session.subtitles.filterNot { it.url.startsWith("file:") }))) {
            Toast.makeText(activity, R.string.msg_no_player, Toast.LENGTH_LONG).show()
            session.player?.play()
            return
        }
        // Nothing to come back to: the other player has it now.
        onClose()
    }

    private fun takeScreenshot() = VideoScreenshot.take(activity, session, surface()?.playerView, handler)

    // --- cast (DLNA) ------------------------------------------------------------------

    /** Play on a TV: finds DLNA renderers on the network and hands the picked one the stream. */
    override fun cast() = casting.start()

    private fun showCastRemote(on: Renderer) = casting.showRemote(on)

    private companion object {
        /** Where the video plays now; survives the activity being recreated (the engine does too). */
        @Volatile var castingTo: Renderer? = null
    }
}
