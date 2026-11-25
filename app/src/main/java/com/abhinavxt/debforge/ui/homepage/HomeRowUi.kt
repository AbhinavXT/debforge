package com.abhinavxt.debforge.ui.homepage

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.ui.graphics.vector.ImageVector
import com.abhinavxt.debforge.R

@StringRes
internal fun HomeRow.labelRes() = when (this) {
    HomeRow.CONTINUE_WATCHING -> R.string.continue_watching
    HomeRow.CONTINUE_LISTENING -> R.string.home_continue_listening
    HomeRow.RECENT -> R.string.home_recently_added
    HomeRow.DOWNLOADS -> R.string.home_downloading
    HomeRow.NEW_EPISODES -> R.string.upcoming_title
    HomeRow.SHORTCUTS -> R.string.home_shortcuts
}

internal fun HomeRow.icon(): ImageVector = when (this) {
    HomeRow.CONTINUE_WATCHING -> Icons.Rounded.PlayCircle
    HomeRow.CONTINUE_LISTENING -> Icons.Rounded.Headphones
    HomeRow.RECENT -> Icons.Rounded.NewReleases
    HomeRow.DOWNLOADS -> Icons.Rounded.Download
    HomeRow.NEW_EPISODES -> Icons.Rounded.Event
    HomeRow.SHORTCUTS -> Icons.Rounded.Apps
}
