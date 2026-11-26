@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.abhinavxt.debforge.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.abhinavxt.debforge.R
import com.abhinavxt.debforge.download.SafPaths
import com.abhinavxt.debforge.download.ScheduleAlarm
import com.abhinavxt.debforge.download.StorageAccess
import com.abhinavxt.debforge.ui.browse.formatSize
import com.abhinavxt.debforge.domain.GridSize
import com.abhinavxt.debforge.ui.home.TabConfig
import com.abhinavxt.debforge.ui.homepage.HomeRow
import com.abhinavxt.debforge.ui.homepage.icon
import com.abhinavxt.debforge.ui.homepage.labelRes
import com.abhinavxt.debforge.ui.components.RowDivider
import com.abhinavxt.debforge.ui.components.SectionCard
import com.abhinavxt.debforge.ui.components.SettingRow
import com.abhinavxt.debforge.ui.components.SwitchSettingRow
import com.abhinavxt.debforge.ui.pluralRes

/** What the pages ask the screen to do: the dialogs and navigation live in [SettingsScreen]. */
internal class SettingsActions(
    val onConnect: () -> Unit,
    val showSignOut: () -> Unit,
    val showFolderPicker: () -> Unit,
    val showFollows: () -> Unit,
    val showPosterSetup: () -> Unit,
)

/** The options of [category], one card per item; [SEARCH_SOURCES_ITEM] is the scroll target of Connections. */
internal fun LazyListScope.settingsPage(category: SettingsCategory, viewModel: SettingsViewModel, actions: SettingsActions) {
    when (category) {
        SettingsCategory.ACCOUNT -> accountItems(viewModel, actions)
        SettingsCategory.APPEARANCE -> appearanceItems(viewModel)
        SettingsCategory.HOME -> { homeItems(viewModel, actions); tabsItem(viewModel); homeRowsItem(viewModel) }
        SettingsCategory.DOWNLOADS -> downloadsItems(viewModel, actions)
        SettingsCategory.VIDEO -> videoItems(viewModel)
        SettingsCategory.AUDIO -> audioItems(viewModel)
        SettingsCategory.YOUTUBE -> youTubeItems(viewModel)
        SettingsCategory.CONNECTIONS -> {
            item(key = "trakt") { TraktSection() }
            item(key = "opensubtitles") { OpenSubtitlesSection() }
            item(key = "search_sources") { SearchSourcesSection() }
        }
        SettingsCategory.DATA -> {
            item(key = "usage") { DataUsageSection() }
            item(key = "backup") { BackupSection() }
        }
        SettingsCategory.ABOUT -> item(key = "about") { AboutSection() }
    }
}

/** Index of the search-sources card within the Connections page. */
internal const val SEARCH_SOURCES_ITEM = 2

private fun LazyListScope.accountItems(viewModel: SettingsViewModel, actions: SettingsActions) {
    item(key = "service") {
        val providers by viewModel.providers.collectAsStateWithLifecycle()
        val account by viewModel.account.collectAsStateWithLifecycle()
        val uriHandler = LocalUriHandler.current
        val activeInfo = providers.firstOrNull { it.active }?.info
        val activeName = providers.firstOrNull { it.active }?.info?.displayName
            ?: stringResource(R.string.set_service_fallback)
        SectionCard(title = stringResource(R.string.set_service)) {
            AccountHeader(
                account = account,
                serviceName = activeInfo?.displayName,
                onManage = { activeInfo?.let { uriHandler.openUri(it.websiteUrl) } }
            )
            providers.forEach { row ->
                ProviderChoiceRow(
                    name = row.info.displayName,
                    signedIn = row.signedIn,
                    selected = row.active,
                    onClick = {
                        viewModel.selectProvider(row.info.id)
                        // Its sign-in is on the Library tab.
                        if (!row.signedIn) actions.onConnect()
                    }
                )
            }
            if (providers.any { it.active && it.signedIn }) {
                RowDivider()
                SettingRow(
                    icon = Icons.AutoMirrored.Rounded.Logout,
                    title = stringResource(R.string.set_sign_out_of, activeName),
                    onClick = { actions.showSignOut() }
                )
            }
        }
    }
    item(key = "expiry") {
        val expiryReminderDays by viewModel.expiryReminderDays.collectAsStateWithLifecycle()
        SectionCard(modifier = Modifier.padding(top = 20.dp)) {
            ExpiryReminderRow(current = expiryReminderDays, onPick = viewModel::setExpiryReminderDays)
        }
    }
}

private fun LazyListScope.appearanceItems(viewModel: SettingsViewModel) {
    item(key = "appearance") {
        val theme by viewModel.themeMode.collectAsStateWithLifecycle()
        val dynamicColor by viewModel.dynamicColor.collectAsStateWithLifecycle()
        val appTheme by viewModel.appTheme.collectAsStateWithLifecycle()
        val pureBlack by viewModel.pureBlack.collectAsStateWithLifecycle()
        SectionCard(modifier = Modifier.padding(top = 8.dp)) {
            AppearanceContent(
                appTheme = appTheme,
                dynamicColor = dynamicColor,
                mode = theme,
                pureBlack = pureBlack,
                onSelectTheme = viewModel::selectTheme,
                onSelectWallpaper = { viewModel.setDynamicColor(true) },
                onMode = viewModel::setThemeMode,
                onPureBlack = viewModel::setPureBlack
            )
            RowDivider()
            val gridSize by viewModel.gridSize.collectAsStateWithLifecycle()
            ChoiceRow(
                icon = Icons.Rounded.GridView,
                title = stringResource(R.string.set_grid_size),
                current = gridSize,
                options = GridSize.entries,
                label = { stringResource(it.labelRes()) },
                onPick = viewModel::setGridSize
            )
            // Per-app language needs Android 13+.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                RowDivider()
                LanguageRow()
            }
        }
    }
}

private fun LazyListScope.homeItems(viewModel: SettingsViewModel, actions: SettingsActions) {
    item(key = "home") {
        val follows by viewModel.follows.collectAsStateWithLifecycle()
        val channels by viewModel.channels.collectAsStateWithLifecycle()
        val podcasts by viewModel.podcasts.collectAsStateWithLifecycle()
        val showExtras by viewModel.showExtraFiles.collectAsStateWithLifecycle()
        val clipboardOffer by viewModel.clipboardOffer.collectAsStateWithLifecycle()
        val tmdbKey by viewModel.tmdbKey.collectAsStateWithLifecycle()
        val postersEnabled by viewModel.postersEnabled.collectAsStateWithLifecycle()
        val sort by viewModel.sortDefault.collectAsStateWithLifecycle()
        SectionCard(modifier = Modifier.padding(top = 8.dp)) {
            SortRow(current = sort, onPick = viewModel::setSortDefault)
            RowDivider()
            SwitchSettingRow(
                icon = Icons.Rounded.FilterList,
                title = stringResource(R.string.set_show_extras),
                subtitle = stringResource(R.string.set_show_extras_detail),
                checked = showExtras,
                onChange = viewModel::setShowExtraFiles
            )
            RowDivider()
            SettingRow(
                icon = Icons.Rounded.NotificationsActive,
                title = stringResource(R.string.set_following),
                subtitle = if (follows.isEmpty() && channels.isEmpty() && podcasts.isEmpty()) stringResource(R.string.set_following_none)
                else (follows.map { it.title } + channels.map { it.title } + podcasts.map { it.title }).joinToString(", "),
                onClick = if (follows.isEmpty() && channels.isEmpty() && podcasts.isEmpty()) null else actions.showFollows
            )
            RowDivider()
            TmdbKeyRow(
                hasUserKey = tmdbKey.isNotBlank(),
                enabled = postersEnabled,
                onSetUp = actions.showPosterSetup,
                onRemove = { viewModel.setTmdbKey("") },
                onClearCache = viewModel::clearPosterCache
            )
            RowDivider()
            SwitchSettingRow(
                icon = Icons.Rounded.ContentPaste,
                title = stringResource(R.string.set_clipboard_offer),
                subtitle = stringResource(R.string.set_clipboard_offer_detail),
                checked = clipboardOffer,
                onChange = viewModel::setClipboardOffer
            )
        }
    }
}

private fun LazyListScope.tabsItem(viewModel: SettingsViewModel) {
    item(key = "tabs") {
        val stored by viewModel.tabsStored.collectAsStateWithLifecycle()
        val hasSource by viewModel.hasSearchSource.collectAsStateWithLifecycle()
        val start by viewModel.startTab.collectAsStateWithLifecycle()
        val libraryName by viewModel.libraryName.collectAsStateWithLifecycle()
        TabsCard(
            bar = TabConfig.bar(stored, hasSource),
            start = start,
            libraryName = libraryName,
            onBar = viewModel::setTabs,
            onStart = viewModel::setStartTab,
            modifier = Modifier.padding(top = 20.dp)
        )
    }
}

private fun LazyListScope.homeRowsItem(viewModel: SettingsViewModel) {
    item(key = "home_rows") {
        val hidden by viewModel.homeRowsHidden.collectAsStateWithLifecycle()
        SectionCard(title = stringResource(R.string.set_home_rows), modifier = Modifier.padding(top = 20.dp)) {
            HomeRow.entries.forEachIndexed { i, row ->
                if (i > 0) RowDivider()
                SwitchSettingRow(
                    icon = row.icon(),
                    title = stringResource(row.labelRes()),
                    subtitle = null,
                    checked = row !in hidden,
                    onChange = { viewModel.setHomeRowShown(row, it) }
                )
            }
        }
    }
}

private fun LazyListScope.downloadsItems(viewModel: SettingsViewModel, actions: SettingsActions) {
    item(key = "folder") {
        val context = LocalContext.current
        val dir by viewModel.downloadDir.collectAsStateWithLifecycle()
        val importLocal by viewModel.importLocal.collectAsStateWithLifecycle()
        val organize by viewModel.organizeLibrary.collectAsStateWithLifecycle()
        val removeAfter by viewModel.removeAfterDownload.collectAsStateWithLifecycle()
        val storageInfo by viewModel.storageInfo.collectAsStateWithLifecycle()
        val autoDeleteDays by viewModel.autoDeleteDays.collectAsStateWithLifecycle()
        // The storage lookup touches the disk: off the main thread.
        val inAppStorage by produceState(false, dir) {
            value = dir.isNotBlank() && !SafPaths.isTreeUri(dir) &&
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { StorageAccess.isAppStorage(context, dir) }
        }
        SectionCard(title = stringResource(R.string.set_download_folder)) {
            SettingRow(
                icon = Icons.Rounded.Folder,
                // A friendly name; the full path (which wraps) sits underneath for plain paths.
                title = if (dir.isNotBlank() && !SafPaths.isTreeUri(dir) && inAppStorage)
                    stringResource(R.string.set_folder_app_storage)
                else StorageAccess.label(context, dir),
                subtitle = dir.takeIf { it.isNotBlank() && !SafPaths.isTreeUri(it) },
                onClick = actions.showFolderPicker,
                trailing = {
                    TextButton(onClick = actions.showFolderPicker) { Text(stringResource(R.string.set_change)) }
                }
            )
            RowDivider()
            SwitchSettingRow(
                icon = Icons.Rounded.FolderOpen,
                title = stringResource(R.string.set_import_local),
                subtitle = stringResource(R.string.set_import_local_detail),
                checked = importLocal,
                onChange = viewModel::setImportLocal
            )
            RowDivider()
            SwitchSettingRow(
                icon = Icons.Rounded.CreateNewFolder,
                title = stringResource(R.string.set_organise),
                subtitle = stringResource(R.string.set_organise_detail),
                checked = organize,
                onChange = viewModel::setOrganizeLibrary
            )
            RowDivider()
            SwitchSettingRow(
                icon = Icons.Rounded.DeleteSweep,
                title = stringResource(R.string.set_remove_after),
                subtitle = stringResource(R.string.set_remove_after_detail),
                checked = removeAfter,
                onChange = viewModel::setRemoveAfterDownload
            )
            storageInfo?.let { info ->
                RowDivider()
                SettingRow(
                    icon = Icons.Rounded.Storage,
                    title = info.freeBytes?.let { stringResource(R.string.set_storage_free, formatSize(it)) }
                        ?: stringResource(R.string.set_storage_free_unknown),
                    subtitle = if (info.downloadedFiles > 0) {
                        pluralRes(
                            R.plurals.set_storage_downloaded,
                            info.downloadedFiles,
                            android.text.format.Formatter.formatShortFileSize(context, info.downloadedBytes),
                            info.downloadedFiles
                        )
                    } else null
                )
            }
            RowDivider()
            AutoDeleteRow(current = autoDeleteDays, onPick = viewModel::pickAutoDeleteDays)
        }
    }
    item(key = "when") {
        val context = LocalContext.current
        val rules by viewModel.rules.collectAsStateWithLifecycle()
        SectionCard(title = stringResource(R.string.set_when)) {
            SwitchSettingRow(
                icon = Icons.Rounded.Wifi,
                title = stringResource(R.string.set_wifi_only),
                subtitle = stringResource(R.string.set_wifi_only_detail),
                checked = rules.wifiOnly,
                onChange = viewModel::setWifiOnly
            )
            RowDivider()
            SwitchSettingRow(
                icon = Icons.Rounded.BatteryChargingFull,
                title = stringResource(R.string.set_charging_only),
                subtitle = null,
                checked = rules.chargingOnly,
                onChange = viewModel::setChargingOnly
            )
            RowDivider()
            SwitchSettingRow(
                icon = Icons.Rounded.Schedule,
                title = stringResource(R.string.set_schedule),
                subtitle = stringResource(R.string.set_schedule_detail),
                checked = rules.scheduleEnabled,
                onChange = { viewModel.setSchedule(it, rules.scheduleStartHour, rules.scheduleEndHour) }
            )
            if (rules.scheduleEnabled) {
                ScheduleRow(
                    start = rules.scheduleStartHour,
                    end = rules.scheduleEndHour,
                    onChange = { st, en -> viewModel.setSchedule(true, st, en) }
                )
                if (!ScheduleAlarm.canBeExact(context)) {
                    Column(Modifier.padding(start = 68.dp, end = 16.dp, bottom = 8.dp)) {
                        Text(
                            stringResource(R.string.set_alarm_needed),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(onClick = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                context.startActivity(
                                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                                        .setData(Uri.parse("package:${context.packageName}"))
                                )
                            }
                        }) { Text(stringResource(R.string.action_allow)) }
                    }
                }
            }
            RowDivider()
            SpeedLimitRow(current = rules.speedLimitKbps, onPick = viewModel::setSpeedLimit)
            RowDivider()
            val storageUsed by viewModel.storageUsedBytes.collectAsStateWithLifecycle()
            StorageLimitRow(current = rules.storageLimitGb, usedBytes = storageUsed, onPick = viewModel::setStorageLimit)
        }
    }
}

private fun LazyListScope.videoItems(viewModel: SettingsViewModel) {
    item(key = "video") {
        val internalPlayer by viewModel.internalPlayer.collectAsStateWithLifecycle()
        val autoplayNext by viewModel.autoplayNext.collectAsStateWithLifecycle()
        val autoSkipIntro by viewModel.autoSkipIntro.collectAsStateWithLifecycle()
        val playerGestures by viewModel.playerGestures.collectAsStateWithLifecycle()
        val videoFullScreen by viewModel.videoFullScreen.collectAsStateWithLifecycle()
        val pip by viewModel.pip.collectAsStateWithLifecycle()
        val audioLanguage by viewModel.audioLanguage.collectAsStateWithLifecycle()
        val subtitleLanguage by viewModel.subtitleLanguage.collectAsStateWithLifecycle()
        val streamPreviews by viewModel.streamPreviews.collectAsStateWithLifecycle()
        SectionCard(modifier = Modifier.padding(top = 8.dp)) {
            PlayerRow(internal = internalPlayer, onPick = viewModel::setInternalPlayer)
            // Only DebForge's player plays the next episode, picks tracks or shows previews.
            if (internalPlayer) {
                RowDivider()
                SwitchSettingRow(
                    icon = Icons.Rounded.SkipNext,
                    title = stringResource(R.string.set_autoplay_next),
                    subtitle = stringResource(R.string.set_autoplay_next_detail),
                    checked = autoplayNext,
                    onChange = viewModel::setAutoplayNext
                )
                RowDivider()
                SwitchSettingRow(
                    icon = Icons.Rounded.FastForward,
                    title = stringResource(R.string.set_auto_skip_intro),
                    subtitle = stringResource(R.string.set_auto_skip_intro_detail),
                    checked = autoSkipIntro,
                    onChange = viewModel::setAutoSkipIntro
                )
                RowDivider()
                SwitchSettingRow(
                    icon = Icons.Rounded.TouchApp,
                    title = stringResource(R.string.set_player_gestures),
                    subtitle = stringResource(R.string.set_player_gestures_detail),
                    checked = playerGestures,
                    onChange = viewModel::setPlayerGestures
                )
                RowDivider()
                SwitchSettingRow(
                    icon = Icons.Rounded.Fullscreen,
                    title = stringResource(R.string.set_video_full_screen),
                    subtitle = stringResource(R.string.set_video_full_screen_detail),
                    checked = videoFullScreen,
                    onChange = viewModel::setVideoFullScreen
                )
                RowDivider()
                if (viewModel.pipAvailable) {
                    SwitchSettingRow(
                        icon = Icons.Rounded.PictureInPictureAlt,
                        title = stringResource(R.string.set_pip),
                        subtitle = stringResource(R.string.set_pip_detail),
                        checked = pip,
                        onChange = viewModel::setPip
                    )
                    RowDivider()
                }
                LanguageRow(
                    icon = Icons.Rounded.Audiotrack,
                    title = stringResource(R.string.set_audio_language),
                    current = audioLanguage,
                    offOption = false,
                    onPick = viewModel::setAudioLanguage
                )
                RowDivider()
                LanguageRow(
                    icon = Icons.Rounded.ClosedCaption,
                    title = stringResource(R.string.set_subtitle_language),
                    current = subtitleLanguage,
                    offOption = true,
                    onPick = viewModel::setSubtitleLanguage
                )
                RowDivider()
                SwitchSettingRow(
                    icon = Icons.Rounded.Image,
                    title = stringResource(R.string.set_stream_previews),
                    subtitle = stringResource(R.string.set_stream_previews_detail),
                    checked = streamPreviews,
                    onChange = viewModel::setStreamPreviews
                )
            }
        }
    }
}

private fun LazyListScope.audioItems(viewModel: SettingsViewModel) {
    item(key = "audio") {
        val audioSkipSeconds by viewModel.audioSkipSeconds.collectAsStateWithLifecycle()
        val evenOutVolume by viewModel.evenOutVolume.collectAsStateWithLifecycle()
        val notificationButtons by viewModel.notificationButtons.collectAsStateWithLifecycle()
        val keepPlayingSwiped by viewModel.keepPlayingSwiped.collectAsStateWithLifecycle()
        val skipSilence by viewModel.skipSilence.collectAsStateWithLifecycle()
        val fadeBetween by viewModel.fadeBetweenTracks.collectAsStateWithLifecycle()
        val playerArtColor by viewModel.playerArtColor.collectAsStateWithLifecycle()
        SectionCard(modifier = Modifier.padding(top = 8.dp)) {
            SkipLengthRow(current = audioSkipSeconds, onPick = viewModel::setAudioSkipSeconds)
            RowDivider()
            NotificationButtonsRow(current = notificationButtons, onPick = viewModel::setNotificationButtons)
            RowDivider()
            SwitchSettingRow(
                icon = Icons.Rounded.FastForward,
                title = stringResource(R.string.set_keep_playing_swiped),
                subtitle = stringResource(R.string.set_keep_playing_swiped_detail),
                checked = keepPlayingSwiped,
                onChange = viewModel::setKeepPlayingSwiped
            )
            RowDivider()
            FadeBetweenTracksRow(current = fadeBetween, onPick = viewModel::setFadeBetweenTracks)
            RowDivider()
            SwitchSettingRow(
                icon = Icons.Rounded.FastForward,
                title = stringResource(R.string.set_skip_silence),
                subtitle = stringResource(R.string.set_skip_silence_detail),
                checked = skipSilence,
                onChange = viewModel::setSkipSilence
            )
            RowDivider()
            SwitchSettingRow(
                icon = Icons.Rounded.FastForward,
                title = stringResource(R.string.set_even_out_volume),
                subtitle = stringResource(R.string.set_even_out_volume_detail),
                checked = evenOutVolume,
                onChange = viewModel::setEvenOutVolume
            )
            RowDivider()
            SwitchSettingRow(
                icon = Icons.Rounded.Palette,
                title = stringResource(R.string.set_player_art_color),
                subtitle = stringResource(R.string.set_player_art_color_detail),
                checked = playerArtColor,
                onChange = viewModel::setPlayerArtColor
            )
        }
    }
}

private fun LazyListScope.youTubeItems(viewModel: SettingsViewModel) {
    item(key = "youtube") {
        val youTubeQuality by viewModel.youTubeQuality.collectAsStateWithLifecycle()
        val sponsorBlock by viewModel.sponsorBlock.collectAsStateWithLifecycle()
        val sponsorCategories by viewModel.sponsorCategories.collectAsStateWithLifecycle()
        val youTubeAutoplay by viewModel.youTubeAutoplay.collectAsStateWithLifecycle()
        SectionCard(modifier = Modifier.padding(top = 8.dp)) {
            YouTubeQualityRow(current = youTubeQuality, onPick = viewModel::setYouTubeQuality)
            RowDivider()
            SwitchSettingRow(
                icon = Icons.Rounded.FastForward,
                title = stringResource(R.string.set_sponsorblock),
                subtitle = stringResource(R.string.set_sponsorblock_detail),
                checked = sponsorBlock,
                onChange = viewModel::setSponsorBlock
            )
            if (sponsorBlock) {
                val cats = listOf(
                    "sponsor" to R.string.set_sb_sponsor, "selfpromo" to R.string.set_sb_selfpromo,
                    "interaction" to R.string.set_sb_interaction, "intro" to R.string.set_sb_intro,
                    "outro" to R.string.set_sb_outro, "preview" to R.string.set_sb_preview,
                )
                androidx.compose.foundation.layout.FlowRow(
                    Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    cats.forEach { (key, label) ->
                        val on = key in sponsorCategories
                        androidx.compose.material3.FilterChip(
                            selected = on,
                            onClick = { viewModel.setSponsorCategories(if (on) sponsorCategories - key else sponsorCategories + key) },
                            label = { Text(stringResource(label)) },
                            modifier = Modifier.heightIn(min = 48.dp),
                        )
                    }
                }
                if (sponsorCategories.isEmpty()) Text(
                    stringResource(R.string.set_sb_none_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                )
            }
            RowDivider()
            SwitchSettingRow(
                icon = Icons.Rounded.SkipNext,
                title = stringResource(R.string.set_yt_autoplay),
                subtitle = stringResource(R.string.set_yt_autoplay_detail),
                checked = youTubeAutoplay,
                onChange = viewModel::setYouTubeAutoplay
            )
        }
    }
}
