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
import com.abhinavxt.debforge.ui.media.displayTitle
import com.abhinavxt.debforge.ui.player.MiniPlayer
import com.abhinavxt.debforge.ui.player.NowPlaying
import com.abhinavxt.debforge.ui.youtube.YouTubeScreen
import com.abhinavxt.debforge.ui.youtube.YouTubeViewModel
import com.abhinavxt.debforge.player.AudioTrack
import com.abhinavxt.debforge.download.LocalFiles
import com.abhinavxt.debforge.download.artworkFile
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
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
    val activeCount by shell.activeCount.collectAsStateWithLifecycle()
    var nowPlaying by rememberSaveable { mutableStateOf(false) }
    val expandRequested by NowPlayingRequest.expand.collectAsStateWithLifecycle()
    LaunchedEffect(expandRequested) {
        if (expandRequested) { nowPlaying = true; NowPlayingRequest.expand.value = false }
    }
    // The queue ended or was cleared: nothing left to show.
    LaunchedEffect(audio.track == null) { if (audio.track == null) nowPlaying = false }

    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val current = backStackEntry?.destination
    // Pushed Downloads/Settings keep the tab they were opened from highlighted in the bar.
    val barRoute = if (current?.route == HomeTab.Active.route || current?.route == HomeTab.Settings.route)
        backStackEntry?.let { navController.previousBackStackEntry?.destination?.route } ?: current?.route
    else current?.route

    fun go(tab: HomeTab) {
        // Drop a pushed Downloads/Settings first so it isn't saved with its tab and restored on top of it later.
        val route = navController.currentDestination?.route
        if ((route == HomeTab.Active.route || route == HomeTab.Settings.route) && route != tab.route) navController.popBackStack()
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
    fun push(tab: HomeTab) {
        val from = navController.currentDestination?.route
        // Settings opened from Downloads goes back to Downloads, which already remembers its own origin.
        if (from != HomeTab.Active.route && from != HomeTab.Settings.route) pushedFrom = from
        navController.navigate(tab.route) { launchSingleTop = true }
    }
    fun backFromPushed() {
        val origin = pushedFrom
        navController.popBackStack()
        val now = navController.currentDestination?.route
        val isPushed = now == HomeTab.Active.route || now == HomeTab.Settings.route
        if (origin != null && !isPushed && now != origin) {
            HomeTab.entries.firstOrNull { it.route == origin }?.let { go(it) }
        }
    }

    BackHandler(enabled = current?.route == HomeTab.Active.route || current?.route == HomeTab.Settings.route) { backFromPushed() }

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
                        viewModel = youtubeViewModel)
                }
                composable(HomeTab.Media.route) {
                    val context = LocalContext.current
                    MediaScreen(
                        playingId = currentAudio.track?.id,
                        onPlayAudio = { queue, index ->
                            val tracks = queue.mapNotNull { e ->
                                val uri = e.finalFilePath?.let { LocalFiles.uriFor(context, it) } ?: return@mapNotNull null
                                AudioTrack(e.id, uri, e.displayTitle(), e.artist, artworkFile(context, e.id).takeIf { it.exists() })
                            }
                            // Unplayable files are skipped; find the tapped one again in what is left.
                            val start = tracks.indexOfFirst { it.id == queue.getOrNull(index)?.id }.coerceAtLeast(0)
                            if (tracks.isNotEmpty()) shell.audio.play(tracks, start)
                        },
                        onOpenDownloads = { push(HomeTab.Active) },
                        onOpenSettings = { push(HomeTab.Settings) },
                        onOpenYouTube = { go(HomeTab.YouTube) }
                    )
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
    val miniPlayer: @Composable () -> Unit = {
        AnimatedVisibility(
            visible = audio.track != null,
            enter = slideInVertically { it } + androidx.compose.animation.fadeIn(),
            exit = slideOutVertically { it } + androidx.compose.animation.fadeOut()
        ) {
            MiniPlayer(
                state = audio,
                onToggle = shell.audio::toggle,
                onNext = shell.audio::next,
                onExpand = { nowPlaying = true },
                onClose = shell.audio::clear,
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
        Box(Modifier.fillMaxSize().focusProperties { if (playerOpen) canFocus = false }) {
        if (wide) {
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
                        val selected = current?.hierarchy?.any { it.route == tab.route } == true
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
                    Box(Modifier.windowInsetsPadding(WindowInsets.navigationBars)) { miniPlayer() }
                }
            }
        } else {
            Scaffold(
                bottomBar = {
                    Column {
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
                    onClose = { nowPlaying = false },
                    onStop = { nowPlaying = false; shell.audio.clear() },
                    onChannel = openChannel,
                    playFocus = playFocus,
                    share = if (sharedMotion && sharedScope != null) ArtworkShare(sharedScope, this@AnimatedVisibility) else null
                )
            }
        }
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
