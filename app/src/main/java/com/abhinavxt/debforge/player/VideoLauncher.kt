package com.abhinavxt.debforge.player

import android.content.Context
import android.content.res.Configuration
import com.abhinavxt.debforge.domain.YouTubeVideo
import com.abhinavxt.debforge.player.session.VideoSession
import com.abhinavxt.debforge.player.session.VideoTarget
import com.abhinavxt.debforge.ui.browse.PlayRequest
import com.abhinavxt.debforge.ui.video.ActiveVideo
import com.abhinavxt.debforge.ui.video.VideoMode
import com.abhinavxt.debforge.ui.video.VideoModeEvent
import com.abhinavxt.debforge.ui.video.nextMode
import com.abhinavxt.debforge.data.prefs.SettingsStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The one way to start a video. Phones and tablets play it in the app (the watch page, full screen and
 * the mini bar, drawn by InAppVideoHost over the app); a TV opens [PlayerActivity] as it always did.
 * Holds which video is shown and in what [mode]; the engine itself is [VideoSession].
 */
@Singleton
class VideoLauncher @Inject constructor(
    val session: VideoSession,
    settingsStore: SettingsStore,
) {
    /** The "Open videos in full screen" setting, kept current. */
    @Volatile private var openFull = false

    /** The shown video was opened full screen by that setting: Back from full screen closes it. */
    @Volatile private var backClosesFull = false

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            settingsStore.videoFullScreenFlow.collect { openFull = it }
        }
    }

    private val _active = MutableStateFlow<ActiveVideo?>(null)
    /** The video shown in the app; null when none. */
    val active: StateFlow<ActiveVideo?> = _active.asStateFlow()

    private val _mode = MutableStateFlow<VideoMode?>(null)
    val mode: StateFlow<VideoMode?> = _mode.asStateFlow()

    /** Bumped when something the watch page shows changed in the engine (the title, the episodes, the picture size). */
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    companion object {
        /** Extra on the intent that brings the app to the front with the video: the watch page opens. */
        const val EXTRA_OPEN_VIDEO = "openVideo"
    }

    /** Set by the app shell: the screen is a tablet's (smallest width 600 dp). */
    @Volatile var isTablet = false

    /** [open] is replacing the video: the engine tells its host to close, which here must not end the replacement. */
    private var replacing = false

    /**
     * Plays [request]. [start] comes from the audio player ("Watch video"). [video] is what the watch page
     * knows of a YouTube stream (its related videos). [startFull]: straight to full screen.
     */
    fun open(
        context: Context,
        request: PlayRequest,
        start: VideoStart? = null,
        video: YouTubeVideo? = null,
        startFull: Boolean = false,
    ) {
        if (session.isTv) {
            context.startActivity(PlayerActivity.intent(context, request, start))
            return
        }
        replacing = true
        val token = try {
            session.open(VideoTarget.from(request), start = start, restoreSaved = restoresSavedPosition(start))
        } finally {
            replacing = false
        }
        _active.value = ActiveVideo(token, request, video)
        // On a phone held sideways the picture goes straight to full screen.
        val landscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        backClosesFull = openFull
        dispatch(VideoModeEvent.Open(startFull = startFull || openFull || (landscape && !isTablet)))
    }

    /** Changes the shown mode by [event] (see [nextMode]). */
    fun dispatch(event: VideoModeEvent) {
        val next = nextMode(_mode.value, event, isTablet, backClosesFull)
        if (next == null && event == VideoModeEvent.Back) {
            close()
            return
        }
        _mode.value = next
        if (next != VideoMode.FULL) backClosesFull = false
        if (next == null) _active.value = null
    }

    /** Ends the video: saved, the parked audio told where it stopped, the player released. */
    fun close() {
        if (_active.value == null && !session.isOpen) return
        session.close()
        _active.value = null
        _mode.value = null
        backClosesFull = false
    }

    /** The engine asks its host to close (a failed stream, "Play as audio", a replaced video). */
    fun engineClosed() {
        if (!replacing) close()
    }

    /** A YouTube stream's details arrived after it started (its related videos, channel). */
    fun detailsArrived(token: Long, video: YouTubeVideo) {
        _active.value = _active.value?.takeIf { it.token == token }?.copy(video = video) ?: return
    }

    fun changed() {
        _revision.value++
    }
}

@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
internal interface VideoLauncherEntryPoint {
    fun videoLauncher(): VideoLauncher
}

/** The [VideoLauncher] for a screen that starts videos. */
@androidx.compose.runtime.Composable
fun rememberVideoLauncher(): VideoLauncher {
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext
    return androidx.compose.runtime.remember {
        dagger.hilt.android.EntryPointAccessors.fromApplication(app, VideoLauncherEntryPoint::class.java).videoLauncher()
    }
}
