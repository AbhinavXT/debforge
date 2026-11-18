package com.abhinavxt.debforge.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.transform.RoundedCornersTransformation
import com.abhinavxt.debforge.player.AudioPlayer
import com.abhinavxt.debforge.player.AudioTrack
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the player widget in step with [AudioPlayer] while the process is alive: pushes when the
 * track, play state or Previous / Next availability changes. Position is not shown, so playing
 * causes no per-second updates.
 */
@Singleton
class PlayerWidgetSync @Inject constructor(
    @ApplicationContext private val context: Context,
    private val audio: AudioPlayer
) {
    /** Android refused to start playback from the background: Play opens the app until something plays. */
    private val startBlocked = MutableStateFlow(false)

    @Volatile private var art: Pair<String, Bitmap>? = null

    fun start(scope: CoroutineScope) {
        scope.launch {
            // A new process: bring the saved queue back so the widget shows it (and its buttons work).
            restoreForWidget()
            combine(audio.state, startBlocked) { a, blocked ->
                if (blocked && (a.isPlaying || a.track == null)) startBlocked.value = false
                Key(a.track?.id, a.track?.title, a.track?.artist, a.isPlaying, a.hasPrevious, a.hasNext, audio.isActive && !blocked)
            }.distinctUntilChanged().collectLatest { refresh() }
        }
    }

    private data class Key(
        val id: String?, val title: String?, val artist: String?,
        val playing: Boolean, val hasPrevious: Boolean, val hasNext: Boolean, val live: Boolean
    )

    /** Playback could not be started from a widget tap: switch the Play button to "open the app". */
    fun markStartBlocked() { startBlocked.value = true }

    suspend fun restoreForWidget() {
        if (PlayerWidgetRenderer.widgetIds(context).isEmpty()) return
        runCatching { withContext(Dispatchers.Main) { audio.restore() } }
    }

    /** Renders the current state now (text first, artwork once it has loaded). */
    suspend fun refresh() {
        if (PlayerWidgetRenderer.widgetIds(context).isEmpty()) return
        val a = audio.state.value
        val track = a.track
        val cached = track?.let { t -> art?.takeIf { it.first == t.id }?.second }
        runCatching { PlayerWidgetRenderer.push(context, stateOf(a, cached, audio.isActive && !startBlocked.value)) }
        if (track == null) { art = null; return }
        if (cached != null) return
        val bmp = loadArt(track) ?: return
        art = track.id to bmp
        val now = audio.state.value
        if (now.track?.id == track.id) {
            runCatching { PlayerWidgetRenderer.push(context, stateOf(now, bmp, audio.isActive && !startBlocked.value)) }
        }
    }

    private suspend fun loadArt(track: AudioTrack): Bitmap? {
        val data: Any = track.artworkFile?.takeIf { it.exists() } ?: track.artworkUrl ?: return null
        return try {
            val request = ImageRequest.Builder(context).data(data)
                .size(ART_PX).allowHardware(false)
                .transformations(RoundedCornersTransformation(ART_PX * 0.125f))
                .build()
            ((context.imageLoader.execute(request) as? SuccessResult)?.drawable as? BitmapDrawable)?.bitmap
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    private companion object {
        /** RemoteViews bitmaps count against a binder size limit, so keep them small. */
        const val ART_PX = 256
    }
}
