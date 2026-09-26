package com.abhinavxt.debforge.ui.settings

import androidx.annotation.RequiresApi
import android.os.LocaleList
import android.app.LocaleManager
import com.abhinavxt.debforge.ui.pluralRes
import androidx.compose.ui.res.stringResource
import com.abhinavxt.debforge.R
import android.os.Environment
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.PasswordVisualTransformation
import android.os.Build
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import com.abhinavxt.debforge.data.provider.AccountInfo
import com.abhinavxt.debforge.download.ScheduleAlarm
import com.abhinavxt.debforge.download.StorageAccess
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.abhinavxt.debforge.domain.SortOrder
import com.abhinavxt.debforge.domain.ThemeMode
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val dir by viewModel.downloadDir.collectAsStateWithLifecycle()
    val sort by viewModel.sortDefault.collectAsStateWithLifecycle()
    val theme by viewModel.themeMode.collectAsStateWithLifecycle()
    val providers by viewModel.providers.collectAsStateWithLifecycle()
    val dynamicColor by viewModel.dynamicColor.collectAsStateWithLifecycle()
    val tmdbKey by viewModel.tmdbKey.collectAsStateWithLifecycle()
    val account by viewModel.account.collectAsStateWithLifecycle()
    val organize by viewModel.organizeLibrary.collectAsStateWithLifecycle()
    val rules by viewModel.rules.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val activeInfo = providers.firstOrNull { it.active }?.info
    val activeName = providers.firstOrNull { it.active }?.info?.displayName
        ?: stringResource(R.string.set_service_fallback)

    var showFolderDialog by remember { mutableStateOf(false) }
    var showSignOutDialog by remember { mutableStateOf(false) }

    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.tab_settings)) }) }) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            item { SectionHeader(stringResource(R.string.set_download_folder)) }
            item {
                FolderRow(
                    path = dir,
                    onChange = { showFolderDialog = true }
                )
            }
            item {
                SwitchRow(
                    label = stringResource(R.string.set_organise),
                    detail = stringResource(R.string.set_organise_detail),
                    checked = organize,
                    onChange = viewModel::setOrganizeLibrary
                )
            }
            item { HorizontalDivider() }

            item { SectionHeader(stringResource(R.string.set_when)) }
            item {
                SwitchRow(
                    label = stringResource(R.string.set_wifi_only),
                    detail = stringResource(R.string.set_wifi_only_detail),
                    checked = rules.wifiOnly,
                    onChange = viewModel::setWifiOnly
                )
            }
            item {
                SwitchRow(
                    label = stringResource(R.string.set_charging_only),
                    detail = null,
                    checked = rules.chargingOnly,
                    onChange = viewModel::setChargingOnly
                )
            }
            item {
                SwitchRow(
                    label = stringResource(R.string.set_schedule),
                    detail = stringResource(R.string.set_schedule_detail),
                    checked = rules.scheduleEnabled,
                    onChange = { viewModel.setSchedule(it, rules.scheduleStartHour, rules.scheduleEndHour) }
                )
            }
            if (rules.scheduleEnabled) {
                item {
                    ScheduleRow(
                        start = rules.scheduleStartHour,
                        end = rules.scheduleEndHour,
                        onChange = { st, en -> viewModel.setSchedule(true, st, en) }
                    )
                }
                if (!ScheduleAlarm.canBeExact(context)) {
                    item {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
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
            }
            item {
                SpeedLimitRow(current = rules.speedLimitKbps, onPick = viewModel::setSpeedLimit)
            }
            item { HorizontalDivider() }

            item { SectionHeader(stringResource(R.string.set_default_sort)) }
            items(SortOrder.entries) { option ->
                ChoiceRow(
                    label = option.label(),
                    selected = option == sort,
                    onClick = { viewModel.setSortDefault(option) }
                )
            }
            item { HorizontalDivider() }

            item { SectionHeader(stringResource(R.string.set_theme)) }
            items(ThemeMode.entries) { option ->
                ChoiceRow(
                    label = option.label(),
                    selected = option == theme,
                    onClick = { viewModel.setThemeMode(option) }
                )
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                item {
                    SwitchRow(
                        label = stringResource(R.string.set_wallpaper_colours),
                        detail = stringResource(R.string.set_wallpaper_detail),
                        checked = dynamicColor,
                        onChange = viewModel::setDynamicColor
                    )
                }
            }
            // Language sits with the other appearance options (Android 13+).
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                item { LanguageRow() }
            }
            item { HorizontalDivider() }

            item { SectionHeader(stringResource(R.string.set_posters)) }
            item {
                TmdbKeyRow(
                    current = tmdbKey,
                    onSave = viewModel::setTmdbKey,
                    onClearCache = viewModel::clearPosterCache
                )
            }
            item { HorizontalDivider() }

            item { SectionHeader(stringResource(R.string.set_service)) }
            item {
                AccountCard(
                    account = account,
                    serviceName = activeInfo?.displayName,
                    onManage = { activeInfo?.let { uriHandler.openUri(it.websiteUrl) } }
                )
            }
            items(providers, key = { it.info.id.name }) { row ->
                ChoiceRow(
                    label = row.info.displayName + "  ·  " +
                        stringResource(if (row.signedIn) R.string.set_signed_in else R.string.set_not_signed_in),
                    selected = row.active,
                    onClick = { viewModel.selectProvider(row.info.id) }
                )
            }
            item {
                Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    OutlinedButton(onClick = { showSignOutDialog = true }) {
                        Text(stringResource(R.string.set_sign_out_of, activeName))
                    }
                }
            }
            item { HorizontalDivider() }
            item { AboutSection() }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (showFolderDialog) {
        FolderPickerDialog(
            current = dir,
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
            title = { Text(stringResource(R.string.set_sign_out_q, activeName)) },
            text = { Text(stringResource(R.string.set_sign_out_body, activeName)) },
            confirmButton = {
                TextButton(onClick = {
                    showSignOutDialog = false
                    viewModel.signOut()
                }) { Text(stringResource(R.string.set_sign_out)) }
            },
            dismissButton = {
                TextButton(onClick = { showSignOutDialog = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
}

// --- rows --------------------------------------------------------------------

@Composable
internal fun SectionHeader(title: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun FolderRow(path: String, onChange: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = path,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.width(8.dp))
        OutlinedButton(onClick = onChange) { Text(stringResource(R.string.set_change)) }
    }
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

// --- folder picker dialog ----------------------------------------------------

@Composable
private fun FolderPickerDialog(
    current: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val appDir = remember { StorageAccess.appStorageDir(context) }
    val presets = remember { buildPresetFolders() + appDir }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.set_choose_folder)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.set_folder_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
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

@Composable
private fun ThemeMode.label(): String = stringResource(
    when (this) {
        ThemeMode.SYSTEM -> R.string.theme_system
        ThemeMode.LIGHT -> R.string.theme_light
        ThemeMode.DARK -> R.string.theme_dark
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
    val options = listOf(
        "" to stringResource(R.string.lang_system),
        "en" to "English",
        "hi" to "हिन्दी"
    )
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable { open = true }.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(stringResource(R.string.set_language), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Box {
            Text(
                options.firstOrNull { current.startsWith(it.first) && it.first.isNotEmpty() }?.second
                    ?: options.first().second,
                color = MaterialTheme.colorScheme.primary
            )
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { (tag, name) ->
                    DropdownMenuItem(text = { Text(name) }, onClick = {
                        open = false
                        // The system recreates the activity in the new language.
                        lm.applicationLocales =
                            if (tag.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
                    })
                }
            }
        }
    }
}

@Composable
internal fun SwitchRow(label: String, detail: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (detail != null) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/**
 * TMDB key entry. Masked like a password; saved only on "Save" so a
 * half-typed key doesn't trigger lookups.
 */
@Composable
private fun TmdbKeyRow(current: String, onSave: (String) -> Unit, onClearCache: () -> Unit) {
    var draft by remember(current) { mutableStateOf(current) }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            stringResource(R.string.set_tmdb_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            label = { Text(stringResource(R.string.set_tmdb_key)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        Row {
            TextButton(onClick = { onSave(draft) }, enabled = draft.trim() != current) { Text(stringResource(R.string.action_save)) }
            TextButton(onClick = onClearCache) { Text(stringResource(R.string.set_refresh_posters)) }
        }
    }
}

@Composable
private fun AccountCard(account: AccountInfo?, serviceName: String?, onManage: () -> Unit) {
    if (account == null) return
    val days = account.daysLeft
    val warn = days != null && days <= 7
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(account.displayName, style = MaterialTheme.typography.titleMedium)
            Text(
                listOfNotNull(serviceName, account.plan).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (days != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    when {
                        days < 0 -> stringResource(R.string.acct_expired)
                        days == 0L -> stringResource(R.string.acct_expires_today)
                        else -> pluralRes(R.plurals.acct_days_left, days.toInt(), days.toInt())
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (warn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
            }
            TextButton(onClick = onManage) {
                Text(stringResource(if (warn) R.string.action_renew else R.string.acct_manage))
            }
        }
    }
}

@Composable
private fun ScheduleRow(start: Int, end: Int, onChange: (Int, Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
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
        TextButton(onClick = { open = true }) { Text("%02d:00".format(hour)) }
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
    Row(
        Modifier.fillMaxWidth().clickable { open = true }.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.set_speed_limit), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(R.string.set_speed_detail),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Box {
            Text(speedLabel(current), color = MaterialTheme.colorScheme.primary)
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                SPEED_OPTIONS.forEach { k ->
                    DropdownMenuItem(text = { Text(speedLabel(k)) }, onClick = { onPick(k); open = false })
                }
            }
        }
    }
}
