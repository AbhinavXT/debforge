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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
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
import com.abhinavxt.debforge.ui.settings.SettingsScreen

private enum class HomeTab(
    val route: String,
    @StringRes val labelRes: Int,
    val icon: ImageVector,
    val selectedIcon: ImageVector
) {
    Browse("browse", R.string.tab_library, Icons.Outlined.VideoLibrary, Icons.Filled.VideoLibrary),
    Active("active", R.string.tab_downloads, Icons.Outlined.Download, Icons.Filled.Download),
    Settings("settings", R.string.tab_settings, Icons.Outlined.Settings, Icons.Filled.Settings)
}

/**
 * The authenticated shell. Bottom-nav switches between Browse / Active /
 * Settings; nav state is saved across tab switches so the user keeps their
 * scroll position and any in-flight loads.
 *
 * The notification permission request runs once on first composition. On
 * pre-API-33 it's no-op (the permission is install-time); on 33+ a denial just
 * means the foreground download notification won't be visible — the engine
 * still runs.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen() {
    NotificationPermissionRequest()

    // Scoped to Home so it outlives tab switches; it also receives magnets /
    // links / .torrent files handed over by other apps.
    val addViewModel: AddViewModel = hiltViewModel()
    AddDialog(addViewModel)

    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val current = backStackEntry?.destination

    fun go(tab: HomeTab) {
        navController.navigate(tab.route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    // Tablets, foldables (unfolded), Chromebooks and TVs get a side rail;
    // phones keep the bottom bar. 600dp is Material's "medium" width class.
    val wide = LocalConfiguration.current.screenWidthDp >= 600

    val content: @Composable (Modifier) -> Unit = { modifier ->
        NavHost(
            navController = navController,
            startDestination = HomeTab.Browse.route,
            modifier = modifier
        ) {
            composable(HomeTab.Browse.route) {
                BrowseScreen(
                    onAdd = { addViewModel.open() },
                    onAddText = { addViewModel.open(prefillText = it) }
                )
            }
            composable(HomeTab.Active.route) { ActiveDownloadsScreen() }
            composable(HomeTab.Settings.route) { SettingsScreen() }
        }
    }

    if (wide) {
        Row(Modifier.fillMaxSize()) {
            NavigationRail(
                modifier = Modifier.fillMaxHeight(),
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            ) {
                HomeTab.entries.forEach { tab ->
                    val selected = current?.hierarchy?.any { it.route == tab.route } == true
                    NavigationRailItem(
                        selected = selected,
                        onClick = { go(tab) },
                        icon = { Icon(if (selected) tab.selectedIcon else tab.icon, contentDescription = stringResource(tab.labelRes)) },
                        label = { Text(stringResource(tab.labelRes)) }
                    )
                }
            }
            content(Modifier.weight(1f).fillMaxHeight())
        }
    } else {
        Scaffold(
            bottomBar = {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                    HomeTab.entries.forEach { tab ->
                        val selected = current?.hierarchy?.any { it.route == tab.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = { go(tab) },
                            icon = { Icon(if (selected) tab.selectedIcon else tab.icon, contentDescription = stringResource(tab.labelRes)) },
                            label = { Text(stringResource(tab.labelRes)) }
                        )
                    }
                }
            }
        ) { padding ->
            // Consume what the bar took so inner screens don't pad for it twice.
            content(Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize())
        }
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
