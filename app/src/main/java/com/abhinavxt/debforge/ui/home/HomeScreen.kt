package com.abhinavxt.debforge.ui.home

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.abhinavxt.debforge.ui.active.ActiveDownloadsScreen
import com.abhinavxt.debforge.ui.browse.BrowseScreen
import com.abhinavxt.debforge.ui.settings.SettingsScreen

private enum class HomeTab(val route: String, val label: String, val icon: ImageVector) {
    Browse("browse", "Browse", Icons.Default.List),
    Active("active", "Active", Icons.Default.Refresh),
    Settings("settings", "Settings", Icons.Default.Settings)
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
@Composable
fun HomeScreen() {
    NotificationPermissionRequest()

    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()

    Scaffold(
        bottomBar = {
            NavigationBar {
                HomeTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = backStackEntry?.destination?.hierarchy
                            ?.any { it.route == tab.route } == true,
                        onClick = {
                            navController.navigate(tab.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) }
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = HomeTab.Browse.route,
            modifier = Modifier.padding(padding).fillMaxSize()
        ) {
            composable(HomeTab.Browse.route) { BrowseScreen() }
            composable(HomeTab.Active.route) { ActiveDownloadsScreen() }
            composable(HomeTab.Settings.route) { SettingsScreen() }
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
