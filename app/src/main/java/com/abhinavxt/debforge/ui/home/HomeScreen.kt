package com.abhinavxt.debforge.ui.home

import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
import com.abhinavxt.debforge.R
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PermMedia
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.outlined.PermMedia
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.Alignment
import androidx.compose.material3.NavigationRail
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.material3.NavigationRailItem
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.activity.compose.BackHandler
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.focusGroup
import androidx.compose.runtime.Composable
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import com.abhinavxt.debforge.ui.player.focusRing
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.abhinavxt.debforge.ui.active.ActiveDownloadsScreen
import com.abhinavxt.debforge.ui.add.AddDialog
import com.abhinavxt.debforge.ui.add.AddViewModel
import com.abhinavxt.debforge.ui.browse.BrowseScreen
import com.abhinavxt.debforge.ui.browser.BrowserScreen
import com.abhinavxt.debforge.ui.search.SearchScreen
import com.abhinavxt.debforge.ui.settings.SettingsScreen
import com.abhinavxt.debforge.ui.setup.SetupScreen
import com.abhinavxt.debforge.ui.media.MediaScreen
import com.abhinavxt.debforge.ui.player.MiniPlayer
import com.abhinavxt.debforge.player.VideoStart
import com.abhinavxt.debforge.player.VideoStartExtras
import com.abhinavxt.debforge.ui.player.NowPlaying
import com.abhinavxt.debforge.ui.youtube.YouTubeScreen
import com.abhinavxt.debforge.ui.youtube.YouTubeViewModel
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import com.abhinavxt.debforge.ui.history.HistoryScreen
import com.abhinavxt.debforge.ui.player.ArtworkShare
import com.abhinavxt.debforge.ui.player.animatorDurationScale
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.background
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.IconButton
import androidx.compose.runtime.DisposableEffect
import com.abhinavxt.debforge.ui.video.InAppVideoHost
import com.abhinavxt.debforge.ui.video.VideoHostViewModel
import com.abhinavxt.debforge.ui.video.VideoMiniBar
import com.abhinavxt.debforge.ui.video.VideoMode
import com.abhinavxt.debforge.ui.video.VideoModeEvent
import com.abhinavxt.debforge.ui.video.WatchViewModel
import com.abhinavxt.debforge.ui.video.rememberVideoPulse

private enum class HomeTab(
    val route: String,
    @StringRes val labelRes: Int,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
    /** In the phone's bottom bar; the rest are reached from header icons (the rail lists all). */
    val inBar: Boolean = true
) {
    Browse("browse", R.string.tab_library, Icons.Outlined.VideoLibrary, Icons.Filled.VideoLibrary),
    Search("search", R.string.tab_search, Icons.Outlined.Search, Icons.Filled.Search),
    YouTube("youtube", R.string.tab_youtube, Icons.Outlined.PlayCircle, Icons.Filled.PlayCircle),
    Browser("browser", R.string.tab_browser, Icons.Outlined.Public, Icons.Filled.Public),
    Media("media", R.string.tab_media, Icons.Outlined.PermMedia, Icons.Filled.PermMedia),
    Active("active", R.string.tab_downloads, Icons.Outlined.Download, Icons.Filled.Download, inBar = false),
    Settings("settings", R.string.tab_settings, Icons.Outlined.Settings, Icons.Filled.Settings, inBar = false)
}

/** Play history: pushed from the Media tab header, so it is a route but not a tab. */
private const val HISTORY_ROUTE = "history"

/** Screens reached from a header icon: Back returns to where they were opened from. */
private fun isPushedRoute(route: String?) =
    route == HomeTab.Active.route || route == HomeTab.Settings.route || route == HISTORY_ROUTE

/**
 * The app shell. Bottom-nav switches between Browse / Search / Active / Browser /
 * Settings; nav state is saved across tab switches so the user keeps their
 * scroll position and any in-flight loads. Without a signed-in service
 * ([signedIn] false) the Library lists this device's downloads, shows Setup
 * only when the user asks to sign in, and the app opens on Browser.
 *
 * The notification permission request runs once on first composition. On
 * pre-API-33 it's no-op (the permission is install-time); on 33+ a denial just
 * means the foreground download notification won't be visible — the engine
 * still runs.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalSharedTransitionApi::class)
@Composable
fun HomeScreen(signedIn: Boolean) {
    NotificationPermissionRequest()

    // Scoped to Home so it outlives tab switches; it also receives magnets /
    // links / .torrent files handed over by other apps.
    val addViewModel: AddViewModel = hiltViewModel()
    AddDialog(addViewModel)

    val shell: ShellViewModel = hiltViewModel()
    val youtubeViewModel: YouTubeViewModel = hiltViewModel()
    val audio by shell.audio.state.collectAsStateWithLifecycle()
    val artColor by shell.playerArtColor.collectAsStateWithLifecycle()
    val skipSeconds by shell.audioSkipSeconds.collectAsStateWithLifecycle()
    val activeCount by shell.activeCount.collectAsStateWithLifecycle()
    val watchBusy by shell.watchBusy.collectAsStateWithLifecycle()
    val homeContext = androidx.compose.ui.platform.LocalContext.current
    // The in-app video (watch page, full screen, mini bar); a TV opens its own player screen instead.
    val videoVm: VideoHostViewModel = hiltViewModel()
    val watchVm: WatchViewModel = hiltViewModel()
    val videoLauncher = videoVm.launcher
    val videoActive by videoLauncher.active.collectAsStateWithLifecycle()
    val videoMode by videoLauncher.mode.collectAsStateWithLifecycle()
    val videoRevision by videoLauncher.revision.collectAsStateWithLifecycle()
    val videoShown = videoActive != null
    val videoExpanded = videoMode == VideoMode.WATCH || videoMode == VideoMode.FULL
    // The floating window's small size says nothing about the device: it keeps what it was before.
    val inPip = com.abhinavxt.debforge.ui.video.rememberInPip()
    val tabletBeforePip = remember { booleanArrayOf(false) }
    val isTablet = if (inPip) tabletBeforePip[0] else (LocalConfiguration.current.smallestScreenWidthDp >= 600).also { tabletBeforePip[0] = it }
    LaunchedEffect(isTablet) { videoLauncher.isTablet = isTablet }
    // Written while the mini bar moves (every frame of its slide): kept as a State and read only by the video host.
    val videoSlot = remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    // The mini bar's sideways swipe, run by the bar and by the picture sitting on its slot.
    val videoMiniSwipe = remember { com.abhinavxt.debforge.ui.video.MiniBarSwipe() }
    var videoBarTitle by remember { mutableStateOf("") }
    LaunchedEffect(videoActive, videoRevision) { if (videoShown) videoBarTitle = videoLauncher.session.title }
    val msgFileMissing = stringResource(R.string.dl_file_missing)
    val msgStreamFailed = stringResource(R.string.media_stream_failed)
    var nowPlaying by rememberSaveable { mutableStateOf(false) }
    val expandRequested by NowPlayingRequest.expand.collectAsStateWithLifecycle()
    LaunchedEffect(expandRequested) {
        if (expandRequested) { nowPlaying = true; NowPlayingRequest.expand.value = false }
    }
    // "Watch video": open the video where the audio is, and minimise the full player.
    LaunchedEffect(Unit) {
        shell.watchEvents.collect { e ->
            when (e) {
                is WatchEvent.Launch -> {
                    nowPlaying = false
                    videoLauncher.open(homeContext, e.request, start = VideoStart(e.startMs, e.speed))
                }
                WatchEvent.FileMissing -> android.widget.Toast.makeText(homeContext, msgFileMissing, android.widget.Toast.LENGTH_LONG).show()
                WatchEvent.StreamFailed -> android.widget.Toast.makeText(homeContext, msgStreamFailed, android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }
    // "Play as audio" could not start and its video is already closed: say so and offer the way back.
    var handoffFailure by remember { mutableStateOf<com.abhinavxt.debforge.player.HandoffFailure?>(null) }
    LaunchedEffect(Unit) { shell.audio.handoffFailed.collect { handoffFailure = it } }
    handoffFailure?.let { failure ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { handoffFailure = null },
            title = { Text(stringResource(R.string.audio_play_failed)) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { handoffFailure = null; shell.watchVideo(failure) }) {
                    Text(stringResource(R.string.audio_watch_video))
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { handoffFailure = null }) { Text(stringResource(R.string.action_close)) }
            }
        )
    }
    // A video opens: the full audio player gives way. Audio starting while a video is open closes the video
    // (opening a video parks the audio, so audio playing again means the user asked for it).
    LaunchedEffect(videoShown) { if (videoShown) nowPlaying = false }
    val videoShownNow by rememberUpdatedState(videoShown)
    LaunchedEffect(audio.isPlaying) { if (audio.isPlaying && videoShownNow) videoLauncher.close() }
    // The queue ended or was cleared: nothing left to show.
    LaunchedEffect(audio.track == null) { if (audio.track == null) nowPlaying = false }

    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val current = backStackEntry?.destination
    // Pushed Downloads/Settings keep the tab they were opened from highlighted in the bar.
    val barRoute = if (isPushedRoute(current?.route))
        backStackEntry?.let { navController.previousBackStackEntry?.destination?.route } ?: current?.route
    else current?.route

    fun go(tab: HomeTab) {
        // Drop a pushed Downloads/Settings first so it isn't saved with its tab and restored on top of it later.
        val route = navController.currentDestination?.route
        if (isPushedRoute(route) && route != tab.route) navController.popBackStack()
        // Already on the back stack (selected, or a screen was pushed on top of it): pop back to its root.
        if (navController.popBackStack(tab.route, inclusive = false)) return
        navController.navigate(tab.route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    // Downloads and Settings are pushed (no tab state to keep), so Back returns to where the user was.
    // The tab a pushed screen was opened from, so Back always returns there.
    var pushedFrom by remember { mutableStateOf<String?>(null) }
    fun pushRoute(route: String) {
        val from = navController.currentDestination?.route
        // Settings opened from Downloads goes back to Downloads, which already remembers its own origin.
        if (!isPushedRoute(from)) pushedFrom = from
        navController.navigate(route) { launchSingleTop = true }
    }
    fun push(tab: HomeTab) = pushRoute(tab.route)
    fun backFromPushed() {
        val origin = pushedFrom
        navController.popBackStack()
        val now = navController.currentDestination?.route
        val isPushed = isPushedRoute(now)
        if (origin != null && !isPushed && now != origin) {
            HomeTab.entries.firstOrNull { it.route == origin }?.let { go(it) }
        }
    }

    BackHandler(enabled = isPushedRoute(current?.route)) { backFromPushed() }

    // A channel name tapped outside the YouTube tab (the audio player): minimise the full player and show the channel page there.
    // Ignored while a playlist download runs on the YouTube tab. Back from the page returns to the tab it was opened from.
    val openChannel: (String, String) -> Unit = { name, url ->
        val origin = navController.currentDestination?.route?.takeIf { it != HomeTab.YouTube.route }
        if (youtubeViewModel.openChannel(name, url, origin)) {
            nowPlaying = false
            go(HomeTab.YouTube)
        }
    }
    LaunchedEffect(Unit) {
        youtubeViewModel.returnTo.collect { route -> HomeTab.entries.firstOrNull { it.route == route }?.let { go(it) } }
    }

    // A YouTube link shared into the app (cold start or already open).
    val pendingYouTube by shell.pending.youtube.collectAsStateWithLifecycle()
    LaunchedEffect(pendingYouTube) {
        if (pendingYouTube != null) {
            go(HomeTab.YouTube)
            shell.pending.consumeYouTube()?.let(youtubeViewModel::offer)
        }
    }

    // Library shows Setup instead of the device's downloads: the user asked to sign in.
    var connecting by rememberSaveable { mutableStateOf(false) }
    fun connect() {
        connecting = true
        go(HomeTab.Browse)
    }
    LaunchedEffect(signedIn) { if (signedIn) connecting = false }
    LaunchedEffect(current?.route) { if (current != null && current.route != HomeTab.Browse.route) connecting = false }

    // A magnet / torrent waiting for a service: show where to sign in.
    val waitingForService by addViewModel.waitingForService.collectAsStateWithLifecycle()
    // Keyed on the item, not "is anything waiting": each new one shows the way again.
    LaunchedEffect(waitingForService) { if (waitingForService != null) connect() }

    // Decided once: signing in later shouldn't yank the user off the tab they're on.
    val startTab = remember { if (signedIn) HomeTab.Browse else HomeTab.Browser }
    // Read inside the movable content below, which is built once.
    val currentSignedIn by rememberUpdatedState(signedIn)
    val currentAudio by rememberUpdatedState(audio)

    // Tablets, foldables (unfolded), Chromebooks and TVs get a side rail;
    // phones keep the bottom bar. 600dp is Material's "medium" width class.
    val wide = LocalConfiguration.current.screenWidthDp >= 600

    // Movable: turning the phone can switch between the bar and the rail, and
    // the tabs must move along with their state (the Browser tab's page and a
    // full-screen video in it) instead of being rebuilt.
    val content = remember {
        movableContentOf { modifier: Modifier ->
            NavHost(
                navController = navController,
                startDestination = startTab.route,
                modifier = modifier
            ) {
                composable(HomeTab.Browse.route) {
                    if (currentSignedIn || !connecting) {
                        BrowseScreen(
                            onAdd = { addViewModel.open() },
                            onAddText = { addViewModel.open(prefillText = it) },
                            onOpenDownloads = { push(HomeTab.Active) },
                            onOpenSettings = { push(HomeTab.Settings) },
                            activeDownloads = activeCount
                        )
                    } else {
                        BackHandler { connecting = false }
                        SetupScreen()
                    }
                }
                composable(HomeTab.Search.route) {
                    SearchScreen(
                        onAdd = { addViewModel.open(prefillText = it) },
                        onOpenSettings = { push(HomeTab.Settings) },
                        onOpenDownloads = { push(HomeTab.Active) },
                        activeDownloads = activeCount
                    )
                }
                composable(HomeTab.YouTube.route) {
                    YouTubeScreen(
                        onOpenDownloads = { push(HomeTab.Active) },
                        onOpenSettings = { push(HomeTab.Settings) },
                        activeDownloads = activeCount,
                        playingId = currentAudio.track?.id,
                        viewModel = youtubeViewModel)
                }
                composable(HomeTab.Media.route) {
                    MediaScreen(
                        playingId = currentAudio.track?.id,
                        // Files are looked up off the main thread; unplayable ones are skipped.
                        onPlayAudio = { queue, index -> shell.audio.playDownloads(queue, index) },
                        onOpenDownloads = { push(HomeTab.Active) },
                        onOpenSettings = { push(HomeTab.Settings) },
                        onOpenYouTube = { go(HomeTab.YouTube) },
                        onOpenHistory = { pushRoute(HISTORY_ROUTE) }
                    )
                }
                composable(HISTORY_ROUTE) {
                    Pushed(wide, onBack = ::backFromPushed) {
                        HistoryScreen(onOpenYouTube = { url -> youtubeViewModel.offer(url); go(HomeTab.YouTube) })
                    }
                }
                composable(HomeTab.Active.route) {
                    Pushed(wide, onBack = ::backFromPushed) {
                        ActiveDownloadsScreen(onOpenMedia = { go(HomeTab.Media) }, onOpenSettings = { push(HomeTab.Settings) })
                    }
                }
                composable(HomeTab.Browser.route) { BrowserScreen(
                    onMagnet = addViewModel::offer,
                    onOpenDownloads = { push(HomeTab.Active) },
                    onOpenSettings = { push(HomeTab.Settings) },
                    activeDownloads = activeCount
                ) }
                composable(HomeTab.Settings.route) {
                    // Settings draws its own collapsing bar with the back arrow in it on a phone.
                    SettingsScreen(onConnect = ::connect, onBack = if (wide) null else ::backFromPushed)
                }
            }
        }
    }

    // Reduced motion (animator scale 0): no shared transition, the full player just switches.
    val appContext = LocalContext.current
    var motionScale by remember { mutableStateOf(animatorDurationScale(appContext)) }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) motionScale = animatorDurationScale(appContext)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val sharedMotion = motionScale > 0f

    // D-pad: focus moves into the full player when it opens and back to the mini player when it closes.
    val playFocus = remember { FocusRequester() }
    val miniFocus = remember { FocusRequester() }
    val playerOpen = nowPlaying && audio.track != null
    var wasOpen by remember { mutableStateOf(false) }
    var miniHasFocus by remember { mutableStateOf(false) }
    // Only for D-pad/keyboard use: on touch a programmatic focus would leave a focus ring showing.
    val inputMode = androidx.compose.ui.platform.LocalInputModeManager.current
    // The watch page and full screen cover the app: nothing underneath takes focus; back at the mini bar, focus returns to it.
    val videoMiniFocus = remember { FocusRequester() }
    var videoWasExpanded by remember { mutableStateOf(false) }
    LaunchedEffect(videoExpanded) {
        val keys = inputMode.inputMode == androidx.compose.ui.input.InputMode.Keyboard
        if (videoExpanded) videoWasExpanded = true
        else if (videoWasExpanded) {
            videoWasExpanded = false
            if (keys && videoMode == VideoMode.MINI) {
                var tries = 0
                while (tries++ < 12) {
                    androidx.compose.runtime.withFrameNanos { }
                    if (runCatching { videoMiniFocus.requestFocus() }.isSuccess) break
                }
            }
        }
    }
    LaunchedEffect(playerOpen) {
        val keys = inputMode.inputMode == androidx.compose.ui.input.InputMode.Keyboard
        if (playerOpen) {
            wasOpen = true
            androidx.compose.runtime.withFrameNanos { }
            if (keys) runCatching { playFocus.requestFocus() }
        } else if (wasOpen) {
            wasOpen = false
            // Wait for the screen underneath to become focusable again.
            androidx.compose.runtime.withFrameNanos { }
            androidx.compose.runtime.withFrameNanos { }
            // The first requests can land before the screen underneath is focusable again (focus then falls to its first item),
            // so ask again each frame until the mini player has it.
            if (keys) {
                miniHasFocus = false
                var tries = 0
                while (!miniHasFocus && tries++ < 12) {
                    runCatching { miniFocus.requestFocus() }
                    androidx.compose.runtime.withFrameNanos { }
                }
            }
        }
    }

    val shellBody: @Composable (SharedTransitionScope?) -> Unit = { sharedScope ->
    // The video bar: over the tabs (beside the rail on wide screens); the picture itself is InAppVideoHost's, on its slot.
    val videoMini: @Composable () -> Unit = {
        AnimatedVisibility(
            visible = videoShown,
            enter = slideInVertically { it } + androidx.compose.animation.fadeIn(),
            exit = slideOutVertically { it } + androidx.compose.animation.fadeOut()
        ) {
            VideoMiniBar(
                title = videoBarTitle,
                pulse = rememberVideoPulse(videoLauncher.session),
                onToggle = { videoLauncher.session.player?.let { if (it.isPlaying) it.pause() else it.play() } },
                onClose = videoLauncher::close,
                onExpand = { videoLauncher.dispatch(VideoModeEvent.Expand) },
                onVideoSlot = { videoSlot.value = it },
                toggleFocus = videoMiniFocus,
                swipe = videoMiniSwipe,
            )
        }
    }
    val miniPlayer: @Composable () -> Unit = {
        AnimatedVisibility(
            visible = audio.track != null && !videoShown,
            enter = slideInVertically { it } + androidx.compose.animation.fadeIn(),
            exit = slideOutVertically { it } + androidx.compose.animation.fadeOut()
        ) {
            MiniPlayer(
                state = audio,
                onToggle = shell.audio::toggle,
                onNext = shell.audio::next,
                onExpand = { nowPlaying = true },
                onClose = shell.audio::clear,
                onWatch = { shell.watchVideo() },
                watchBusy = watchBusy,
                onChannel = openChannel,
                modifier = Modifier.onFocusChanged { if (it.hasFocus) miniHasFocus = true },
                sharedScope = if (sharedMotion) sharedScope else null,
                expanded = nowPlaying,
                expandFocus = miniFocus
            )
        }
    }

    Box(Modifier.fillMaxSize()) {
        // While the full player is open nothing underneath may take focus or key presses.
        // Only ever set canFocus to false: assigning true here would also override the non-focusable focus
        // groups of lazy lists and scroll containers below, and 2D (arrow key) search would skip their items.
        Box(Modifier.fillMaxSize().focusProperties { if (playerOpen || videoExpanded) canFocus = false }) {
        if (wide) {
            // The rail layout has no Scaffold, so it needs its own Surface: it paints the page and sets the
            // content colour (without it Media showed the window's light default, then black text on black).
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Row(Modifier.fillMaxSize()) {
                NavigationRail(
                    modifier = Modifier.fillMaxHeight(),
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                ) {
                    // Scrolls so every item stays reachable at short heights (phone landscape).
                    Column(
                        Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                    HomeTab.entries.sortedBy { it == HomeTab.Settings }.forEach { tab ->
                        val selected = current?.hierarchy?.any { it.route == tab.route } == true ||
                            (tab == HomeTab.Media && current?.route == HISTORY_ROUTE)
                        val railSource = remember { MutableInteractionSource() }
                        NavigationRailItem(
                            selected = selected,
                            onClick = { go(tab) },
                            interactionSource = railSource,
                            modifier = Modifier.focusRing(railSource, RoundedCornerShape(16.dp)),
                            icon = { Icon(if (selected) tab.selectedIcon else tab.icon, contentDescription = null) },
                            label = { Text(stringResource(tab.labelRes), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        )
                    }
                    }
                }
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    content(Modifier.weight(1f).fillMaxWidth())
                    Box(Modifier.windowInsetsPadding(WindowInsets.navigationBars)) { Column { videoMini(); miniPlayer() } }
                }
            }
            }
        } else {
            Scaffold(
                bottomBar = {
                    Column {
                        videoMini()
                        miniPlayer()
                        NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                            HomeTab.entries.filter { it.inBar }.forEach { tab ->
                                val selected = barRoute == tab.route
                                val barSource = remember { MutableInteractionSource() }
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = { go(tab) },
                                    interactionSource = barSource,
                                    modifier = Modifier.focusRing(barSource, RoundedCornerShape(16.dp)),
                                    icon = { Icon(if (selected) tab.selectedIcon else tab.icon, contentDescription = null) },
                                    label = { Text(stringResource(tab.labelRes), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                )
                            }
                        }
                    }
                }
            ) { padding ->
                // Consume what the bar took so inner screens don't pad for it twice.
                // With the bottom bar only DOWN reaches it: LEFT/RIGHT from an edge item must not jump to a tab.
                @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
                content(
                    Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize()
                        .focusProperties {
                            exit = { dir ->
                                if (dir == androidx.compose.ui.focus.FocusDirection.Left || dir == androidx.compose.ui.focus.FocusDirection.Right)
                                    FocusRequester.Cancel else FocusRequester.Default
                            }
                        }
                        .focusGroup()
                )
            }
        }
        }

        // Without a track there is nothing to draw, so leave at once instead of fading out an empty sheet.
        if (audio.track != null) AnimatedVisibility(
            visible = nowPlaying,
            enter = if (sharedMotion) fadeIn(tween(350)) else EnterTransition.None,
            exit = if (sharedMotion) fadeOut(tween(350)) else ExitTransition.None
        ) {
            BackHandler(enabled = nowPlaying) { nowPlaying = false }
            // While fading out the controls are inert.
            val leaving = transition.targetState == androidx.compose.animation.EnterExitState.PostExit
            run {
                NowPlaying(
                    modifier = Modifier.pointerInput(leaving) {
                        if (leaving) awaitPointerEventScope {
                            while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                        }
                    },
                    padSystemBars = true,
                    open = nowPlaying,
                    artColor = artColor,
                    skipSeconds = skipSeconds,
                    state = audio,
                    onToggle = shell.audio::toggle,
                    onNext = shell.audio::next,
                    onPrevious = shell.audio::previous,
                    onSeek = shell.audio::seekTo,
                    onSkip = shell.audio::seekBy,
                    onShuffle = shell.audio::setShuffle,
                    onRepeat = shell.audio::cycleRepeat,
                    onSpeed = shell.audio::setSpeed,
                    onSleep = shell.audio::setSleepTimer,
                    onSkipTo = shell.audio::skipTo,
                    onAutoplay = shell.audio::setAutoplay,
                    onClose = { nowPlaying = false },
                    onStop = { nowPlaying = false; shell.audio.clear() },
                    onWatch = { shell.watchVideo() },
                    watchBusy = watchBusy,
                    lyrics = shell::lyrics,
                    onChannel = openChannel,
                    playFocus = playFocus,
                    share = if (sharedMotion && sharedScope != null) ArtworkShare(sharedScope, this@AnimatedVisibility) else null
                )
            }
        }
        InAppVideoHost(
            viewModel = videoVm,
            watchViewModel = watchVm,
            miniSlot = { videoSlot.value },
            isTablet = isTablet,
            onChannel = openChannel,
            miniSwipe = videoMiniSwipe,
        )
    }
    }
    if (sharedMotion) SharedTransitionLayout(Modifier.fillMaxSize()) { shellBody(this) } else shellBody(null)
}

/** Downloads and Settings on a phone: reached from a header icon, so they get a back arrow. */
@Composable
private fun Pushed(wide: Boolean, onBack: () -> Unit, content: @Composable () -> Unit) {
    if (wide) { content(); return }
    Column(Modifier.fillMaxSize()) {
        IconButton(
            onClick = onBack,
            modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars).padding(start = 4.dp)
        ) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.shell_cd_back)) }
        // The status bar is already cleared by the arrow row.
        Box(Modifier.weight(1f).consumeWindowInsets(WindowInsets.statusBars)) { content() }
    }
}

@Composable
private fun NotificationPermissionRequest() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* result ignored; engine works without it */ }

    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
