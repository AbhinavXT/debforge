@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.abhinavxt.debforge.ui.settings

import android.app.LocaleManager
import android.os.Build
import android.os.LocaleList
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.AutoDelete
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.WorkspacePremium
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.abhinavxt.debforge.BuildConfig
import com.abhinavxt.debforge.R
import com.abhinavxt.debforge.data.prefs.STORAGE_LIMITS_GB
import com.abhinavxt.debforge.data.provider.AccountInfo
import com.abhinavxt.debforge.domain.DefaultQuality
import com.abhinavxt.debforge.domain.ExpiryReminder
import com.abhinavxt.debforge.domain.Housekeeping
import com.abhinavxt.debforge.domain.SortOrder
import com.abhinavxt.debforge.download.SafPaths
import com.abhinavxt.debforge.download.StorageAccess
import com.abhinavxt.debforge.ui.browse.formatSize
import com.abhinavxt.debforge.ui.components.RowDivider
import com.abhinavxt.debforge.ui.components.SearchPill
import com.abhinavxt.debforge.ui.components.SectionCard
import com.abhinavxt.debforge.ui.components.SettingRow
import com.abhinavxt.debforge.ui.components.StatusPill
import com.abhinavxt.debforge.ui.pluralRes
import com.abhinavxt.debforge.ui.posters.PosterSetupSheet
import kotlinx.coroutines.flow.first

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onConnect: () -> Unit, onBack: (() -> Unit)? = null, viewModel: SettingsViewModel = hiltViewModel()) {
    val dir by viewModel.downloadDir.collectAsStateWithLifecycle()
    val providers by viewModel.providers.collectAsStateWithLifecycle()
    val autoDeleteConfirm by viewModel.autoDeleteConfirm.collectAsStateWithLifecycle()
    val follows by viewModel.follows.collectAsStateWithLifecycle()
    val channels by viewModel.channels.collectAsStateWithLifecycle()
    val podcasts by viewModel.podcasts.collectAsStateWithLifecycle()
    var posterSetupOpen by remember { mutableStateOf(false) }
    var followsOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current
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
    val actions = SettingsActions(
        onConnect = onConnect,
        showSignOut = { showSignOutDialog = true },
        showFolderPicker = { showFolderDialog = true },
        showFollows = { followsOpen = true },
        showPosterSetup = { posterSetupOpen = true }
    )

    // The open category (null = the category list) and the search text survive rotation and process death.
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    // Where Back from a sub-page puts D-pad focus again.
    var returnTo by remember { mutableStateOf<SettingsCategory?>(null) }
    var pendingScroll by remember { mutableStateOf<Pair<SettingsCategory, Int>?>(null) }
    val rowFocus = remember { SettingsCategory.entries.associateWith { FocusRequester() } }
    val pageFocus = remember { FocusRequester() }
    val categoryListState = rememberLazyListState()

    // Large title that shrinks into the bar as the list scrolls, with the back arrow beside it.
    // Fresh bar state when the window changes size or density: a collapse offset measured for the old
    // size can exceed the new bar height and crash the layout.
    val cfg = androidx.compose.ui.platform.LocalConfiguration.current
    val barState = remember(cfg.densityDpi, cfg.screenWidthDp, cfg.screenHeightDp, cfg.fontScale) {
        androidx.compose.material3.TopAppBarState(-Float.MAX_VALUE, 0f, 0f)
    }
    val scrollBehavior = androidx.compose.material3.TopAppBarDefaults.exitUntilCollapsedScrollBehavior(barState)
    val target by SettingsTarget.section.collectAsStateWithLifecycle()
    // The bar publishes its collapse limit when it is first measured; until then it is -Float.MAX_VALUE.
    // Keep the offset inside [limit, 0] whenever the limit changes so the bar never measures below its collapsed height.
    LaunchedEffect(scrollBehavior) {
        snapshotFlow { scrollBehavior.state.heightOffsetLimit }.collect { limit ->
            if (limit > -Float.MAX_VALUE / 2 && limit <= 0f) {
                scrollBehavior.state.heightOffset = scrollBehavior.state.heightOffset.coerceIn(limit, 0f)
            }
        }
    }
    // A fresh page starts under a full-size title.
    LaunchedEffect(selectedKey) { scrollBehavior.state.heightOffset = 0f }
    LaunchedEffect(target) {
        val category = SettingsCategory.forTarget(target)
        if (category != null) {
            selectedKey = category.key
            query = ""
            pendingScroll = category to SEARCH_SOURCES_ITEM
        }
        // Always consumed, so a stale target cannot fire again.
        if (target != null) SettingsTarget.section.value = null
    }

    val wide = cfg.screenWidthDp >= 840 && cfg.screenHeightDp >= 480 // not a phone on its side
    val searching = query.isNotBlank()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            androidx.compose.material3.LargeTopAppBar(
                title = {
                    val open = SettingsCategory.fromKey(selectedKey).takeIf { !wide }
                    Text(
                        stringResource(open?.title ?: R.string.tab_settings),
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    val back = if (selectedKey != null && !wide) ({ selectedKey = null }) else onBack
                    if (back != null) {
                        IconButton(onClick = back) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.shell_cd_back))
                        }
                    }
                },
                scrollBehavior = scrollBehavior
            )
        }
    ) { padding ->
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.padding(padding).fillMaxSize()) {
            val open = SettingsCategory.fromKey(selectedKey)
            // Wide: categories and options side by side, so one is always open. Narrow: one pane.
            val shown = if (wide) open ?: SettingsCategory.ACCOUNT else open
            // Back from a sub-page leaves it for the list; from search results it clears the search.
            // Disabled otherwise, so HomeScreen's own handler for pushed routes still works.
            BackHandler(enabled = (!wide && open != null) || searching) {
                if (!wide && open != null) selectedKey = null else query = ""
            }
            LaunchedEffect(shown, wide) {
                if (wide) return@LaunchedEffect
                withFrameNanos { }
                if (shown != null) {
                    runCatching { pageFocus.requestFocus() }
                } else returnTo?.let {
                    runCatching { rowFocus.getValue(it).requestFocus() }
                    returnTo = null
                }
            }
            val openCategory = { c: SettingsCategory ->
                returnTo = c
                query = ""
                selectedKey = c.key
            }
            val categoryList: @Composable (Modifier) -> Unit = { modifier ->
                CategoryList(
                    viewModel = viewModel,
                    query = query,
                    onQuery = { query = it },
                    selected = if (wide) shown else null,
                    rowFocus = rowFocus,
                    onOpen = openCategory,
                    state = categoryListState,
                    modifier = modifier
                )
            }
            val page: @Composable (SettingsCategory, Modifier, Dp) -> Unit = { category, modifier, side ->
                key(category) {
                    val state = rememberLazyListState()
                    LaunchedEffect(pendingScroll) {
                        pendingScroll?.takeIf { it.first == category }?.let {
                            state.scrollToItem(it.second)
                            pendingScroll = null
                        }
                    }
                    LazyColumn(
                        state = state,
                        modifier = modifier.fillMaxSize().focusRequester(pageFocus).focusGroup(),
                        contentPadding = PaddingValues(start = side, end = side, bottom = 32.dp)
                    ) {
                        settingsPage(category, viewModel, actions)
                    }
                }
            }
            when {
                wide -> Row(Modifier.fillMaxSize()) {
                    categoryList(Modifier.width(360.dp))
                    page(shown!!, Modifier.weight(1f), 0.dp)
                }
                shown == null -> categoryList(Modifier.fillMaxSize())
                // A readable width, centred, so a switch stays near its label.
                else -> page(shown, Modifier, ((maxWidth - 640.dp) / 2).coerceAtLeast(0.dp))
            }
        }
    }

    // Nothing left to list after the last unfollow: close the dialog.
    LaunchedEffect(follows.isEmpty() && channels.isEmpty() && podcasts.isEmpty()) { if (follows.isEmpty() && channels.isEmpty() && podcasts.isEmpty()) followsOpen = false }
    if (followsOpen) {
        val multiService = follows.map { it.provider }.distinct().size > 1
        AlertDialog(
            onDismissRequest = { followsOpen = false },
            icon = { Icon(Icons.Rounded.NotificationsActive, contentDescription = null) },
            title = { Text(stringResource(R.string.set_following)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (follows.isNotEmpty()) Text(
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
                    listOf(R.string.set_following_channels to channels, R.string.set_following_podcasts to podcasts).forEach { (sectionTitle, subs) ->
                      val isPodcasts = subs === podcasts
                      if (subs.isNotEmpty()) {
                        if (follows.isNotEmpty() || (isPodcasts && channels.isNotEmpty())) Spacer(Modifier.height(16.dp))
                        Text(stringResource(sectionTitle), style = MaterialTheme.typography.titleSmall)
                        subs.forEach { c ->
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(c.title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                                TextButton(onClick = { viewModel.unfollowChannel(c.id) }) { Text(stringResource(R.string.follow_unfollow)) }
                            }
                            Text(stringResource(R.string.set_following_auto), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(
                                    com.abhinavxt.debforge.data.subscriptions.AutoDownload.OFF to R.string.set_auto_dl_off,
                                    com.abhinavxt.debforge.data.subscriptions.AutoDownload.AUDIO to R.string.set_auto_dl_audio,
                                    com.abhinavxt.debforge.data.subscriptions.AutoDownload.VIDEO to R.string.set_auto_dl_video,
                                ).filter { !isPodcasts || it.first != com.abhinavxt.debforge.data.subscriptions.AutoDownload.VIDEO }.forEach { (mode, label) ->
                                    androidx.compose.material3.FilterChip(
                                        selected = c.autoDownload == mode,
                                        onClick = { viewModel.setChannelAutoDownload(c.id, mode) },
                                        label = { Text(stringResource(label)) },
                                        modifier = Modifier.heightIn(min = 48.dp),
                                    )
                                }
                            }
                        }
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

@Composable
private fun CategoryList(
    viewModel: SettingsViewModel,
    query: String,
    onQuery: (String) -> Unit,
    selected: SettingsCategory?,
    rowFocus: Map<SettingsCategory, FocusRequester>,
    onOpen: (SettingsCategory) -> Unit,
    state: LazyListState,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val results = remember(query) { searchSettings(query) { context.getString(it) } }
    LazyColumn(state = state, modifier = modifier, contentPadding = PaddingValues(bottom = 32.dp)) {
        item(key = "search") {
            SearchPill(
                query = query,
                onQuery = onQuery,
                placeholder = stringResource(R.string.settings_search_hint),
                clearDescription = stringResource(R.string.media_search_clear),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
        if (query.isBlank()) {
            item(key = "categories") {
                SectionCard {
                    SettingsCategory.entries.forEachIndexed { i, category ->
                        if (i > 0) RowDivider()
                        Box(
                            Modifier
                                .focusRequester(rowFocus.getValue(category))
                                .focusGroup()
                                .semantics { if (selected != null) this.selected = category == selected }
                                .background(
                                    if (category == selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
                                )
                        ) {
                            SettingRow(
                                icon = category.icon(),
                                title = stringResource(category.title),
                                subtitle = categorySummary(category, viewModel),
                                onClick = { onOpen(category) },
                                trailing = {
                                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null)
                                }
                            )
                        }
                    }
                }
            }
        } else {
            if (results.isEmpty()) {
                item(key = "none") {
                    Text(
                        stringResource(R.string.settings_search_none),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 28.dp, vertical = 16.dp)
                    )
                }
            } else {
                item(key = "results") {
                    SectionCard {
                        results.forEachIndexed { i, entry ->
                            if (i > 0) RowDivider()
                            SettingRow(
                                icon = entry.category.icon(),
                                title = stringResource(entry.title),
                                subtitle = stringResource(entry.category.title) +
                                    (entry.detail?.let { " · " + stringResource(it) } ?: ""),
                                onClick = { onOpen(entry.category) }
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun SettingsCategory.icon() = when (this) {
    SettingsCategory.ACCOUNT -> Icons.Rounded.Person
    SettingsCategory.APPEARANCE -> Icons.Rounded.Palette
    SettingsCategory.HOME -> Icons.Rounded.Home
    SettingsCategory.DOWNLOADS -> Icons.Rounded.Download
    SettingsCategory.VIDEO -> Icons.Rounded.Movie
    SettingsCategory.AUDIO -> Icons.Rounded.Headphones
    SettingsCategory.YOUTUBE -> Icons.Rounded.PlayCircle
    SettingsCategory.CONNECTIONS -> Icons.Rounded.Link
    SettingsCategory.DATA -> Icons.Rounded.Backup
    SettingsCategory.ABOUT -> Icons.Rounded.Info
}

/** The one-line live summary under a category title; null where a static title says enough. */
@Composable
private fun categorySummary(category: SettingsCategory, viewModel: SettingsViewModel): String? = when (category) {
    SettingsCategory.ACCOUNT -> {
        val providers by viewModel.providers.collectAsStateWithLifecycle()
        val account by viewModel.account.collectAsStateWithLifecycle()
        val active = providers.firstOrNull { it.active }
        val days = account?.daysLeft
        listOfNotNull(
            active?.info?.displayName,
            when {
                active == null || !active.signedIn -> stringResource(R.string.set_not_signed_in)
                days == null -> null
                days < 0 -> stringResource(R.string.acct_expired)
                days == 0L -> stringResource(R.string.acct_expires_today)
                else -> pluralRes(R.plurals.acct_days_left, days.toInt(), days.toInt())
            }
        ).joinToString(" · ")
    }
    SettingsCategory.APPEARANCE -> {
        val mode by viewModel.themeMode.collectAsStateWithLifecycle()
        mode.label()
    }
    SettingsCategory.HOME -> {
        val sort by viewModel.sortDefault.collectAsStateWithLifecycle()
        sort.label()
    }
    SettingsCategory.DOWNLOADS -> {
        val rules by viewModel.rules.collectAsStateWithLifecycle()
        val info by viewModel.storageInfo.collectAsStateWithLifecycle()
        listOfNotNull(
            stringResource(R.string.set_wifi_only).takeIf { rules.wifiOnly },
            info?.freeBytes?.let { stringResource(R.string.set_storage_free, formatSize(it)) }
        ).joinToString(" · ").ifEmpty { null }
    }
    SettingsCategory.VIDEO -> {
        val internal by viewModel.internalPlayer.collectAsStateWithLifecycle()
        stringResource(if (internal) R.string.set_player_internal else R.string.set_player_external)
    }
    SettingsCategory.AUDIO -> {
        val seconds by viewModel.audioSkipSeconds.collectAsStateWithLifecycle()
        stringResource(R.string.set_skip_length) + " · " + stringResource(R.string.skip_seconds, seconds)
    }
    SettingsCategory.YOUTUBE -> {
        val q by viewModel.youTubeQuality.collectAsStateWithLifecycle()
        when (q) {
            DefaultQuality.ASK -> stringResource(R.string.set_yt_quality_ask)
            DefaultQuality.BEST -> stringResource(R.string.set_yt_quality_best)
            DefaultQuality.AUDIO -> stringResource(R.string.set_yt_quality_audio)
            else -> "${q.height}p"
        }
    }
    SettingsCategory.CONNECTIONS -> stringResource(R.string.settings_sum_connections)
    SettingsCategory.DATA -> stringResource(R.string.settings_sum_data)
    SettingsCategory.ABOUT -> stringResource(R.string.about_version, BuildConfig.VERSION_NAME)
}


// --- rows --------------------------------------------------------------------

@Composable
internal fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .heightIn(min = 48.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

/** A service in the list: radio, name, signed-in pill. */
@Composable
internal fun ProviderChoiceRow(name: String, signedIn: Boolean, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        // Wraps: with a large font the pill goes under the name instead of breaking it mid-word.
        FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.align(Alignment.CenterVertically).padding(end = 8.dp))
            val cs = MaterialTheme.colorScheme
            StatusPill(
                text = stringResource(if (signedIn) R.string.set_signed_in else R.string.set_not_signed_in),
                modifier = Modifier.align(Alignment.CenterVertically),
                container = if (signedIn) cs.primaryContainer else cs.surfaceContainerHighest,
                content = if (signedIn) cs.onPrimaryContainer else cs.onSurfaceVariant,
                icon = if (signedIn) Icons.Rounded.Check else null
            )
        }
    }
}

// --- folder picker dialog ----------------------------------------------------

@Composable
internal fun FolderPickerDialog(
    current: String,
    onPickAny: () -> Unit,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val appDir = remember { StorageAccess.appStorageDir(context) }
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
                ChoiceRow(
                    label = stringResource(R.string.set_app_storage) + "\n" + appDir,
                    selected = appDir == current,
                    onClick = { onPick(appDir) }
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_done)) } }
    )
}

// --- labels ------------------------------------------------------------------

@Composable
internal fun SortOrder.label(): String = stringResource(
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
internal fun LanguageRow() {
    val context = LocalContext.current
    val lm = remember { context.getSystemService(LocaleManager::class.java) }
    val current = remember { lm.applicationLocales.toLanguageTags() }
    // Tags must match res/xml/locales_config.xml. Names are shown in their own
    // language so anyone can find theirs whatever the app is set to.
    val options = listOf("" to stringResource(R.string.lang_system)) + LANGUAGES
    val currentName = options.firstOrNull { current.startsWith(it.first) && it.first.isNotEmpty() }?.second
        ?: options.first().second
    var open by remember { mutableStateOf(false) }
    SettingRow(
        icon = Icons.Rounded.Language,
        title = stringResource(R.string.set_language),
        subtitle = currentName,
        onClick = { open = true }
    )
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(stringResource(R.string.set_language)) },
            text = {
                // 15 choices: scrolls inside the dialog.
                Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                    options.forEach { (tag, name) ->
                        ChoiceRow(label = name, selected = name == currentName, onClick = {
                            open = false
                            // The system recreates the activity in the new language.
                            lm.applicationLocales =
                                if (tag.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
                        })
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.action_cancel)) } }
        )
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
internal fun SortRow(current: SortOrder, onPick: (SortOrder) -> Unit) {
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

/**
 * Player audio / subtitle language: the file's default, a language, or (for
 * subtitles) off. The player also remembers what you pick per show.
 */
@Composable
internal fun LanguageRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    current: String,
    offOption: Boolean,
    onPick: (String) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    @Composable
    fun label(v: String) = when (v) {
        com.abhinavxt.debforge.player.tracks.TrackPicker.AUTO -> stringResource(R.string.set_lang_auto)
        com.abhinavxt.debforge.player.tracks.TrackPicker.OFF -> stringResource(R.string.player_subtitles_off)
        else -> com.abhinavxt.debforge.player.tracks.PlayerLanguages.displayName(v)
    }
    val options = buildList {
        add(com.abhinavxt.debforge.player.tracks.TrackPicker.AUTO)
        if (offOption) add(com.abhinavxt.debforge.player.tracks.TrackPicker.OFF)
        addAll(com.abhinavxt.debforge.player.tracks.PlayerLanguages.CODES)
    }
    Box {
        SettingRow(icon = icon, title = title, subtitle = label(current), onClick = { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { v ->
                DropdownMenuItem(
                    text = { Text(label(v)) },
                    leadingIcon = { if (v == current) Icon(Icons.Rounded.Check, contentDescription = null) },
                    onClick = { onPick(v); open = false }
                )
            }
        }
    }
}

/** "Play videos in": DebForge's player or another app (VLC, mpv…). */
@Composable
internal fun PlayerRow(internal: Boolean, onPick: (Boolean) -> Unit) {
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

/** YouTube "Default quality": the preview preselects it; the user can still change it. */
@Composable
internal fun YouTubeQualityRow(current: DefaultQuality, onPick: (DefaultQuality) -> Unit) {
    var open by remember { mutableStateOf(false) }
    @Composable
    fun label(q: DefaultQuality): String = when (q) {
        DefaultQuality.ASK -> stringResource(R.string.set_yt_quality_ask)
        DefaultQuality.BEST -> stringResource(R.string.set_yt_quality_best)
        DefaultQuality.AUDIO -> stringResource(R.string.set_yt_quality_audio)
        else -> "${q.height}p"
    }
    Box {
        SettingRow(
            icon = Icons.Rounded.HighQuality,
            title = stringResource(R.string.set_yt_quality),
            subtitle = label(current) + " · " + stringResource(R.string.set_yt_quality_detail),
            onClick = { open = true }
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DefaultQuality.entries.forEach { q ->
                DropdownMenuItem(
                    text = { Text(label(q)) },
                    leadingIcon = { if (q == current) Icon(Icons.Rounded.Check, contentDescription = null) },
                    onClick = { onPick(q); open = false }
                )
            }
        }
    }
}

/** "Delete downloads after": Never / 7 / 14 / 30 / 60 days. */
@Composable
internal fun AutoDeleteRow(current: Int, onPick: (Int) -> Unit) {
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

@Composable
internal fun ExpiryReminderRow(current: Int, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    @Composable
    fun label(days: Int): String =
        if (days <= 0) stringResource(R.string.set_expiry_off)
        else pluralRes(R.plurals.set_expiry_days_before, days, days)
    Box {
        SettingRow(
            icon = Icons.Rounded.WorkspacePremium,
            title = stringResource(R.string.set_expiry_reminder),
            subtitle = label(current),
            onClick = { open = true }
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ExpiryReminder.CHOICES.forEach { days ->
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
internal fun TmdbKeyRow(
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
    FlowRow(
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
internal fun AccountHeader(account: AccountInfo?, serviceName: String?, onManage: () -> Unit) {
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
        // Wraps: with a large font the button goes under the pill instead of being squeezed.
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            if (days != null) {
                StatusPill(
                    modifier = Modifier.align(Alignment.CenterVertically),
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
internal fun ScheduleRow(start: Int, end: Int, onChange: (Int, Int) -> Unit) {
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
internal fun HourPicker(hour: Int, onPick: (Int) -> Unit) {
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

@Composable
internal fun SkipLengthRow(current: Int, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        SettingRow(
            icon = Icons.Rounded.Forward10,
            title = stringResource(R.string.set_skip_length),
            subtitle = stringResource(R.string.set_skip_length_detail),
            onClick = { open = true },
            trailing = {
                Text(
                    stringResource(R.string.skip_seconds, current),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            com.abhinavxt.debforge.player.SKIP_LENGTHS.forEach { n ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.skip_seconds, n)) },
                    leadingIcon = { if (n == current) Icon(Icons.Rounded.Check, contentDescription = null) },
                    onClick = { onPick(n); open = false }
                )
            }
        }
    }
}

@Composable
internal fun NotificationButtonsRow(current: com.abhinavxt.debforge.player.NotificationButtons, onPick: (com.abhinavxt.debforge.player.NotificationButtons) -> Unit) {
    var open by remember { mutableStateOf(false) }
    @Composable
    fun label(b: com.abhinavxt.debforge.player.NotificationButtons) = stringResource(
        when (b) {
            com.abhinavxt.debforge.player.NotificationButtons.SKIP -> R.string.set_notif_skip
            com.abhinavxt.debforge.player.NotificationButtons.FAVOURITE_SLEEP -> R.string.set_notif_fav_sleep
            com.abhinavxt.debforge.player.NotificationButtons.FAVOURITE_SPEED -> R.string.set_notif_fav_speed
            com.abhinavxt.debforge.player.NotificationButtons.SLEEP_SPEED -> R.string.set_notif_sleep_speed
        }
    )
    Box {
        SettingRow(
            icon = Icons.Rounded.Forward10,
            title = stringResource(R.string.set_notification_buttons),
            subtitle = stringResource(R.string.set_notification_buttons_detail),
            onClick = { open = true },
            trailing = { Text(label(current), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            com.abhinavxt.debforge.player.NotificationButtons.entries.forEach { b ->
                DropdownMenuItem(
                    text = { Text(label(b)) },
                    leadingIcon = { if (b == current) Icon(Icons.Rounded.Check, contentDescription = null) },
                    onClick = { onPick(b); open = false }
                )
            }
        }
    }
}

private val SPEED_OPTIONS = listOf(0, 1024, 2 * 1024, 5 * 1024, 10 * 1024, 25 * 1024, 50 * 1024)

@Composable
internal fun speedLabel(kbps: Int) =
    if (kbps <= 0) stringResource(R.string.speed_unlimited) else stringResource(R.string.speed_mbps, kbps / 1024)

@Composable
internal fun storageLimitLabel(gb: Int) =
    if (gb <= 0) stringResource(R.string.set_storage_limit_off) else stringResource(R.string.set_storage_limit_gb, gb)

@Composable
internal fun StorageLimitRow(current: Int, usedBytes: Long, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val context = LocalContext.current
    Box {
        SettingRow(
            icon = Icons.Rounded.Storage,
            title = stringResource(R.string.set_storage_limit),
            subtitle = stringResource(R.string.set_storage_limit_detail) + "\n" +
                stringResource(R.string.set_storage_used, android.text.format.Formatter.formatShortFileSize(context, usedBytes)),
            onClick = { open = true },
            trailing = {
                Text(
                    storageLimitLabel(current),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            STORAGE_LIMITS_GB.forEach { g ->
                DropdownMenuItem(
                    text = { Text(storageLimitLabel(g)) },
                    leadingIcon = { if (g == current) Icon(Icons.Rounded.Check, contentDescription = null) },
                    onClick = { onPick(g); open = false }
                )
            }
        }
    }
}

@Composable
internal fun SpeedLimitRow(current: Int, onPick: (Int) -> Unit) {
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
