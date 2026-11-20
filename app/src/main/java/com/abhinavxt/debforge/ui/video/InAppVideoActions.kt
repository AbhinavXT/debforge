package com.abhinavxt.debforge.ui.video

import android.app.AlertDialog
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
import com.abhinavxt.debforge.player.session.VideoSession
import com.abhinavxt.debforge.player.session.VideoSurface
import com.abhinavxt.debforge.ui.browse.PlayRequest
import com.abhinavxt.debforge.ui.browse.playExternally
import kotlinx.coroutines.Job
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
    private val onBars: (Boolean) -> Unit,
    /** Closes the video (leaving it on the TV, or handing it to another player). */
    private val onClose: () -> Unit,
) : ControlsActions by engine {

    private val controls get() = session.controls
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

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

    // --- screenshot (the current frame to Pictures/DebForge) -----------------------------

    private fun takeScreenshot() {
        val videoView = surface()?.playerView?.videoSurfaceView as? android.view.SurfaceView
        val v = session.player?.videoSize
        if (videoView == null || v == null || v.width <= 0 || v.height <= 0) {
            session.showMessage(activity.getString(R.string.player_screenshot_failed))
            return
        }
        val w = (v.width * v.pixelWidthHeightRatio).toInt().coerceAtLeast(1)
        val scale = minOf(1f, 3840f / maxOf(w, v.height))
        val bitmap = android.graphics.Bitmap.createBitmap(
            (w * scale).toInt().coerceAtLeast(1), (v.height * scale).toInt().coerceAtLeast(1), android.graphics.Bitmap.Config.ARGB_8888
        )
        val title = session.title
        android.view.PixelCopy.request(videoView, bitmap, { result ->
            if (result != android.view.PixelCopy.SUCCESS) {
                session.showMessage(activity.getString(R.string.player_screenshot_failed))
                return@request
            }
            activity.lifecycleScope.launch {
                val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { saveImage(bitmap, title) }
                bitmap.recycle()
                session.showMessage(activity.getString(if (ok) R.string.player_screenshot_saved else R.string.player_screenshot_failed))
            }
        }, handler)
    }

    private fun saveImage(bitmap: android.graphics.Bitmap, title: String): Boolean = runCatching {
        val safeTitle = title.replace(Regex("""[^\p{L}\p{N} ._-]"""), "_").trim().take(60).ifEmpty { "video" }
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "${safeTitle}_${System.currentTimeMillis()}.jpg")
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, android.os.Environment.DIRECTORY_PICTURES + "/DebForge")
            put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = activity.contentResolver
        val uri = resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return@runCatching false
        val written = resolver.openOutputStream(uri)?.use { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, it) } == true
        if (!written) {
            resolver.delete(uri, null, null)
            return@runCatching false
        }
        values.clear()
        values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        true
    }.getOrDefault(false)

    // --- cast (DLNA) ------------------------------------------------------------------

    /** Play on a TV: finds DLNA renderers on the network and hands the picked one the stream. */
    override fun cast() {
        val p = session.player ?: return
        p.pause()
        var search: Job? = null
        val searching = AlertDialog.Builder(activity)
            .setTitle(R.string.cast_title)
            .setMessage(R.string.cast_searching)
            .setNegativeButton(R.string.action_cancel) { _, _ -> search?.cancel() }
            .setOnCancelListener { search?.cancel() }
            .show().tracked()
        search = activity.lifecycleScope.launch {
            val found = caster.discover()
            searching.dismiss()
            if (activity.isFinishing) return@launch
            if (found.isEmpty()) {
                AlertDialog.Builder(activity)
                    .setTitle(R.string.cast_title)
                    .setMessage(R.string.cast_none)
                    .setPositiveButton(R.string.action_retry) { _, _ -> cast() }
                    .setNegativeButton(R.string.action_close, null)
                    .show().tracked()
                return@launch
            }
            AlertDialog.Builder(activity)
                .setTitle(R.string.cast_title)
                .setItems(found.map { it.name }.toTypedArray()) { _, i -> startCast(found[i]) }
                .setNegativeButton(R.string.action_cancel, null)
                .show().tracked()
        }
    }

    private fun startCast(to: Renderer) {
        val at = session.player?.currentPosition ?: 0L
        val uri = session.uri
        val title = session.title
        activity.lifecycleScope.launch {
            if (runCatching { caster.play(to, uri.toString(), title, at) }.isFailure) {
                Toast.makeText(activity, activity.getString(R.string.cast_failed, to.name), Toast.LENGTH_LONG).show()
                return@launch
            }
            if (!activity.isFinishing) showCastRemote(to)
        }
    }

    /** While the TV plays: pause / play it, take the video back here, or leave it there and close. */
    private fun showCastRemote(on: Renderer) {
        var paused = false
        controls.casting = true
        castingTo = on
        val dialog = AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.cast_playing_on, on.name))
            .setMessage(session.title)
            .setCancelable(false)
            .setPositiveButton(R.string.cast_pause, null)
            .setNegativeButton(R.string.cast_stop) { _, _ ->
                activity.lifecycleScope.launch {
                    // Carry on here from where the TV got to.
                    val at = caster.positionMs(on)
                    runCatching { caster.stop(on) }
                    controls.casting = false
                    castingTo = null
                    session.player?.let { p -> at?.let(p::seekTo); p.play() }
                }
            }
            .setNeutralButton(R.string.cast_leave) { _, _ -> castingTo = null; onClose() }
            .create()
        dialog.setOnShowListener {
            // Set here so the button doesn't close the dialog.
            val toggle = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            toggle.setOnClickListener {
                activity.lifecycleScope.launch {
                    val ok = runCatching { if (paused) caster.resume(on) else caster.pause(on) }.isSuccess
                    if (!ok) return@launch
                    paused = !paused
                    toggle.setText(if (paused) R.string.cast_play else R.string.cast_pause)
                }
            }
        }
        dialog.tracked().show()
    }

    private companion object {
        /** Where the video plays now; survives the activity being recreated (the engine does too). */
        @Volatile var castingTo: Renderer? = null
    }
}
