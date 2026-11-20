package com.abhinavxt.debforge.ui.video

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import androidx.media3.common.util.UnstableApi
import com.abhinavxt.debforge.R
import com.abhinavxt.debforge.data.playback.Playlist
import com.abhinavxt.debforge.player.controls.Panel
import com.abhinavxt.debforge.player.session.SurfaceMode
import com.abhinavxt.debforge.player.session.VideoHost
import com.abhinavxt.debforge.player.session.VideoSession
import com.abhinavxt.debforge.player.session.VideoSurface
import com.abhinavxt.debforge.ui.browse.YtStream
import com.abhinavxt.debforge.ui.player.animatorDurationScale
import com.abhinavxt.debforge.ui.youtube.AddToPlaylistRequest
import com.abhinavxt.debforge.ui.youtube.LocalAddToPlaylist
import com.abhinavxt.debforge.ui.youtube.YouTubeAddToPlaylistFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/** After the floating window: how long to wait for the full-size configuration, then a moment more for a second step. */
private const val PIP_SETTLE_MS = 1_000L
private const val PIP_SETTLE_EXTRA_MS = 300L

/** What the engine asked of the screen that needs the user's attention (dialogs over the app). */
private sealed interface HostDialog {
    data object Network : HostDialog
    data class StreamFailed(val youtube: YtStream) : HostDialog
    data class Problem(val message: String) : HostDialog
    data class External(val message: String, val offerSoftware: Boolean) : HostDialog
}

/** The picture's size while shown in each mode, as the surface's own mode. */
private fun VideoMode.surfaceMode() = when (this) {
    VideoMode.WATCH -> SurfaceMode.INLINE
    VideoMode.FULL -> SurfaceMode.FULL
    VideoMode.MINI -> SurfaceMode.MINI
}

/** The picture's rectangle in each mode, in pixels of the host: pure so the layout rules can be tested. */
internal fun videoRect(mode: VideoMode, width: Float, height: Float, top: Float, isTablet: Boolean, miniSlot: Rect): Rect = when (mode) {
    VideoMode.FULL -> Rect(0f, 0f, width, height)
    VideoMode.MINI -> if (miniSlot.width > 0f) miniSlot else Rect(8f, height - 64f, 8f + 114f, height)
    VideoMode.WATCH ->
        if (isTablet) {
            // Two panes: the video above the details on the left.
            val pane = width / 2f
            Rect(0f, top, pane, top + pane * 9f / 16f)
        } else {
            // 16:9 across the top; sideways (not yet full screen) it must not fill the screen.
            Rect(0f, top, width, top + minOf(width * 9f / 16f, height * 0.55f))
        }
}

/**
 * The in-app video: the watch page, full screen, and the picture sitting on the mini bar's slot. One
 * [VideoSurface] (one PlayerView) is placed by an animated rectangle and never moves between parents, so
 * the picture is only resized: no new surface, no black frame, while the user switches tabs under it.
 *
 * Put it last in the app shell's root so it covers the shell; in the mini mode it covers nothing but the
 * picture. [miniSlot] is where [VideoMiniBar] left room for the video, in the root's coordinates: read only
 * here, so the app shell does not recompose while the bar moves.
 *
 * Each video gets its own [VideoSurface] (`key(token)`): the surface's controls, gesture overlay and actions are
 * bound to the engine's per-video state ([VideoSession.controls], `gestureUi`, `actions`), which opening another
 * video replaces, so a surface kept across videos would show the old video's controls and send its buttons to
 * a discarded engine. The picture between two videos is black either way: PlayerView closes its shutter for a
 * new player until the new stream's first frame.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun InAppVideoHost(
    viewModel: VideoHostViewModel,
    watchViewModel: WatchViewModel,
    miniSlot: () -> Rect,
    isTablet: Boolean,
    /** A channel name tapped on the watch page: the app opens the channel (the video minimises first). */
    onChannel: (name: String, url: String) -> Unit,
    /** The mini bar's swipe: a swipe that starts on the small picture (over the bar's slot) moves the bar too. */
    miniSwipe: MiniBarSwipe = remember { MiniBarSwipe() },
    /** The app is in the floating window: only the picture, filling it. */
    pip: Boolean = rememberInPip(),
) {
    val launcher = viewModel.launcher
    val active by launcher.active.collectAsStateWithLifecycle()
    val mode by launcher.mode.collectAsStateWithLifecycle()
    val shown = active ?: return
    val shownMode = mode ?: return
    key(shown.token) { ActiveVideoHost(viewModel, watchViewModel, shown, shownMode, miniSlot, isTablet, onChannel, pip, miniSwipe) }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun ActiveVideoHost(
    vm: VideoHostViewModel,
    watchVm: WatchViewModel,
    active: ActiveVideo,
    mode: VideoMode,
    miniSlot: () -> Rect,
    isTablet: Boolean,
    onChannel: (String, String) -> Unit,
    pip: Boolean,
    miniSwipe: MiniBarSwipe,
) {
    val context = LocalContext.current
    val activity = remember { context.findComponentActivity() } ?: return
    val launcher = vm.launcher
    val session = launcher.session
    val scope = rememberCoroutineScope()
    val modeNow by rememberUpdatedState(mode)
    val details by watchVm.details.collectAsStateWithLifecycle()
    val revision by launcher.revision.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<HostDialog?>(null) }
    val spaceWarning by vm.spaceWarning.collectAsStateWithLifecycle()
    // More → Rotate in full screen: counted here, turned into an orientation lock below.
    var rotateScreenRequests by remember { mutableIntStateOf(0) }

    // --- the surface and what it asks of this screen ---
    val holder = remember { arrayOfNulls<VideoSurface>(1) }
    val actions = remember {
        InAppVideoActions(
            activity, session, session.actions, vm.caster, { holder[0] },
            onBack = { launcher.dispatch(VideoModeEvent.Back) },
            onRotate = {
                launcher.dispatch(if (modeNow == VideoMode.FULL) VideoModeEvent.ExitFull else VideoModeEvent.EnterFull)
            },
            onRotateScreen = { if (modeNow == VideoMode.FULL) rotateScreenRequests++ },
            // Showing the controls keeps the system bars hidden: they only come back on an edge swipe.
            onBars = { if (modeNow == VideoMode.FULL) FullScreen.bars(activity, false) },
            onClose = launcher::close,
        )
    }
    val surface = remember {
        VideoSurface(activity, session, vm.settings, { activity.window }, false, actions, mode.surfaceMode()).also { holder[0] = it }
    }
    SideEffect {
        surface.mode = (if (pip) VideoMode.FULL else mode).surfaceMode()
        if (pip) surface.setPictureInPicture(true) else if (surface.inPictureInPicture) {
            surface.setPictureInPicture(false)
            surface.playerView.useController = session.isTv // the phone's own controls, not Media3's
        }
        // A swipe down from the middle leaves full screen like Back does (closing when the setting opened it there).
        surface.onSwipeDownFromCenter = { if (modeNow == VideoMode.FULL) launcher.dispatch(VideoModeEvent.Back) }
        session.controls.inline = mode == VideoMode.WATCH
        session.controls.exitFullScreen = mode == VideoMode.FULL
    }
    LaunchedEffect(Unit) { surface.gesturesEnabled = vm.settings.playerGesturesFlow.first() }

    val engineHost = remember {
        object : VideoHost {
            override val inPictureInPicture = false
            override val isClosing get() = launcher.active.value?.token != active.token
            override fun close() = launcher.engineClosed()
            override fun titleChanged(title: String) { surface.titleChanged(title); launcher.changed() }
            // The tiny picture has no room for buttons and cards (skipping itself still runs in the engine).
            override fun showStartOver(show: Boolean) { if (!show || modeNow != VideoMode.MINI) surface.showStartOver(show) }
            override fun showSkip(text: String, onSkip: () -> Unit) { if (modeNow != VideoMode.MINI) surface.showSkip(text, onSkip) }
            override fun hideSkip() = surface.hideSkip()
            override fun showNextCard(text: String) { if (modeNow != VideoMode.MINI) surface.showNextCard(text) }
            override fun setNextCountdown(text: String?) = surface.setNextCountdown(text)
            override fun hideNextCard() = surface.hideNextCard()
            override fun showStats(on: Boolean) = surface.showStats(on)
            override fun statsText(text: String) = surface.statsText(text)
            override fun updatePip() {}
            override fun videoShape(portrait: Boolean) = launcher.changed()
            override fun showNetworkError() = ask(HostDialog.Network)
            override fun dismissNetworkError() { if (dialog == HostDialog.Network) dialog = null }
            override fun showStreamFailed(youtube: YtStream) = ask(HostDialog.StreamFailed(youtube))
            override fun showYouTubeProblem(message: String) = ask(HostDialog.Problem(message))
            override fun offerExternal(message: String, offerSoftware: Boolean) = ask(HostDialog.External(message, offerSoftware))
            private fun ask(d: HostDialog) {
                // The user must see what failed: the mini bar opens first.
                launcher.dispatch(VideoModeEvent.Expand)
                dialog = d
            }
        }
    }

    // --- lifecycle: the engine builds and releases its player with the app's visibility ---
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(active.token) {
        val controls = session.controls
        session.attach(surface.playerView, engineHost)
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> if (session.hostStarted(active.token)) surface.playerView.onResume()
                Lifecycle.Event.ON_PAUSE ->
                    session.hostPaused(active.token, mayLeave = !activity.isFinishing && !activity.isChangingConfigurations)
                Lifecycle.Event.ON_RESUME -> if (session.isCurrent(active.token)) session.hostResumed(active.token)
                Lifecycle.Event.ON_STOP -> {
                    // Leaving the app for good (Back out of it) ends the video, like closing the old player screen.
                    if (activity.isFinishing) launcher.close() else {
                        session.hostStopped(active.token, finishing = false)
                        surface.playerView.onPause()
                    }
                }
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            // The watch page's and full screen's own control layouts end with this host.
            controls.inline = false
            controls.exitFullScreen = false
            session.detach(surface.playerView)
            surface.release()
        }
    }
    // The YouTube details (related videos, channel, quality rows) for the watch page.
    LaunchedEffect(active.token) { vm.loadDetails(active) { video, loading -> watchVm.show(video, loading) } }
    LaunchedEffect(active.video) { if (active.video != null && watchVm.details.value.video?.id != active.video.id) watchVm.show(active.video, false) }

    // --- full screen: immersive bars, landscape for a landscape picture ---
    DisposableEffect(mode == VideoMode.FULL) {
        val full = mode == VideoMode.FULL
        if (full) FullScreen.bars(activity, false)
        // The screen turning in full screen (it is already black and full) cross-fades instead of the default spin.
        val window = activity.window
        val before = window.attributes.rotationAnimation
        if (full) window.attributes = window.attributes.apply { rotationAnimation = android.view.WindowManager.LayoutParams.ROTATION_ANIMATION_CROSSFADE }
        onDispose {
            if (full) {
                FullScreen.bars(activity, true)
                window.attributes = window.attributes.apply { rotationAnimation = before }
            }
        }
    }
    // --- orientation (section 4): the plan from the settings, the sensor and the lock the button / Back asked for ---
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val landscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val smallestWidth = configuration.smallestScreenWidthDp
    var autoRotate by remember { mutableStateOf(FullScreen.autoRotate(context)) }
    DisposableEffect(Unit) {
        val observer = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { autoRotate = FullScreen.autoRotate(context) }
        }
        context.contentResolver.registerContentObserver(android.provider.Settings.System.getUriFor(android.provider.Settings.System.ACCELEROMETER_ROTATION), false, observer)
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }
    var videoPortrait by remember { mutableStateOf(false) }
    var sizeKnown by remember { mutableStateOf(false) }
    var lock by remember { mutableStateOf(OrientationLock.NONE) }
    // Picture-in-picture: the small window is no rotated phone. The rules wait while in it and until the first
    // configuration after it (the full-size window), so expanding returns to the mode it had before.
    var settlingAfterPip by remember { mutableStateOf(false) }
    var wasPip by remember { mutableStateOf(pip) }
    if (wasPip != pip) {
        if (!pip) settlingAfterPip = true
        wasPip = pip
    }
    val configurationNow by rememberUpdatedState(configuration)
    LaunchedEffect(settlingAfterPip) {
        if (!settlingAfterPip) return@LaunchedEffect
        val left = configurationNow
        withTimeoutOrNull(PIP_SETTLE_MS) { snapshotFlow { configurationNow }.first { it !== left } }
        delay(PIP_SETTLE_EXTRA_MS) // the window can report its size and its orientation in two steps
        settlingAfterPip = false
    }
    val rulesApply = orientationRulesApply(pip, settlingAfterPip)
    // null: this video has just opened. Opened straight in full screen (the setting), it takes the lock the button would.
    var lockedFrom by remember { mutableStateOf<VideoMode?>(null) }
    if (lockedFrom != mode && rulesApply) {
        // Decided as the mode changes (before the plan below reads it), whichever way full screen was entered or left.
        val from = lockedFrom
        if (from == null) lock = lockOnOpen(mode, videoPortrait, landscape)
        else if (mode == VideoMode.FULL) lock = lockOnEnterFull(videoPortrait, landscape)
        else if (from == VideoMode.FULL) lock = lockOnExitFull(smallestWidth, landscape)
        lockedFrom = mode
    }
    var lastRequested by remember { mutableIntStateOf(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) }
    val plan = if (rulesApply) orientationPlan(mode, smallestWidth, autoRotate, videoPortrait, landscape, lock, sizeKnown)
    else OrientationPlan(mode, lastRequested) // in (or just out of) the floating window: nothing changes
    LaunchedEffect(plan.mode, mode) { if (plan.mode != mode) launcher.dispatch(VideoModeEvent.Rotated(landscape)) }
    LaunchedEffect(plan.requested) {
        lastRequested = plan.requested
        activity.requestedOrientation = plan.requested
    }
    DisposableEffect(Unit) { onDispose { activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED } }
    // A portrait picture found out late: no landscape hold after all.
    LaunchedEffect(videoPortrait) { if (videoPortrait && lock == OrientationLock.LANDSCAPE) lock = OrientationLock.NONE }
    // Auto-rotate switched off in full screen that the sensor turned sideways: stay sideways, as the button would.
    LaunchedEffect(autoRotate) { if (!autoRotate) lock = lockOnAutoRotateOff(lock, modeNow, videoPortrait) }
    val landscapeNow by rememberUpdatedState(landscape)
    LaunchedEffect(rotateScreenRequests) {
        if (rotateScreenRequests > 0 && modeNow == VideoMode.FULL) lock = lockOnRotateScreen(landscapeNow)
    }
    val lockNow by rememberUpdatedState(lock)
    val autoRotateNow by rememberUpdatedState(autoRotate)
    // The sensor is read only while a lock waits on it and the app is on screen (not in the background or the window).
    val started = lifecycle.currentStateAsState().value.isAtLeast(Lifecycle.State.STARTED)
    DisposableEffect(lock != OrientationLock.NONE && started && rulesApply) {
        val listener = object : android.view.OrientationEventListener(context) {
            override fun onOrientationChanged(degrees: Int) { lock = lockAfterSensor(lockNow, holdOf(degrees), autoRotateNow) }
        }
        if (lock != OrientationLock.NONE && started && rulesApply && listener.canDetectOrientation()) listener.enable()
        onDispose { listener.disable() }
    }
    // With auto-rotate off the portrait hold simply ends after a moment.
    LaunchedEffect(lock, autoRotate) { if (lock == OrientationLock.PORTRAIT && !autoRotate) { delay(2_000); lock = OrientationLock.NONE } }
    // The watch page opened from a search: the keyboard must not stay up over it.
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    LaunchedEffect(mode != VideoMode.MINI) {
        if (mode != VideoMode.MINI) {
            focusManager.clearFocus(force = true)
            keyboard?.hide()
        }
    }
    // Over the black strip behind the video the status bar icons must be light, whatever the app's theme.
    DisposableEffect(mode != VideoMode.MINI) {
        val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        val before = controller.isAppearanceLightStatusBars
        if (mode != VideoMode.MINI) controller.isAppearanceLightStatusBars = false
        onDispose { controller.isAppearanceLightStatusBars = before }
    }

    // Registered afresh each time the video expands (and on each expanded mode), so it is the newest callback and
    // wins over a BackHandler a screen under the video registered while it was minimised.
    if (backBelongsToVideo(mode)) key(mode) { BackHandler { launcher.dispatch(VideoModeEvent.Back) } }

    // --- what the watch page shows ---
    var canPlayAsAudio by remember { mutableStateOf(false) }
    var pictureHeight by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            canPlayAsAudio = session.canPlayAsAudio()
            pictureHeight = session.player?.videoSize?.height ?: 0
            val size = session.player?.videoSize
            sizeKnown = size != null && size.width > 0
            videoPortrait = size != null && size.width > 0 && size.height > size.width
            delay(1_000)
        }
    }
    val state = remember(active, revision, canPlayAsAudio, pictureHeight) {
        videoUiState(vm, session, active, canPlayAsAudio, pictureHeight)
    }
    val request = active.request
    val ask by vm.ask.collectAsStateWithLifecycle()
    val pageDetails = remember(details, state.kind, session.controls.castAvailable, ask) {
        if (state.kind == VideoKind.YOUTUBE) details.copy(ask = ask) else details.copy(
            canDownload = state.kind == VideoKind.DEBRID && request.item != null && request.item?.provider != com.abhinavxt.debforge.domain.ProviderId.LOCAL,
            canCast = session.controls.castAvailable,
            canOpenExternal = session.youtube == null,
        )
    }
    val detailsNow by rememberUpdatedState(pageDetails)
    val stateNow by rememberUpdatedState(state)
    val watchActions = remember {
        WatchActions(
            onChannel = { name, url -> launcher.dispatch(VideoModeEvent.Minimise); onChannel(name, url) },
            onToggleFollow = { watchVm.toggleFollow { Toast.makeText(context, R.string.yt_follow_failed, Toast.LENGTH_LONG).show() } },
            // The page keeps the playing video's details under the loading state, so a failure can go back to them.
            onOpenRelated = { r -> vm.playRelated(r, loading = { watchVm.setLoading(true) }, failed = { watchVm.setLoading(false) }) },
            onAddRelatedToQueue = vm::addToQueue,
            onAddToQueue = { detailsNow.video?.let(vm::addToQueue) },
            onDownload = {
                val d = detailsNow
                val video = d.video
                val formats = d.formats
                if (stateNow.kind == VideoKind.YOUTUBE) {
                    if (video != null && formats != null) vm.downloadYouTube(video, formats.selected, formats.subtitle)
                } else request.item?.let(vm::downloadFile)
            },
            onSelectFormat = watchVm::select,
            onSelectMode = watchVm::selectMode,
            onSelectSubtitle = watchVm::selectSubtitle,
            onAskSelect = vm::askSelect,
            onAskMode = vm::askMode,
            onAskPlay = vm::askPlay,
            onAskDismiss = vm::askDismiss,
            onPlayAsAudio = session::handOffToAudio,
            onSubtitles = { session.controls.panel = Panel.SUBTITLES; session.controls.visible = true },
            onShare = { share(context, stateNow, request.uri, detailsNow) },
            onCast = actions::cast,
            onOpenExternal = actions::openExternally,
            onPlayEpisode = { session.playEpisode(it.itemId) },
        )
    }

    // Add to playlist (YouTube): the same dialog the YouTube tab uses.
    var addRequest by remember { mutableStateOf<AddToPlaylistRequest?>(null) }
    var addLists by remember { mutableStateOf<List<Playlist>>(emptyList()) }
    val openAdd = remember<(AddToPlaylistRequest) -> Unit> { { req -> scope.launch { addLists = vm.playlists(); addRequest = req } } }
    addRequest?.let { req ->
        YouTubeAddToPlaylistFlow(
            req, addLists,
            onAdd = { id, m -> vm.addToPlaylist(req, id, m); addRequest = null },
            onCreate = { name, m -> vm.addToNewPlaylist(req, name, m); addRequest = null },
            onDismiss = { addRequest = null },
        )
    }

    HostDialogs(dialog, { dialog = null }, vm, session, actions, launcher::close)
    spaceWarning?.let { w -> com.abhinavxt.debforge.ui.browse.SpaceWarningDialog(w, vm::confirmSpaceWarning, vm::dismissSpaceWarning) }

    // --- layout ---
    val density = LocalDensity.current
    val fast = remember { animatorDurationScale(context) == 0f }
    var origin by remember { mutableStateOf(Offset.Zero) }
    BoxWithConstraints(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInRoot() }) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        val top = WindowInsets.statusBars.getTop(density).toFloat()
        val slot = miniSlot().translate(-origin)
        val target = videoRect(if (pip) VideoMode.FULL else mode, width, height, top, isTablet, slot)
        val watchRect = videoRect(VideoMode.WATCH, width, height, top, isTablet, slot)
        val miniRect = videoRect(VideoMode.MINI, width, height, top, isTablet, slot)
        val fullRect = videoRect(VideoMode.FULL, width, height, top, isTablet, slot)
        // The picture's rectangle: animated between modes, following the finger while the watch page is dragged down.
        val rectAnim = remember { androidx.compose.animation.core.Animatable(target, Rect.VectorConverter) }
        val pageAlpha = remember { androidx.compose.animation.core.Animatable(1f) }
        var dragging by remember { mutableStateOf(false) }
        var dragOffset by remember { mutableFloatStateOf(0f) }
        val travel = (miniRect.top - watchRect.top).coerceAtLeast(1f)
        // While the watch page is dragged, and while the picture settles after it, the picture stays laid out as the watch
        // page and only its View transform changes (no measuring each frame); then the layout catches up in one snapshot.
        var heldLayout by remember { mutableStateOf<Rect?>(null) }
        var settleGen by remember { mutableIntStateOf(0) }
        // A swipe up to full screen: [upActive] while the picture grows (finger or settle), [upProgress] 0..1, and the black
        // scrim stays [scrimHold] a moment after the mode changes, until full screen's own black is in.
        var upActive by remember { mutableStateOf(false) }
        var upProgress by remember { mutableFloatStateOf(0f) }
        var scrimHold by remember { mutableStateOf(false) }
        val upTravel = enterFullTravel(watchRect, fullRect)
        val upLatest = rememberUpdatedState(Triple(watchRect, fullRect, upTravel))
        val targetNow by rememberUpdatedState(target)
        var lastMode by remember { mutableStateOf(if (pip) VideoMode.FULL else mode) }
        LaunchedEffect(target, mode, pip) {
            val shown = if (pip) VideoMode.FULL else mode
            val modeChanged = lastMode != shown
            lastMode = shown
            if (dragging || heldLayout != null) return@LaunchedEffect
            // A size change in the same mode (rotation, a slot moving under a swipe) jumps instead of sliding.
            if (shouldSnapRect(modeChanged, rectAnim.isRunning, fast)) rectAnim.snapTo(target)
            else rectAnim.animateTo(target, tween(280))
        }
        LaunchedEffect(mode) { if (mode == VideoMode.WATCH && !dragging) pageAlpha.snapTo(1f) }
        val dragLatest = rememberUpdatedState(Triple(watchRect, miniRect, travel))
        // A rotation or resize during a drag or settle: the held layout follows the new watch layout.
        LaunchedEffect(watchRect) { if (heldLayout != null) heldLayout = watchRect }
        // Where the picture is laid out, and where it looks to be (animated, or under the finger).
        val rectNow: () -> Rect = { heldLayout ?: rectAnim.value }
        val visualRect: () -> Rect = {
            if (dragging) {
                val (watch, mini, trav) = dragLatest.value
                androidx.compose.ui.geometry.lerp(watch, mini, (dragOffset / trav).coerceIn(0f, 1f))
            } else if (upActive) {
                val (watch, full, _) = upLatest.value
                enterFullRect(watch, full, upProgress)
            } else rectAnim.value
        }
        val settleJobs = remember { arrayOfNulls<kotlinx.coroutines.Job>(1) }
        val drag = remember {
            object : VideoSurface.HostDrag {
                fun begin() {
                    if (modeNow == VideoMode.WATCH && !dragging && !upActive) {
                        // Caught while springing back: carry on from where the picture is.
                        val resumeFrom = if (heldLayout != null) (rectAnim.value.top - dragLatest.value.first.top).coerceAtLeast(0f) else 0f
                        settleGen++
                        settleJobs[0]?.cancel()
                        heldLayout = dragLatest.value.first
                        dragOffset = resumeFrom
                        dragging = true
                        session.controls.visible = false
                        surface.playerView.hideController()
                    }
                }
                fun by(dy: Float) { if (dragging) dragOffset = (dragOffset + dy).coerceAtLeast(0f) }
                override fun start() = begin()
                override fun move(dy: Float) = by(dy)
                override fun upStart() {
                    if (modeNow != VideoMode.WATCH || dragging || upActive) return
                    settleGen++
                    settleJobs[0]?.cancel()
                    heldLayout = upLatest.value.first
                    upProgress = 0f
                    upActive = true
                    session.controls.visible = false
                    surface.playerView.hideController()
                }
                override fun upMove(up: Float) {
                    if (upActive && up.isFinite()) upProgress = (upProgress + up / upLatest.value.third).coerceIn(0f, 1f)
                }
                override fun upEnd(velocityY: Float, cancelled: Boolean) {
                    if (!upActive) return
                    val travel = upLatest.value.third
                    val commit = !cancelled && shouldEnterFull(upProgress, velocityY)
                    val gen = ++settleGen
                    settleJobs[0] = scope.launch {
                        try {
                            val spring = androidx.compose.animation.core.spring<Float>(dampingRatio = 1f, stiffness = 400f)
                            val end = if (commit) 1f else 0f
                            if (fast) upProgress = end
                            else androidx.compose.animation.core.animate(
                                upProgress, end, enterFullSettleVelocity(velocityY, travel, commit), spring,
                            ) { v, _ -> upProgress = v }
                            if (commit) {
                                // Full and black first; only then the mode changes, and with it the request to turn the screen.
                                scrimHold = true
                                pageAlpha.snapTo(0f)
                                launcher.dispatch(VideoModeEvent.EnterFull)
                                withTimeoutOrNull(1_000) { snapshotFlow { modeNow }.first { it == VideoMode.FULL } }
                                scope.launch { delay(250); scrimHold = false }
                            }
                        } finally {
                            if (gen == settleGen) {
                                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { rectAnim.snapTo(targetNow) }
                                heldLayout = null
                                upActive = false
                                upProgress = 0f
                            }
                        }
                    }
                }
                override fun end(velocityY: Float) {
                    if (!dragging) return
                    val (watch, mini, trav) = dragLatest.value
                    val minimise = shouldMinimise(dragOffset, velocityY, trav)
                    val p = (dragOffset / trav).coerceIn(0f, 1f)
                    val gen = ++settleGen
                    settleJobs[0] = scope.launch {
                        try {
                            rectAnim.snapTo(androidx.compose.ui.geometry.lerp(watch, mini, p))
                            pageAlpha.snapTo(1f - p)
                            dragging = false
                            val speed = settleVelocity(velocityY, minimise)
                            if (minimise) {
                                launcher.dispatch(VideoModeEvent.Minimise)
                                launch { pageAlpha.animateTo(0f, tween(160)) }
                            } else launch { pageAlpha.animateTo(1f, tween(150)) }
                            if (!fast) {
                                val spring = androidx.compose.animation.core.spring<Rect>(dampingRatio = 1f, stiffness = 400f)
                                if (!minimise) {
                                    rectAnim.animateTo(watch, spring, initialVelocity = Rect(0f, speed, 0f, speed))
                                } else kotlinx.coroutines.coroutineScope {
                                    // The mini bar reports its slot a few frames after the mode changes: the picture keeps heading
                                    // for wherever the slot turns out to be, without stopping or jumping.
                                    var dest = mini
                                    var anim = launch { rectAnim.animateTo(dest, spring, initialVelocity = Rect(0f, speed, 0f, speed)) }
                                    val watcher = launch {
                                        snapshotFlow { targetNow }.collect { t ->
                                            if (t != dest) { dest = t; anim.cancel(); anim = launch { rectAnim.animateTo(t, spring) } }
                                        }
                                    }
                                    while (true) {
                                        anim.join()
                                        repeat(3) { withFrameNanos { } }
                                        if (anim.isCompleted && dest == targetNow) break
                                    }
                                    watcher.cancel()
                                }
                            }
                        } finally {
                            // The layout catches up and the transform resets together, in one snapshot.
                            if (gen == settleGen) {
                                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { rectAnim.snapTo(if (minimise) dragLatest.value.second else targetNow) }
                                heldLayout = null
                            }
                        }
                    }
                }
            }
        }
        SideEffect { surface.hostDrag = drag }
        // The page's own list: pulling down at its top drags the video down too.
        val pageScroll = remember {
            object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): Offset {
                    if (source != androidx.compose.ui.input.nestedscroll.NestedScrollSource.UserInput || !dragging || available.y >= 0f) return Offset.Zero
                    val used = -minOf(dragOffset, -available.y)
                    drag.move(used)
                    return Offset(0f, used)
                }
                override fun onPostScroll(consumed: Offset, available: Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): Offset {
                    if (source != androidx.compose.ui.input.nestedscroll.NestedScrollSource.UserInput || available.y <= 0f || modeNow != VideoMode.WATCH) return Offset.Zero
                    drag.start(); drag.move(available.y)
                    return Offset(0f, available.y)
                }
                override suspend fun onPreFling(available: androidx.compose.ui.unit.Velocity): androidx.compose.ui.unit.Velocity {
                    if (!dragging) return androidx.compose.ui.unit.Velocity.Zero
                    drag.end(available.y)
                    return available
                }
            }
        }

        // The watch page: everything but the picture.
        AnimatedVisibility(visible = mode == VideoMode.WATCH && !pip, enter = fadeIn(tween(220)), exit = fadeOut(tween(160))) {
            Surface(
                Modifier.fillMaxSize()
                    .graphicsLayer {
                        alpha = when {
                            dragging -> 1f - (dragOffset / travel).coerceIn(0f, 1f)
                            upActive -> enterFullPageAlpha(upProgress)
                            else -> pageAlpha.value
                        }
                    }
                    .nestedScroll(pageScroll),
                color = MaterialTheme.colorScheme.background,
            ) {
                val videoBottom = with(density) { watchRect.bottom.toDp() }
                if (isTablet) {
                    Box(Modifier.fillMaxSize()) {
                        Box(Modifier.width(with(density) { watchRect.width.toDp() }).height(videoBottom).background(Color.Black))
                        CompositionLocalProvider(LocalAddToPlaylist provides openAdd.takeIf { state.kind == VideoKind.YOUTUBE }) {
                            WatchPage(
                                state, pageDetails, watchActions, wide = true,
                                headerTopPadding = videoBottom, listTopPadding = with(density) { top.toDp() },
                            )
                        }
                    }
                } else {
                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxWidth().height(videoBottom).background(Color.Black))
                        CompositionLocalProvider(LocalAddToPlaylist provides openAdd.takeIf { state.kind == VideoKind.YOUTUBE }) {
                            WatchPage(state, pageDetails, watchActions, wide = false, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
        AnimatedVisibility(visible = mode == VideoMode.FULL || pip, enter = fadeIn(tween(160)), exit = fadeOut(tween(160))) {
            Box(Modifier.fillMaxSize().background(Color.Black))
        }
        // The black that comes in as the picture grows under a swipe up.
        if (upActive || scrimHold) {
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = if (upActive) enterFullScrimAlpha(upProgress) else 1f }.background(Color.Black))
        }

        // The mini bar's swipe moves the picture with a View transform on the surface itself, set from the same state in the
        // same frame as the bar's layer (a SurfaceView follows View transforms, not a Compose layer's, and cannot fade).
        // Once a still of the picture is drawn in the bar's slot, the live picture steps off-screen, so what the user sees
        // moves, fades and is covered by the tabs exactly like the bar; back at rest the live picture returns, then the still goes.
        val miniPicture = remember { MiniPicture() }
        val miniNow = mode == VideoMode.MINI && !pip
        val layoutNow by rememberUpdatedState(rectNow)
        val visualNow by rememberUpdatedState(visualRect)
        // The picture's View transform: the layout-to-visual difference, plus the mini bar's swipe offset.
        fun applyMini() {
            val m = miniPicture.active
            val layer = layerFor(layoutNow(), visualNow())
            surface.pivotX = 0f
            surface.pivotY = 0f
            surface.scaleX = layer.scaleX
            surface.scaleY = layer.scaleY
            surface.translationX = layer.translationX + when { !m -> 0f; miniPicture.hidden -> MINI_PICTURE_AWAY; else -> miniSwipe.dragX }
            surface.translationY = layer.translationY + if (m && !miniPicture.hidden) miniSwipe.dragY else 0f
        }
        // Set in the frame the state changes, not through a re-measure.
        LaunchedEffect(Unit) { snapshotFlow { layerFor(layoutNow(), visualNow()) }.collect { applyMini() } }
        miniPicture.active = miniNow
        // Entering picture-in-picture mid-drag: drop the swipe so nothing is left held or offset when the app returns.
        LaunchedEffect(pip) {
            if (!pip) return@LaunchedEffect
            miniSwipe.reset()
            // ...and a watch-page drag too: back to the watch layout with no transform left over.
            if (dragging || heldLayout != null || upActive) {
                settleGen++
                settleJobs[0]?.cancel()
                dragging = false
                upActive = false
                upProgress = 0f
                rectAnim.snapTo(targetNow)
                heldLayout = null
                pageAlpha.snapTo(1f)
            }
            applyMini()
        }
        SideEffect { miniSwipe.onMoved = { applyMini() }; applyMini() }
        LaunchedEffect(miniNow) {
            if (!miniNow) return@LaunchedEffect
            snapshotFlow { miniSwipe.held }.collectLatest { held ->
                if (held) {
                    val still = copyPicture(surface.playerView) ?: return@collectLatest
                    miniSwipe.frame = still.asImageBitmap()
                    // The bar draws the still under the live picture first; then the live one steps aside.
                    withFrameNanos { }
                    withFrameNanos { }
                    miniPicture.hidden = true
                    applyMini()
                } else if (miniPicture.hidden || miniSwipe.frame != null) {
                    miniPicture.hidden = false
                    applyMini()
                    withFrameNanos { }
                    withFrameNanos { }
                    miniSwipe.frame = null
                }
            }
        }
        DisposableEffect(miniNow) {
            onDispose { miniPicture.hidden = false; miniSwipe.frame = null; applyMini() }
        }

        // The one picture.
        AndroidView(
            factory = { surface },
            modifier = Modifier
                .offset { rectNow().let { IntOffset(it.left.roundToInt(), it.top.roundToInt()) } }
                .layout { measurable, _ ->
                    val w = rectNow().width.roundToInt().coerceAtLeast(1)
                    val h = rectNow().height.roundToInt().coerceAtLeast(1)
                    val placeable = measurable.measure(Constraints.fixed(w, h))
                    layout(w, h) { placeable.place(0, 0) }
                },
        )
        // While the picture is held (dragged, or settling after it) it is drawn away from its layout bounds, which is
        // where touches are tested: this region over where it is drawn lets it be caught there.
        if (mode == VideoMode.WATCH && !pip && heldLayout != null && !upActive) {
            val held = heldLayout!!
            Box(
                Modifier
                    .offset { IntOffset(held.left.roundToInt(), held.top.roundToInt()) }
                    .size(with(density) { held.width.toDp() }, with(density) { held.height.toDp() })
                    .graphicsLayer {
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
                        val l = layerFor(rectNow(), visualRect())
                        scaleX = l.scaleX; scaleY = l.scaleY; translationX = l.translationX; translationY = l.translationY
                    }
                    .draggable(
                        androidx.compose.foundation.gestures.rememberDraggableState { dy -> drag.move(dy) },
                        androidx.compose.foundation.gestures.Orientation.Vertical,
                        onDragStarted = { drag.start() },
                        onDragStopped = { v -> drag.end(v) },
                    )
            )
        }
        // A tap on the small picture opens the watch page (its own touch handling is off in the mini mode), and a
        // swipe that starts on it is the bar's swipe. Placed where the picture is without the swipe's offset, so the
        // touch does not move under the finger.
        if (mode == VideoMode.MINI && !pip && heldLayout == null) {
            Box(
                Modifier
                    .offset { rectNow().let { IntOffset(it.left.roundToInt(), it.top.roundToInt()) } }
                    .layout { measurable, _ ->
                        val w = rectNow().width.roundToInt().coerceAtLeast(1)
                        val h = rectNow().height.roundToInt().coerceAtLeast(1)
                        val placeable = measurable.measure(Constraints.fixed(w, h))
                        layout(w, h) { placeable.place(0, 0) }
                    }
                    .miniBarSwipe(miniSwipe)
                    .pointerInput(Unit) { detectTapGestures { launcher.dispatch(VideoModeEvent.Expand) } }
            )
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun HostDialogs(
    dialog: HostDialog?,
    dismiss: () -> Unit,
    vm: VideoHostViewModel,
    session: VideoSession,
    actions: InAppVideoActions,
    close: () -> Unit,
) {
    when (dialog) {
        null -> {}
        HostDialog.Network -> AlertDialog(
            onDismissRequest = { dismiss(); session.networkDialogGone(); close() },
            title = { Text(stringResource(R.string.player_network_title)) },
            text = { Text(stringResource(R.string.player_network_body)) },
            confirmButton = { TextButton(onClick = { dismiss(); session.networkDialogGone(); session.retryAfterNetworkError() }) { Text(stringResource(R.string.action_retry)) } },
            dismissButton = { TextButton(onClick = { dismiss(); session.networkDialogGone(); close() }) { Text(stringResource(R.string.action_close)) } },
        )
        is HostDialog.StreamFailed -> AlertDialog(
            onDismissRequest = { dismiss(); close() },
            text = { Text(stringResource(R.string.yt_stream_failed)) },
            confirmButton = {
                TextButton(onClick = {
                    dismiss()
                    vm.downloadFailedStream(dialog.youtube, session.title, session.uri, session.subtitles, close)
                }) { Text(stringResource(R.string.yt_stream_download)) }
            },
            dismissButton = { TextButton(onClick = { dismiss(); close() }) { Text(stringResource(R.string.action_close)) } },
        )
        is HostDialog.Problem -> AlertDialog(
            onDismissRequest = { dismiss(); close() },
            text = { Text(dialog.message) },
            confirmButton = { TextButton(onClick = { dismiss(); close() }) { Text(stringResource(R.string.action_close)) } },
        )
        is HostDialog.External -> AlertDialog(
            onDismissRequest = { dismiss(); session.player?.play() },
            title = { Text(stringResource(R.string.player_unsupported_title)) },
            text = { Text(dialog.message) },
            confirmButton = { TextButton(onClick = { dismiss(); actions.openExternally() }) { Text(stringResource(R.string.action_open_other_player)) } },
            dismissButton = {
                Column {
                    if (dialog.offerSoftware) TextButton(onClick = { dismiss(); session.trySoftwareDecoding() }) { Text(stringResource(R.string.player_try_software)) }
                    TextButton(onClick = { dismiss(); session.player?.play() }) { Text(stringResource(R.string.player_keep_watching)) }
                }
            },
        )
    }
}

/** What the watch page needs to know about the video the engine is playing. */
private fun videoUiState(vm: VideoHostViewModel, session: VideoSession, active: ActiveVideo, canPlayAsAudio: Boolean, pictureHeight: Int): VideoUiState {
    val request = active.request
    val kind = kindOf(request.youtube != null, request.uri.scheme)
    val item = request.item
    val fileName = session.identity?.filename?.takeIf { it.isNotBlank() }
    return VideoUiState(
        kind = kind,
        title = session.title.ifBlank { request.title },
        providerName = if (kind == VideoKind.DEBRID) item?.provider?.let(vm::providerName) else null,
        fileName = if (kind == VideoKind.YOUTUBE) null else fileName,
        sizeBytes = item?.filesize ?: 0L,
        height = pictureHeight,
        container = fileName?.substringAfterLast('.', "")?.takeIf { it.length in 1..5 },
        canPlayAsAudio = canPlayAsAudio,
        episodes = session.upcomingEpisodes.map { EpisodeRow(it.item.id, it.title) },
    )
}

/** Share: a YouTube link, or the downloaded file. */
private fun share(context: Context, state: VideoUiState, uri: android.net.Uri, details: WatchDetails) {
    val send = Intent(Intent.ACTION_SEND)
    when (state.kind) {
        VideoKind.YOUTUBE -> {
            val id = details.video?.id ?: return
            send.setType("text/plain").putExtra(Intent.EXTRA_TEXT, "${state.title}\nhttps://youtu.be/$id")
        }
        VideoKind.DOWNLOAD -> send.setType("video/*").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        VideoKind.DEBRID -> return
    }
    runCatching { context.startActivity(Intent.createChooser(send, null)) }
}

private tailrec fun Context.findComponentActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.findComponentActivity()
    else -> null
}

/** Full screen's system side: immersive bars and the auto-rotate setting, kept apart from the composition. */
internal object FullScreen {
    fun bars(activity: Activity, visible: Boolean) {
        val controller = WindowInsetsControllerCompat(activity.window, activity.window.decorView)
        if (visible) controller.show(WindowInsetsCompat.Type.systemBars()) else {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    /** The system's auto-rotate switch. */
    fun autoRotate(context: Context): Boolean =
        android.provider.Settings.System.getInt(context.contentResolver, android.provider.Settings.System.ACCELEROMETER_ROTATION, 1) == 1
}

/** Where the live mini picture goes while a still of it stands in on the swiped bar: far off-screen (the surface is kept, not destroyed). */
private const val MINI_PICTURE_AWAY = -100_000f

private class MiniPicture {
    var active = false
    var hidden = false
}

/** A still of what the player's SurfaceView shows, or null when it has none (no surface yet, a copy failure). */
@androidx.annotation.OptIn(UnstableApi::class)
private suspend fun copyPicture(view: androidx.media3.ui.PlayerView): android.graphics.Bitmap? {
    val sv = view.videoSurfaceView as? android.view.SurfaceView ?: return null
    if (sv.width <= 0 || sv.height <= 0 || !sv.holder.surface.isValid) return null
    val bitmap = android.graphics.Bitmap.createBitmap(sv.width, sv.height, android.graphics.Bitmap.Config.ARGB_8888)
    return kotlinx.coroutines.suspendCancellableCoroutine { c ->
        try {
            android.view.PixelCopy.request(sv, bitmap, { result ->
                if (c.isActive) c.resumeWith(Result.success(if (result == android.view.PixelCopy.SUCCESS) bitmap else null))
            }, android.os.Handler(android.os.Looper.getMainLooper()))
        } catch (e: IllegalArgumentException) {
            c.resumeWith(Result.success(null))
        }
    }
}
