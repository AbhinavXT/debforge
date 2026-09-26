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
import com.abhinavxt.debforge.download.DownloadScheduler
import com.abhinavxt.debforge.ui.AppViewModel
import com.abhinavxt.debforge.ui.DebForgeRoot
import com.abhinavxt.debforge.ui.add.PendingAddStore
import com.abhinavxt.debforge.ui.theme.DebforgeTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var pendingAdds: PendingAddStore
    @Inject lateinit var downloadScheduler: DownloadScheduler

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Only on a fresh launch — after rotation the same intent would be
        // re-delivered and re-open the Add dialog.
        if (savedInstanceState == null) pendingAdds.offerFromIntent(intent)
        setContent {
            val appVm: AppViewModel = hiltViewModel()
            val mode by appVm.themeMode.collectAsStateWithLifecycle()
            val dark = when (mode) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }
            DebforgeTheme(darkTheme = dark) {
                DebForgeRoot()
            }
        }
    }

    /** singleTop: a magnet tapped while DebForge is already open lands here. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingAdds.offerFromIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        // The app is visible, so this is the moment Android allows starting a
        // user-initiated job / foreground service. Resumes anything still
        // QUEUED — e.g. after Android's background time limit paused it, or
        // after the process was killed.
        lifecycleScope.launch { downloadScheduler.ensureRunning() }
    }
}
