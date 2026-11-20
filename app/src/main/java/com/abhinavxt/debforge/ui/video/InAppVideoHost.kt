package com.abhinavxt.debforge.ui.video

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateRectAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
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
import kotlin.math.roundToInt

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
 * picture. [miniSlot] is where [VideoMiniBar] left room for the video, in this host's coordinates.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun InAppVideoHost(
    viewModel: VideoHostViewModel,
    watchViewModel: WatchViewModel,
    miniSlot: Rect,
    isTablet: Boolean,
    /** A channel name tapped on the watch page: the app opens the channel (the video minimises first). */
    onChannel: (name: String, url: String) -> Unit,
) {
    val launcher = viewModel.launcher
    val active by launcher.active.collectAsStateWithLifecycle()
    val mode by launcher.mode.collectAsStateWithLifecycle()
    val shown = active ?: return
    val shownMode = mode ?: return
    key(shown.token) { ActiveVideoHost(viewModel, watchViewModel, shown, shownMode, miniSlot, isTablet, onChannel) }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun ActiveVideoHost(
    vm: VideoHostViewModel,
    watchVm: WatchViewModel,
    active: ActiveVideo,
    mode: VideoMode,
    miniSlot: Rect,
    isTablet: Boolean,
    onChannel: (String, String) -> Unit,
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

    // --- the surface and what it asks of this screen ---
    val holder = remember { arrayOfNulls<VideoSurface>(1) }
    val actions = remember {
        InAppVideoActions(
            activity, session, session.actions, vm.caster, { holder[0] },
            onBack = { launcher.dispatch(VideoModeEvent.Back) },
            onRotate = {
                if (modeNow == VideoMode.FULL) FullScreen.turn(activity) else launcher.dispatch(VideoModeEvent.EnterFull)
            },
            onBars = { visible -> if (modeNow == VideoMode.FULL) FullScreen.bars(activity, visible) },
            onClose = launcher::close,
        )
    }
    val surface = remember {
        VideoSurface(activity, session, vm.settings, { activity.window }, false, actions, mode.surfaceMode()).also { holder[0] = it }
    }
    SideEffect {
        surface.mode = mode.surfaceMode()
        session.controls.inline = mode == VideoMode.WATCH
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
        if (full) FullScreen.enter(activity, session.player?.videoSize)
        onDispose { if (full) FullScreen.leave(activity) }
    }
    // Over the black strip behind the video the status bar icons must be light, whatever the app's theme.
    DisposableEffect(mode != VideoMode.MINI) {
        val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        val before = controller.isAppearanceLightStatusBars
        if (mode != VideoMode.MINI) controller.isAppearanceLightStatusBars = false
        onDispose { controller.isAppearanceLightStatusBars = before }
    }

    BackHandler(enabled = backBelongsToVideo(mode)) { launcher.dispatch(VideoModeEvent.Back) }

    // --- what the watch page shows ---
    var canPlayAsAudio by remember { mutableStateOf(false) }
    var pictureHeight by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            canPlayAsAudio = session.canPlayAsAudio()
            pictureHeight = session.player?.videoSize?.height ?: 0
            delay(1_000)
        }
    }
    val state = remember(active, revision, canPlayAsAudio, pictureHeight) {
        videoUiState(vm, session, active, canPlayAsAudio, pictureHeight)
    }
    val request = active.request
    val pageDetails = remember(details, state.kind, session.controls.castAvailable) {
        if (state.kind == VideoKind.YOUTUBE) details else details.copy(
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
            onOpenRelated = { r -> vm.playRelated(r, loading = { watchVm.show(null, true) }, failed = { watchVm.show(detailsNow.video, false) }) },
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

    // --- layout ---
    val density = LocalDensity.current
    val fast = remember { animatorDurationScale(context) == 0f }
    var origin by remember { mutableStateOf(Offset.Zero) }
    BoxWithConstraints(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInRoot() }) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        val top = WindowInsets.statusBars.getTop(density).toFloat()
        val target = videoRect(mode, width, height, top, isTablet, miniSlot.translate(-origin))
        val rect by animateRectAsState(target, animationSpec = tween(if (fast) 0 else 280), label = "video")
        val watchRect = videoRect(VideoMode.WATCH, width, height, top, isTablet, miniSlot)

        // The watch page: everything but the picture.
        AnimatedVisibility(visible = mode == VideoMode.WATCH, enter = fadeIn(tween(220)), exit = fadeOut(tween(160))) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
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
        AnimatedVisibility(visible = mode == VideoMode.FULL, enter = fadeIn(tween(160)), exit = fadeOut(tween(160))) {
            Box(Modifier.fillMaxSize().background(Color.Black))
        }

        // The one picture.
        AndroidView(
            factory = { surface },
            modifier = Modifier
                .offset { IntOffset(rect.left.roundToInt(), rect.top.roundToInt()) }
                .layout { measurable, _ ->
                    val w = rect.width.roundToInt().coerceAtLeast(1)
                    val h = rect.height.roundToInt().coerceAtLeast(1)
                    val placeable = measurable.measure(Constraints.fixed(w, h))
                    layout(w, h) { placeable.place(0, 0) }
                },
        )
        // A tap on the small picture opens the watch page (its own touch handling is off in the mini mode).
        if (mode == VideoMode.MINI) {
            Box(
                Modifier
                    .offset { IntOffset(rect.left.roundToInt(), rect.top.roundToInt()) }
                    .layout { measurable, _ ->
                        val w = rect.width.roundToInt().coerceAtLeast(1)
                        val h = rect.height.roundToInt().coerceAtLeast(1)
                        val placeable = measurable.measure(Constraints.fixed(w, h))
                        layout(w, h) { placeable.place(0, 0) }
                    }
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

/** Full screen's system side: immersive bars and orientation, kept apart from the composition. */
internal object FullScreen {
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var release: Runnable? = null

    fun enter(activity: Activity, size: androidx.media3.common.VideoSize?) {
        release?.let(handler::removeCallbacks)
        bars(activity, false)
        val portraitPicture = size != null && size.width > 0 && size.height > size.width
        if (!portraitPicture) activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    /** Bars back; the turn back to portrait is held for a moment so the phone held sideways does not bounce straight into full screen again. */
    fun leave(activity: Activity) {
        bars(activity, true)
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        val r = Runnable { activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
        release = r
        handler.postDelayed(r, 1_500)
    }

    fun bars(activity: Activity, visible: Boolean) {
        val controller = WindowInsetsControllerCompat(activity.window, activity.window.decorView)
        if (visible) controller.show(WindowInsetsCompat.Type.systemBars()) else {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    /** The rotate button in full screen: the other way round. */
    fun turn(activity: Activity) {
        val landscape = activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        activity.requestedOrientation =
            if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }
}
