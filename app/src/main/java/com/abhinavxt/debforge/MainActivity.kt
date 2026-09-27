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
import com.abhinavxt.debforge.domain.ThemeMode
import com.abhinavxt.debforge.download.DownloadController
import com.abhinavxt.debforge.download.DownloadScheduler
import com.abhinavxt.debforge.ui.AppViewModel
import com.abhinavxt.debforge.ui.CrashReportPrompt
import com.abhinavxt.debforge.ui.DebForgeRoot
import com.abhinavxt.debforge.ui.add.PendingAdd
import com.abhinavxt.debforge.ui.add.PendingAddStore
import com.abhinavxt.debforge.ui.theme.DebforgeTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var pendingAdds: PendingAddStore
    @Inject lateinit var downloadScheduler: DownloadScheduler
    @Inject lateinit var downloadController: DownloadController
    @Inject lateinit var housekeeper: com.abhinavxt.debforge.download.Housekeeper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Only on a fresh launch — after rotation the same intent would be
        // re-delivered and re-open the Add dialog.
        if (savedInstanceState == null) {
            pendingAdds.offerFromIntent(intent)
            handleAppAction(intent)
        }
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

    /** singleTop: a magnet tapped while DebForge is already open lands here. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingAdds.offerFromIntent(intent)
        handleAppAction(intent)
    }

    /** Widget, Quick Settings tile and launcher shortcuts; see [AppActions]. */
    private fun handleAppAction(intent: Intent?) {
        when (intent?.action) {
            // Empty text: just opens the Add dialog (after sign-in if needed).
            AppActions.ADD -> pendingAdds.offer(PendingAdd.Text(""))
            // The app is coming to the front, so the engine may start now.
            AppActions.RESUME_ALL -> lifecycleScope.launch { downloadController.resumeAll() }
        }
    }

    override fun onStart() {
        super.onStart()
        // The app is visible, so this is the moment Android allows starting a
        // user-initiated job / foreground service. Resumes anything still
        // QUEUED — e.g. after Android's background time limit paused it, or
        // after the process was killed.
        lifecycleScope.launch { downloadScheduler.ensureRunning() }
        // "Delete downloads after N days", if the user turned it on.
        lifecycleScope.launch { runCatching { housekeeper.run() } }
    }
}
