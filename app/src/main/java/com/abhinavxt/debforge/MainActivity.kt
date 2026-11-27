package com.abhinavxt.debforge

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import com.abhinavxt.debforge.domain.ThemeMode
import com.abhinavxt.debforge.download.DownloadController
import com.abhinavxt.debforge.download.DownloadNotifications
import com.abhinavxt.debforge.download.DownloadScheduler
import com.abhinavxt.debforge.ui.AppViewModel
import com.abhinavxt.debforge.ui.CrashReportPrompt
import com.abhinavxt.debforge.ui.DebForgeRoot
import com.abhinavxt.debforge.ui.add.PendingAdd
import com.abhinavxt.debforge.ui.add.PendingAddStore
import com.abhinavxt.debforge.ui.theme.DebforgeTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var pendingAdds: PendingAddStore
    @Inject lateinit var downloadScheduler: DownloadScheduler
    @Inject lateinit var downloadController: DownloadController
    @Inject lateinit var housekeeper: com.abhinavxt.debforge.download.Housekeeper
    @Inject lateinit var localImporter: com.abhinavxt.debforge.download.LocalImporter
    @Inject lateinit var auth: com.abhinavxt.debforge.data.repository.AuthRepository
    @Inject lateinit var settingsStore: com.abhinavxt.debforge.data.prefs.SettingsStore
    @Inject lateinit var audioPlayer: com.abhinavxt.debforge.player.AudioPlayer
    @Inject lateinit var videoLauncher: com.abhinavxt.debforge.player.VideoLauncher

    private lateinit var videoPip: com.abhinavxt.debforge.ui.video.VideoPip

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        videoPip = com.abhinavxt.debforge.ui.video.VideoPip(this, videoLauncher, settingsStore).also { it.start() }
        // A new download folder (or the import switch) is scanned right away; this also drops
        // imported rows outside the new folder. The first value is covered by onStart.
        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                kotlinx.coroutines.flow.combine(settingsStore.downloadDirFlow, settingsStore.importLocalFilesFlow) { d, i -> d to i }
                    .distinctUntilChanged()
                    .drop(1)
                    .collectLatest { localImporter.scan() }
            }
        }
        // Only on a fresh launch — after rotation the same intent would be
        // re-delivered and re-open the Add dialog.
        if (savedInstanceState == null) {
            pendingAdds.offerFromIntent(intent)
            handleAppAction(intent)
            takeCloudReady(intent)
            // Reopened from recents (even after the process died), the launch intent comes back with the
            // widget's request in it: only a real tap plays.
            if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0) takePlayRestored(intent)
        }
        takeExpandRequest(intent)
        takeOpenVideo(intent)
        setContent {
            val appVm: AppViewModel = hiltViewModel()
            val mode by appVm.themeMode.collectAsStateWithLifecycle()
            val dynamicColor by appVm.dynamicColor.collectAsStateWithLifecycle()
            val appTheme by appVm.appTheme.collectAsStateWithLifecycle()
            val pureBlack by appVm.pureBlack.collectAsStateWithLifecycle()
            val dark = when (mode) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }
            // Status/navigation bar icons follow the APP's light/dark choice,
            // not the system's (e.g. dark app on a light system).
            androidx.compose.runtime.DisposableEffect(dark) {
                val transparent = android.graphics.Color.TRANSPARENT
                enableEdgeToEdge(
                    statusBarStyle = androidx.activity.SystemBarStyle.auto(transparent, transparent) { dark },
                    navigationBarStyle = androidx.activity.SystemBarStyle.auto(transparent, transparent) { dark }
                )
                onDispose {}
            }
            DebforgeTheme(darkTheme = dark, dynamicColor = dynamicColor, appTheme = appTheme, pureBlack = pureBlack) {
                DebForgeRoot()
                CrashReportPrompt()
            }
        }
    }

    // Every activity the app starts goes through these two (startActivity and the result launchers included):
    // Android 11's leave hint must not take that for the user leaving.
    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun startActivityForResult(intent: Intent, requestCode: Int, options: android.os.Bundle?) {
        if (::videoPip.isInitialized) videoPip.ownActivityStarting()
        // Nothing started (no app for it): nothing will resume us, so the flag must not linger.
        try { super.startActivityForResult(intent, requestCode, options) } catch (e: RuntimeException) { if (::videoPip.isInitialized) videoPip.resumed(); throw e }
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun startIntentSenderForResult(
        intent: android.content.IntentSender, requestCode: Int, fillInIntent: Intent?, flagsMask: Int, flagsValues: Int,
        extraFlags: Int, options: android.os.Bundle?,
    ) {
        if (::videoPip.isInitialized) videoPip.ownActivityStarting()
        try {
            super.startIntentSenderForResult(intent, requestCode, fillInIntent, flagsMask, flagsValues, extraFlags, options)
        } catch (e: Exception) {
            if (::videoPip.isInitialized) videoPip.resumed()
            throw e
        }
    }

    override fun onResume() {
        super.onResume()
        videoPip.resumed()
    }

    /** Android 11 and older: leaving while a video plays enters the floating window (12+ enters by itself). */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        videoPip.onUserLeaveHint()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        videoPip.modeChanged(isInPictureInPictureMode)
    }

    override fun onDestroy() {
        videoPip.stop()
        // Leaving for good (Back out of the app, swiped from recents): the video ends with it.
        if (isFinishing) videoLauncher.close()
        super.onDestroy()
    }

    /** singleTop: a magnet tapped while DebForge is already open lands here. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Kept as the activity's intent: without the one-shot play request, so nothing later replays it.
        setIntent(Intent(intent).apply {
            removeExtra(com.abhinavxt.debforge.widget.PlayerWidget.EXTRA_PLAY_RESTORED)
            // Taken below; a copy that kept it would expand the mini bar again when the activity is recreated.
            removeExtra(com.abhinavxt.debforge.player.VideoLauncher.EXTRA_OPEN_VIDEO)
            removeExtra(DownloadNotifications.EXTRA_JOB_REF)
        })
        pendingAdds.offerFromIntent(intent)
        handleAppAction(intent)
        takeCloudReady(intent)
        takeExpandRequest(intent)
        takeOpenVideo(intent)
        takePlayRestored(intent)
    }

    /** "Finished on the service" notification: switch to that service and show its Library (not the exact title: the job's ref is not a title key). */
    private fun takeCloudReady(intent: Intent?) {
        val provider = intent?.getStringExtra(DownloadNotifications.EXTRA_PROVIDER)
        val ref = intent?.getStringExtra(DownloadNotifications.EXTRA_JOB_REF)
        if (provider == null && ref == null) return
        intent.removeExtra(DownloadNotifications.EXTRA_PROVIDER)
        intent.removeExtra(DownloadNotifications.EXTRA_JOB_REF)
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        lifecycleScope.launch {
            val target = com.abhinavxt.debforge.download.watch.cloudReadyTarget(provider, ref, auth.signedInProviders.first()) ?: return@launch
            auth.setActiveProvider(target)
            com.abhinavxt.debforge.ui.home.NowPlayingRequest.openLibrary.value = true
        }
    }

    /** The video's notification: show the watch page of the video playing (nothing when it has ended). */
    private fun takeOpenVideo(intent: Intent?) {
        if (intent?.getBooleanExtra(com.abhinavxt.debforge.player.VideoLauncher.EXTRA_OPEN_VIDEO, false) != true) return
        intent.removeExtra(com.abhinavxt.debforge.player.VideoLauncher.EXTRA_OPEN_VIDEO)
        videoLauncher.dispatch(com.abhinavxt.debforge.ui.video.VideoModeEvent.Expand)
    }

    /** The player widget's Play button when it cannot start playback itself: the app is in front now, so it may. */
    private fun takePlayRestored(intent: Intent?) {
        if (intent?.getBooleanExtra(com.abhinavxt.debforge.widget.PlayerWidget.EXTRA_PLAY_RESTORED, false) != true) return
        intent.removeExtra(com.abhinavxt.debforge.widget.PlayerWidget.EXTRA_PLAY_RESTORED)
        lifecycleScope.launch {
            audioPlayer.restore()
            if (audioPlayer.isActive && !audioPlayer.state.value.isPlaying) audioPlayer.toggle()
        }
    }

    /** The audio notification asks for "now playing"; clear it so rotation does not reopen it. */
    private fun takeExpandRequest(intent: Intent?) {
        if (intent?.getBooleanExtra(com.abhinavxt.debforge.player.BackgroundPlayback.EXTRA_EXPAND_NOW_PLAYING, false) == true) {
            intent.removeExtra(com.abhinavxt.debforge.player.BackgroundPlayback.EXTRA_EXPAND_NOW_PLAYING)
            com.abhinavxt.debforge.ui.home.NowPlayingRequest.expand.value = true
        }
    }

    /** Widget, Quick Settings tile and launcher shortcuts; see [AppActions]. */
    private fun handleAppAction(intent: Intent?) {
        when (intent?.action) {
            // Empty text: just opens the Add dialog (after sign-in if needed).
            AppActions.ADD -> pendingAdds.offer(PendingAdd.Text(""))
            // The app is coming to the front, so the engine may start now.
            AppActions.RESUME_ALL -> lifecycleScope.launch { downloadController.resumeAll() }
            AppActions.RESUME_IDS -> {
                val ids = intent.getStringArrayExtra(AppActions.EXTRA_IDS)?.toList().orEmpty()
                lifecycleScope.launch { downloadController.resumeIds(ids) }
            }
            AppActions.FREE_SPACE -> if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0) {
                com.abhinavxt.debforge.ui.settings.SettingsTarget.section.value = com.abhinavxt.debforge.ui.settings.SettingsTarget.DOWNLOADS
                com.abhinavxt.debforge.ui.home.NowPlayingRequest.openSettings.value = true
            }
            AppActions.SEARCH -> if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0) com.abhinavxt.debforge.ui.home.NowPlayingRequest.openSearch.value = true
            // A launcher tap, so the app is in front and may start playback: resume the saved queue and show it.
            AppActions.CONTINUE_LISTENING -> if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0) lifecycleScope.launch {
                audioPlayer.restore()
                if (audioPlayer.isActive) {
                    if (!audioPlayer.state.value.isPlaying) audioPlayer.toggle()
                    com.abhinavxt.debforge.ui.home.NowPlayingRequest.expand.value = true
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // The user is back in the app: the "tap to resume" notification is done.
        DownloadNotifications.cancelStoppedBySystem(this)
        // The app is visible, so this is the moment Android allows starting a
        // user-initiated job / foreground service. Resumes anything still
        // QUEUED — e.g. after Android's background time limit paused it, or
        // after the process was killed.
        lifecycleScope.launch { downloadScheduler.ensureRunning(foreground = true) }
        // "Delete downloads after N days", if the user turned it on.
        lifecycleScope.launch { runCatching { housekeeper.run() } }
        // Audio and video already in the download folder show up in Media.
        lifecycleScope.launch { localImporter.scanIfStale() }
    }
}
