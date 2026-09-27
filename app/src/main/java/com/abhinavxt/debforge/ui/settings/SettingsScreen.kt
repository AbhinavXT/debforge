package com.abhinavxt.debforge.ui.settings

import android.app.LocaleManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.LocaleList
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.AutoDelete
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WorkspacePremium
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.abhinavxt.debforge.R
import com.abhinavxt.debforge.ui.posters.PosterSetupSheet
import com.abhinavxt.debforge.data.provider.AccountInfo
import com.abhinavxt.debforge.domain.SortOrder
import com.abhinavxt.debforge.download.SafPaths
import com.abhinavxt.debforge.download.ScheduleAlarm
import com.abhinavxt.debforge.download.StorageAccess
import com.abhinavxt.debforge.ui.components.RowDivider
import com.abhinavxt.debforge.ui.components.ScreenHeader
import com.abhinavxt.debforge.ui.components.SectionCard
import com.abhinavxt.debforge.ui.components.SettingRow
import com.abhinavxt.debforge.ui.components.StatusPill
import com.abhinavxt.debforge.ui.components.SwitchSettingRow
import com.abhinavxt.debforge.ui.pluralRes
import com.abhinavxt.debforge.ui.browse.formatSize
import com.abhinavxt.debforge.domain.Housekeeping
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val dir by viewModel.downloadDir.collectAsStateWithLifecycle()
    val sort by viewModel.sortDefault.collectAsStateWithLifecycle()
    val theme by viewModel.themeMode.collectAsStateWithLifecycle()
    val providers by viewModel.providers.collectAsStateWithLifecycle()
    val dynamicColor by viewModel.dynamicColor.collectAsStateWithLifecycle()
    val appTheme by viewModel.appTheme.collectAsStateWithLifecycle()
    val pureBlack by viewModel.pureBlack.collectAsStateWithLifecycle()
    val tmdbKey by viewModel.tmdbKey.collectAsStateWithLifecycle()
    val postersEnabled by viewModel.postersEnabled.collectAsStateWithLifecycle()
    var posterSetupOpen by remember { mutableStateOf(false) }
    val account by viewModel.account.collectAsStateWithLifecycle()
    val organize by viewModel.organizeLibrary.collectAsStateWithLifecycle()
    val rules by viewModel.rules.collectAsStateWithLifecycle()
    val removeAfter by viewModel.removeAfterDownload.collectAsStateWithLifecycle()
    val showExtras by viewModel.showExtraFiles.collectAsStateWithLifecycle()
    val clipboardOffer by viewModel.clipboardOffer.collectAsStateWithLifecycle()
    val storageInfo by viewModel.storageInfo.collectAsStateWithLifecycle()
    val autoDeleteDays by viewModel.autoDeleteDays.collectAsStateWithLifecycle()
    val autoDeleteConfirm by viewModel.autoDeleteConfirm.collectAsStateWithLifecycle()
    val follows by viewModel.follows.collectAsStateWithLifecycle()
    val internalPlayer by viewModel.internalPlayer.collectAsStateWithLifecycle()
    val autoplayNext by viewModel.autoplayNext.collectAsStateWithLifecycle()
    var followsOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val activeInfo = providers.firstOrNull { it.active }?.info
    val activeName = providers.firstOrNull { it.active }?.info?.displayName
        ?: stringResource(R.string.set_service_fallback)

    var showFolderDialog by remember { mutableStateOf(false) }
    // System folder picker: any folder incl. SD card / USB, no special permission.
    val pickFolder = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            runCatching { StorageAccess.rememberPickedFolder(context, uri, dir) }
            viewModel.setDownloadDir(uri.toString())
            showFolderDialog = false
        }
    }
    var showSignOutDialog by remember { mutableStateOf(false) }

    Scaffold(topBar = { ScreenHeader(title = stringResource(R.string.tab_settings)) }) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            item(key = "appearance") {
                SectionCard(title = stringResource(R.string.set_appearance)) {
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
                    // Per-app language needs Android 13+.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        RowDivider()
                        LanguageRow()
                    }
                }
            }

            item(key = "folder") {
                SectionCard(title = stringResource(R.string.set_download_folder)) {
                    SettingRow(
                        icon = Icons.Rounded.Folder,
                        title = StorageAccess.label(context, dir),
                        onClick = { showFolderDialog = true },
                        trailing = {
                            TextButton(onClick = { showFolderDialog = true }) { Text(stringResource(R.string.set_change)) }
                        }
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
                                    formatSize(info.downloadedBytes),
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
                }
            }

            item(key = "library") {
                SectionCard(title = stringResource(R.string.tab_library)) {
                    SortRow(current = sort, onPick = viewModel::setSortDefault)
                    RowDivider()
                    PlayerRow(internal = internalPlayer, onPick = viewModel::setInternalPlayer)
                    RowDivider()
                    // Only DebForge's player plays the next episode.
                    if (internalPlayer) {
                        SwitchSettingRow(
                            icon = Icons.Rounded.SkipNext,
                            title = stringResource(R.string.set_autoplay_next),
                            subtitle = stringResource(R.string.set_autoplay_next_detail),
                            checked = autoplayNext,
                            onChange = viewModel::setAutoplayNext
                        )
                        RowDivider()
                    }
                    SwitchSettingRow(
                        icon = Icons.Rounded.FilterList,
                        title = stringResource(R.string.set_show_extras),
                        subtitle = stringResource(R.string.set_show_extras_detail),
                        checked = showExtras,
                        onChange = viewModel::setShowExtraFiles
                    )
                    RowDivider()
                    SwitchSettingRow(
                        icon = Icons.Rounded.ContentPaste,
                        title = stringResource(R.string.set_clipboard_offer),
                        subtitle = stringResource(R.string.set_clipboard_offer_detail),
                        checked = clipboardOffer,
                        onChange = viewModel::setClipboardOffer
                    )
                    RowDivider()
                    SettingRow(
                        icon = Icons.Rounded.NotificationsActive,
                        title = stringResource(R.string.set_follows),
                        subtitle = if (follows.isEmpty()) stringResource(R.string.set_follows_none)
                        else follows.joinToString(", ") { it.title },
                        onClick = if (follows.isEmpty()) null else ({ followsOpen = true })
                    )
                    RowDivider()
                    TmdbKeyRow(
                        hasUserKey = tmdbKey.isNotBlank(),
                        enabled = postersEnabled,
                        onSetUp = { posterSetupOpen = true },
                        onRemove = { viewModel.setTmdbKey("") },
                        onClearCache = viewModel::clearPosterCache
                    )
                }
            }

            item(key = "service") {
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
                            onClick = { viewModel.selectProvider(row.info.id) }
                        )
                    }
                    RowDivider()
                    SettingRow(
                        icon = Icons.AutoMirrored.Rounded.Logout,
                        title = stringResource(R.string.set_sign_out_of, activeName),
                        onClick = { showSignOutDialog = true }
                    )
                }
            }
            item(key = "usage") { DataUsageSection() }
            item(key = "backup") { BackupSection() }
            item(key = "about") { AboutSection() }
        }
    }

    if (followsOpen) {
        val multiService = follows.map { it.provider }.distinct().size > 1
        AlertDialog(
            onDismissRequest = { followsOpen = false },
            icon = { Icon(Icons.Rounded.NotificationsActive, contentDescription = null) },
            title = { Text(stringResource(R.string.set_follows)) },
            text = {
                Column {
                    Text(
                        stringResource(R.string.set_follows_detail),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    follows.forEach { f ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(f.title, style = MaterialTheme.typography.bodyLarge)
                                val sub = listOfNotNull(
                                    viewModel.providerName(f.provider).takeIf { multiService },
                                    f.resolution
                                ).joinToString(" · ")
                                if (sub.isNotEmpty()) {
                                    Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            TextButton(onClick = { viewModel.unfollow(f) }) { Text(stringResource(R.string.follow_unfollow)) }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { followsOpen = false }) { Text(stringResource(R.string.action_done)) }
            }
        )
    }

    autoDeleteConfirm?.let { p ->
        AlertDialog(
            onDismissRequest = viewModel::dismissAutoDelete,
            icon = { Icon(Icons.Rounded.AutoDelete, contentDescription = null) },
            title = { Text(stringResource(R.string.set_auto_delete_confirm_title)) },
            text = {
                Text(
                    pluralRes(R.plurals.set_auto_delete_confirm_body, p.files, p.files, formatSize(p.bytes), p.days)
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmAutoDelete) { Text(stringResource(R.string.set_auto_delete_now)) }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissAutoDelete) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }

    if (posterSetupOpen) {
        PosterSetupSheet(onDismiss = { posterSetupOpen = false }, onSaved = {})
    }

    if (showFolderDialog) {
        FolderPickerDialog(
            current = dir,
            onPickAny = { pickFolder.launch(null) },
            onPick = { picked ->
                viewModel.setDownloadDir(picked)
                showFolderDialog = false
            },
            onDismiss = { showFolderDialog = false }
        )
    }

    if (showSignOutDialog) {
        AlertDialog(
            onDismissRequest = { showSignOutDialog = false },
            icon = { Icon(Icons.AutoMirrored.Rounded.Logout, contentDescription = null) },
            title = { Text(stringResource(R.string.set_sign_out_q, activeName)) },
            text = { Text(stringResource(R.string.set_sign_out_body, activeName)) },
            confirmButton = {
                TextButton(onClick = {
                    showSignOutDialog = false
                    viewModel.signOut()
                }) { Text(stringResource(R.string.set_sign_out), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showSignOutDialog = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
}

// --- rows --------------------------------------------------------------------

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

/** A service in the list: radio, name, signed-in pill. */
@Composable
private fun ProviderChoiceRow(name: String, signedIn: Boolean, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(8.dp))
        Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        val cs = MaterialTheme.colorScheme
        StatusPill(
            text = stringResource(if (signedIn) R.string.set_signed_in else R.string.set_not_signed_in),
            container = if (signedIn) cs.primaryContainer else cs.surfaceContainerHighest,
            content = if (signedIn) cs.onPrimaryContainer else cs.onSurfaceVariant,
            icon = if (signedIn) Icons.Rounded.Check else null
        )
    }
}

// --- folder picker dialog ----------------------------------------------------

@Composable
private fun FolderPickerDialog(
    current: String,
    onPickAny: () -> Unit,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val appDir = remember { StorageAccess.appStorageDir(context) }
    val presets = remember { buildPresetFolders() + appDir }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.FolderOpen, contentDescription = null) },
        title = { Text(stringResource(R.string.set_choose_folder)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.set_folder_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                // Recommended: works for SD cards and USB drives, needs no permission.
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                        .clickable(onClick = onPickAny)
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Text(
                        stringResource(R.string.set_pick_any_folder),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Text(
                        if (SafPaths.isTreeUri(current)) StorageAccess.label(context, current)
                        else stringResource(R.string.set_pick_any_folder_detail),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                    )
                }
                Spacer(Modifier.height(8.dp))
                presets.forEach { preset ->
                    ChoiceRow(
                        label = if (preset == appDir) stringResource(R.string.set_app_storage) + "\n" + preset else preset,
                        selected = preset == current,
                        onClick = { onPick(preset) }
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_done)) } }
    )
}

/** A few common destinations under shared storage. */
private fun buildPresetFolders(): List<String> {
    fun publicDir(name: String) =
        File(Environment.getExternalStoragePublicDirectory(name), "DebForge").absolutePath
    return listOf(
        publicDir(Environment.DIRECTORY_MOVIES),
        publicDir(Environment.DIRECTORY_DOWNLOADS),
        publicDir(Environment.DIRECTORY_PICTURES)
    )
}

// --- labels ------------------------------------------------------------------

@Composable
private fun SortOrder.label(): String = stringResource(
    when (this) {
        SortOrder.DATE_DESC -> R.string.set_sort_newest
        SortOrder.DATE_ASC -> R.string.set_sort_oldest
        SortOrder.NAME_ASC -> R.string.sort_name_az
        SortOrder.NAME_DESC -> R.string.sort_name_za
        SortOrder.SIZE_DESC -> R.string.set_sort_largest
        SortOrder.SIZE_ASC -> R.string.set_sort_smallest
    }
)

/**
 * Per-app language (Android 13+). Uses the platform LocaleManager, so it
 * matches Settings → Apps → DebForge → Language and survives restarts.
 * Language names are shown in their own script, as users expect.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun LanguageRow() {
    val context = LocalContext.current
    val lm = remember { context.getSystemService(LocaleManager::class.java) }
    val current = remember { lm.applicationLocales.toLanguageTags() }
    // Tags must match res/xml/locales_config.xml. Names are shown in their own
    // language so anyone can find theirs whatever the app is set to.
    val options = listOf("" to stringResource(R.string.lang_system)) + LANGUAGES
    val currentName = options.firstOrNull { current.startsWith(it.first) && it.first.isNotEmpty() }?.second
        ?: options.first().second
    var open by remember { mutableStateOf(false) }
    Box {
        SettingRow(
            icon = Icons.Rounded.Language,
            title = stringResource(R.string.set_language),
            subtitle = currentName,
            onClick = { open = true }
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (tag, name) ->
                DropdownMenuItem(
                    text = { Text(name) },
                    leadingIcon = { if (name == currentName) Icon(Icons.Rounded.Check, contentDescription = null) },
                    onClick = {
                        open = false
                        // The system recreates the activity in the new language.
                        lm.applicationLocales =
                            if (tag.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
                    }
                )
            }
        }
    }
}

/** App languages: BCP 47 tag to native name. Indonesian's resources live in values-in. */
private val LANGUAGES = listOf(
    "en" to "English",
    "bn" to "বাংলা",
    "de" to "Deutsch",
    "es" to "Español",
    "fr" to "Français",
    "hi" to "हिन्दी",
    "id" to "Bahasa Indonesia",
    "mr" to "मराठी",
    "pt-BR" to "Português (Brasil)",
    "ru" to "Русский",
    "ta" to "தமிழ்",
    "te" to "తెలుగు",
    "tr" to "Türkçe",
    "zh-CN" to "简体中文"
)

/** Default sort for the Library, picked from a menu. */
@Composable
private fun SortRow(current: SortOrder, onPick: (SortOrder) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        SettingRow(
            icon = Icons.AutoMirrored.Rounded.Sort,
            title = stringResource(R.string.set_default_sort),
            subtitle = current.label(),
            onClick = { open = true }
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SortOrder.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label()) },
                    leadingIcon = { if (option == current) Icon(Icons.Rounded.Check, contentDescription = null) },
                    onClick = { onPick(option); open = false }
                )
            }
        }
    }
}

/** "Play videos in": DebForge's player or another app (VLC, mpv…). */
@Composable
private fun PlayerRow(internal: Boolean, onPick: (Boolean) -> Unit) {
    var open by remember { mutableStateOf(false) }
    @Composable
    fun label(v: Boolean) = stringResource(if (v) R.string.set_player_internal else R.string.set_player_external)
    Box {
        SettingRow(
            icon = Icons.Rounded.PlayCircle,
            title = stringResource(R.string.set_player),
            subtitle = label(internal),
            onClick = { open = true }
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            listOf(true, false).forEach { v ->
                DropdownMenuItem(
                    text = { Text(label(v)) },
                    leadingIcon = { if (v == internal) Icon(Icons.Rounded.Check, contentDescription = null) },
                    onClick = { onPick(v); open = false }
                )
            }
        }
    }
}

/** "Delete downloads after": Never / 7 / 14 / 30 / 60 days. */
@Composable
private fun AutoDeleteRow(current: Int, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    @Composable
    fun label(days: Int): String =
        if (days <= 0) stringResource(R.string.set_auto_delete_never)
        else pluralRes(R.plurals.set_auto_delete_after_days, days, days)
    Box {
        SettingRow(
            icon = Icons.Rounded.AutoDelete,
            title = stringResource(R.string.set_auto_delete),
            subtitle = label(current) + " · " + stringResource(R.string.set_auto_delete_detail),
            onClick = { open = true }
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Housekeeping.AUTO_DELETE_CHOICES.forEach { days ->
                DropdownMenuItem(
                    text = { Text(label(days)) },
                    leadingIcon = { if (days == current) Icon(Icons.Rounded.Check, contentDescription = null) },
                    onClick = { onPick(days); open = false }
                )
            }
        }
    }
}

/**
 * Poster status plus actions. Entering a key happens in [PosterSetupSheet]
 * (steps, link to TMDB, paste, and a check with TMDB before saving).
 */
@Composable
private fun TmdbKeyRow(
    hasUserKey: Boolean,
    enabled: Boolean,
    onSetUp: () -> Unit,
    onRemove: () -> Unit,
    onClearCache: () -> Unit
) {
    SettingRow(
        icon = Icons.Rounded.Image,
        title = stringResource(R.string.set_posters),
        subtitle = stringResource(if (enabled) R.string.set_posters_on else R.string.set_tmdb_note)
    )
    Row(
        Modifier.padding(start = 68.dp, end = 16.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilledTonalButton(onClick = onSetUp) {
            Text(stringResource(if (hasUserKey) R.string.set_posters_change else R.string.poster_banner_action))
        }
        if (hasUserKey) {
            TextButton(onClick = onRemove) { Text(stringResource(R.string.set_posters_remove)) }
        }
        if (enabled) {
            TextButton(onClick = onClearCache) { Text(stringResource(R.string.set_refresh_posters)) }
        }
    }
}

/** Signed-in account at the top of the Service card, on a soft gradient. */
@Composable
private fun AccountHeader(account: AccountInfo?, serviceName: String?, onManage: () -> Unit) {
    if (account == null) return
    val days = account.daysLeft
    val warn = days != null && days <= 7
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .padding(8.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(Brush.linearGradient(listOf(cs.primaryContainer, cs.tertiaryContainer)))
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(color = cs.primary, contentColor = cs.onPrimary, shape = CircleShape, modifier = Modifier.size(40.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        account.displayName.trim().take(1).uppercase(),
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    account.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = cs.onPrimaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    listOfNotNull(serviceName, account.plan).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onPrimaryContainer.copy(alpha = 0.8f)
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (days != null) {
                StatusPill(
                    text = when {
                        days < 0 -> stringResource(R.string.acct_expired)
                        days == 0L -> stringResource(R.string.acct_expires_today)
                        else -> pluralRes(R.plurals.acct_days_left, days.toInt(), days.toInt())
                    },
                    container = if (warn) cs.errorContainer else cs.surface.copy(alpha = 0.7f),
                    content = if (warn) cs.onErrorContainer else cs.onSurface,
                    icon = Icons.Rounded.WorkspacePremium
                )
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onManage) {
                Text(
                    stringResource(if (warn) R.string.action_renew else R.string.acct_manage),
                    color = cs.onPrimaryContainer
                )
            }
        }
    }
}

@Composable
private fun ScheduleRow(start: Int, end: Int, onChange: (Int, Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 68.dp, end = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(stringResource(R.string.set_from), style = MaterialTheme.typography.bodyMedium)
        HourPicker(start) { onChange(it, end) }
        Text(stringResource(R.string.set_to), style = MaterialTheme.typography.bodyMedium)
        HourPicker(end) { onChange(start, it) }
    }
}

@Composable
private fun HourPicker(hour: Int, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) { Text("%02d:00".format(hour)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            (0..23).forEach { h ->
                DropdownMenuItem(text = { Text("%02d:00".format(h)) }, onClick = { onPick(h); open = false })
            }
        }
    }
}

private val SPEED_OPTIONS = listOf(0, 1024, 2 * 1024, 5 * 1024, 10 * 1024, 25 * 1024, 50 * 1024)

@Composable
private fun speedLabel(kbps: Int) =
    if (kbps <= 0) stringResource(R.string.speed_unlimited) else stringResource(R.string.speed_mbps, kbps / 1024)

@Composable
private fun SpeedLimitRow(current: Int, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        SettingRow(
            icon = Icons.Rounded.Speed,
            title = stringResource(R.string.set_speed_limit),
            subtitle = stringResource(R.string.set_speed_detail),
            onClick = { open = true },
            trailing = {
                Text(
                    speedLabel(current),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SPEED_OPTIONS.forEach { k ->
                DropdownMenuItem(
                    text = { Text(speedLabel(k)) },
                    leadingIcon = { if (k == current) Icon(Icons.Rounded.Check, contentDescription = null) },
                    onClick = { onPick(k); open = false }
                )
            }
        }
    }
}
