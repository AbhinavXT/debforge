package com.abhinavxt.debforge.player.session

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.view.PixelCopy
import android.view.SurfaceView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.abhinavxt.debforge.R
import com.abhinavxt.debforge.player.cast.DlnaCaster
import com.abhinavxt.debforge.player.cast.Renderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The player's screenshot: the current frame (without subtitles or controls) as a JPEG in Pictures/DebForge.
 * No permission needed: MediaStore owns the folder. Shared by PlayerActivity and the in-app player.
 */
@OptIn(UnstableApi::class)
internal object VideoScreenshot {

    /** Copies the frame shown in [playerView]; the result is told through the engine's message. */
    fun take(activity: ComponentActivity, session: VideoSession, playerView: PlayerView?, handler: Handler) {
        val videoView = playerView?.videoSurfaceView as? SurfaceView
        val v = session.player?.videoSize
        if (videoView == null || v == null || v.width <= 0 || v.height <= 0) {
            session.showMessage(activity.getString(R.string.player_screenshot_failed))
            return
        }
        val w = (v.width * v.pixelWidthHeightRatio).toInt().coerceAtLeast(1)
        val scale = minOf(1f, 3840f / maxOf(w, v.height))
        val bitmap = Bitmap.createBitmap(
            (w * scale).toInt().coerceAtLeast(1), (v.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888
        )
        val title = session.title
        PixelCopy.request(videoView, bitmap, { result ->
            if (result != PixelCopy.SUCCESS) {
                session.showMessage(activity.getString(R.string.player_screenshot_failed))
                return@request
            }
            activity.lifecycleScope.launch {
                val ok = withContext(Dispatchers.IO) { save(activity, bitmap, title) }
                bitmap.recycle()
                session.showMessage(activity.getString(if (ok) R.string.player_screenshot_saved else R.string.player_screenshot_failed))
            }
        }, handler)
    }

    private fun save(context: Context, bitmap: Bitmap, title: String): Boolean = runCatching {
        val safeTitle = title.replace(Regex("""[^\p{L}\p{N} ._-]"""), "_").trim().take(60).ifEmpty { "video" }
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "${safeTitle}_${System.currentTimeMillis()}.jpg")
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, android.os.Environment.DIRECTORY_PICTURES + "/DebForge")
            put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return@runCatching false
        val written = resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) } == true
        if (!written) {
            resolver.delete(uri, null, null)
            return@runCatching false
        }
        values.clear()
        values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        true
    }.getOrDefault(false)
}

/**
 * Play on a TV (DLNA): finds renderers on the network, hands the picked one the stream, then shows a remote
 * while the TV plays. Shared by PlayerActivity and the in-app player.
 */
@OptIn(UnstableApi::class)
internal class VideoCast(
    private val activity: ComponentActivity,
    private val session: VideoSession,
    private val caster: DlnaCaster,
    /** "Leave it playing there": the video closes here. */
    private val onLeave: () -> Unit,
    /** Each dialog opened, for a host that dismisses them itself when it goes away. */
    private val track: (Dialog) -> Unit = {},
    /** The TV now playing it (null: stopped or left), for a host that restores the remote after being recreated. */
    private val onCasting: (Renderer?) -> Unit = {},
) {
    private val controls get() = session.controls

    /** Searches, then offers the TVs found. */
    fun start() {
        val p = session.player ?: return
        p.pause()
        var search: Job? = null
        val searching = AlertDialog.Builder(activity)
            .setTitle(R.string.cast_title)
            .setMessage(R.string.cast_searching)
            .setNegativeButton(R.string.action_cancel) { _, _ -> search?.cancel() }
            .setOnCancelListener { search?.cancel() }
            .show().also(track)
        search = activity.lifecycleScope.launch {
            val found = caster.discover()
            searching.dismiss()
            if (activity.isFinishing) return@launch
            if (found.isEmpty()) {
                AlertDialog.Builder(activity)
                    .setTitle(R.string.cast_title)
                    .setMessage(R.string.cast_none)
                    .setPositiveButton(R.string.action_retry) { _, _ -> start() }
                    .setNegativeButton(R.string.action_close, null)
                    .show().also(track)
                return@launch
            }
            AlertDialog.Builder(activity)
                .setTitle(R.string.cast_title)
                .setItems(found.map { it.name }.toTypedArray()) { _, i -> play(found[i]) }
                .setNegativeButton(R.string.action_cancel, null)
                .show().also(track)
        }
    }

    private fun play(to: Renderer) {
        val at = session.player?.currentPosition ?: 0L
        val uri = session.uri
        val title = session.title
        activity.lifecycleScope.launch {
            if (runCatching { caster.play(to, uri.toString(), title, at) }.isFailure) {
                Toast.makeText(activity, activity.getString(R.string.cast_failed, to.name), Toast.LENGTH_LONG).show()
                return@launch
            }
            if (!activity.isFinishing) showRemote(to)
        }
    }

    /** While the TV plays: pause / play it, take the video back here, or leave it there and close. */
    fun showRemote(on: Renderer) {
        var paused = false
        controls.casting = true
        onCasting(on)
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
                    onCasting(null)
                    session.player?.let { p -> at?.let(p::seekTo); p.play() }
                }
            }
            .setNeutralButton(R.string.cast_leave) { _, _ -> onCasting(null); onLeave() }
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
        track(dialog)
        dialog.show()
    }
}
