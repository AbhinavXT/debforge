package com.abhinavxt.debforge.ui.search

import androidx.compose.foundation.horizontalScroll
import com.abhinavxt.debforge.domain.SearchKind
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Close
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.abhinavxt.debforge.R
import com.abhinavxt.debforge.domain.SearchQuality
import com.abhinavxt.debforge.domain.SearchResult
import com.abhinavxt.debforge.ui.browse.formatSize
import com.abhinavxt.debforge.ui.components.MessageState
import com.abhinavxt.debforge.ui.components.ScreenHeader
import com.abhinavxt.debforge.ui.components.SearchPill
import com.abhinavxt.debforge.ui.components.StatusPill
import com.abhinavxt.debforge.ui.pluralRes

/**
 * Search tab: type a title, pick a result, and the Add dialog takes it from
 * there ([onAdd] gets the magnet). Without a search source it explains what
 * one is and offers [onOpenSettings].
 */
@Composable
fun SearchScreen(
    onAdd: (String) -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: SearchViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val available by viewModel.available.collectAsStateWithLifecycle()
    val recent by viewModel.recent.collectAsStateWithLifecycle()
    val followOffer by viewModel.followOffer.collectAsStateWithLifecycle()
    val keyboard = LocalSoftwareKeyboardController.current

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(title = stringResource(R.string.tab_search))
        when (available) {
            null -> return@Column
            false -> {
                MessageState(
                    icon = Icons.Rounded.Search,
                    title = stringResource(R.string.search_needs_source_title),
                    body = stringResource(R.string.search_needs_source_body),
                    action = { Button(onClick = onOpenSettings) { Text(stringResource(R.string.search_open_settings)) } }
                )
                return@Column
            }
            true -> Unit
        }

        SearchPill(
            query = state.query,
            onQuery = viewModel::onQueryChange,
            placeholder = stringResource(R.string.search_hint),
            clearDescription = stringResource(R.string.action_clear),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            onSearch = {
                keyboard?.hide()
                viewModel.submit()
            }
        )
        KindRow(
            kind = state.kind,
            onKind = viewModel::onKind,
            season = state.season,
            onSeason = viewModel::onSeason,
            episode = state.episode,
            onEpisode = viewModel::onEpisode
        )
        val showRecent = state.query.isBlank() && recent.isNotEmpty() && !state.loading
        if (state.results.isNotEmpty() && !showRecent) {
            if (state.checkingCache || state.failures.isNotEmpty()) {
                Notes(state.checkingCache, state.failures)
            }
            Filters(
                quality = state.quality,
                onQuality = viewModel::onQuality,
                cachedOnly = state.cachedOnly,
                onToggleCachedOnly = viewModel::onToggleCachedOnly
            )
        }

        val shown = state.shown
        when {
            state.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            showRecent -> RecentList(
                recent = recent,
                onRun = { keyboard?.hide(); viewModel.runRecent(it) },
                onRemove = viewModel::removeRecent,
                onClear = viewModel::clearRecent
            )
            state.results.isEmpty() && state.failures.isNotEmpty() -> MessageState(
                icon = Icons.Rounded.CloudOff,
                title = state.failures.first(),
                body = state.failures.drop(1).joinToString("\n").ifEmpty { null },
                tone = MaterialTheme.colorScheme.error,
                action = {
                    FilledTonalButton(onClick = viewModel::submit) {
                        Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(R.string.action_retry))
                    }
                }
            )
            state.searched == null -> MessageState(
                icon = Icons.Rounded.Search,
                title = stringResource(R.string.search_start_title),
                body = stringResource(R.string.search_start_body)
            )
            state.results.isEmpty() -> MessageState(
                icon = Icons.Rounded.SearchOff,
                title = stringResource(R.string.search_nothing_found, state.searched!!),
                body = null,
                tone = MaterialTheme.colorScheme.onSurfaceVariant
            )
            shown.isEmpty() -> MessageState(
                icon = Icons.Rounded.SearchOff,
                title = stringResource(R.string.search_nothing_filtered),
                body = null,
                tone = MaterialTheme.colorScheme.onSurfaceVariant
            )
            else -> LazyColumn(
                contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                followOffer?.let { offer ->
                    item(key = "follow") { FollowCard(offer, onToggle = { viewModel.toggleFollow(offer) }) }
                }
                items(shown, key = { it.hash }) { result ->
                    ResultRow(result, onClick = { onAdd(result.magnet) })
                }
            }
        }
    }
}

/** All / Movies / Shows, and for shows the season and episode. */
@Composable
private fun KindRow(
    kind: SearchKind,
    onKind: (SearchKind) -> Unit,
    season: Int?,
    onSeason: (Int?) -> Unit,
    episode: Int?,
    onEpisode: (Int?) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SearchKind.entries.forEach { k ->
            FilterChip(
                selected = kind == k,
                onClick = { onKind(k) },
                label = {
                    Text(stringResource(when (k) {
                        SearchKind.ALL -> R.string.search_kind_all
                        SearchKind.MOVIE -> R.string.search_kind_movies
                        SearchKind.SHOW -> R.string.search_kind_shows
                    }))
                }
            )
        }
        if (kind == SearchKind.SHOW) {
            NumberPicker(
                value = season,
                range = 1..MAX_SEASON,
                anyLabel = stringResource(R.string.search_season_any),
                label = { stringResource(R.string.search_season, it) },
                onPick = onSeason
            )
            if (season != null) {
                NumberPicker(
                    value = episode,
                    range = 1..MAX_EPISODE,
                    anyLabel = stringResource(R.string.search_episode_any),
                    label = { stringResource(R.string.search_episode, it) },
                    onPick = onEpisode
                )
            }
        }
    }
}

/** A chip that opens "Any, 1, 2, …"; null = any. */
@Composable
private fun NumberPicker(
    value: Int?,
    range: IntRange,
    anyLabel: String,
    label: @Composable (Int) -> String,
    onPick: (Int?) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = value != null,
            onClick = { expanded = true },
            trailingIcon = { Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(18.dp)) },
            label = { Text(if (value == null) anyLabel else label(value)) }
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(anyLabel) },
                leadingIcon = { if (value == null) Icon(Icons.Rounded.Check, contentDescription = null) },
                onClick = { onPick(null); expanded = false }
            )
            range.forEach { n ->
                DropdownMenuItem(
                    text = { Text(label(n)) },
                    leadingIcon = { if (value == n) Icon(Icons.Rounded.Check, contentDescription = null) },
                    onClick = { onPick(n); expanded = false }
                )
            }
        }
    }
}

/** Recent searches while the box is empty: tap to run one again. */
@Composable
private fun RecentList(recent: List<String>, onRun: (String) -> Unit, onRemove: (String) -> Unit, onClear: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item(key = "header") {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.search_recent),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onClear) { Text(stringResource(R.string.search_clear_recent)) }
            }
        }
        items(recent, key = { it }) { q ->
            Surface(onClick = { onRun(q) }, color = Color.Transparent, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.History, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.size(16.dp))
                    Text(q, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    IconButton(onClick = { onRemove(q) }) {
                        Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.search_remove_recent, q))
                    }
                }
            }
        }
    }
}

/** "Follow The Bear": new episodes are then found and added to the service by themselves. */
@Composable
private fun FollowCard(offer: FollowOffer, onToggle: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        color = cs.secondaryContainer,
        contentColor = cs.onSecondaryContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Row(Modifier.padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(offer.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    stringResource(
                        if (offer.following) R.string.search_following_body else R.string.search_follow_body,
                        offer.serviceName
                    ),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Spacer(Modifier.size(12.dp))
            if (offer.following) {
                OutlinedButton(onClick = onToggle) { Text(stringResource(R.string.follow_unfollow)) }
            } else {
                Button(onClick = onToggle) { Text(stringResource(R.string.follow_action)) }
            }
        }
    }
}

/** "Checking cache" while the services answer; a line per source that failed. */
@Composable
private fun Notes(checkingCache: Boolean, failures: List<String>) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (checkingCache) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(8.dp))
                Text(
                    stringResource(R.string.search_checking_cache),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        failures.forEach {
            Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun Filters(
    quality: SearchQuality,
    onQuality: (SearchQuality) -> Unit,
    cachedOnly: Boolean,
    onToggleCachedOnly: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FilterChip(
            selected = cachedOnly,
            onClick = onToggleCachedOnly,
            leadingIcon = { Icon(Icons.Rounded.Bolt, contentDescription = null, modifier = Modifier.size(18.dp)) },
            label = { Text(stringResource(R.string.search_cached_only)) }
        )
        var expanded by remember { mutableStateOf(false) }
        Box {
            FilterChip(
                selected = quality != SearchQuality.ANY,
                onClick = { expanded = true },
                trailingIcon = { Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(18.dp)) },
                label = { Text(quality.label()) }
            )
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                SearchQuality.entries.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.label()) },
                        leadingIcon = { if (option == quality) Icon(Icons.Rounded.Check, contentDescription = null) },
                        onClick = { onQuality(option); expanded = false }
                    )
                }
            }
        }
    }
}

private const val MAX_SEASON = 30
private const val MAX_EPISODE = 50

@Composable
private fun SearchQuality.label(): String = when (this) {
    SearchQuality.ANY -> stringResource(R.string.search_any_quality)
    SearchQuality.UHD -> "4K"
    SearchQuality.FULL_HD -> "1080p"
    SearchQuality.HD -> "720p"
    SearchQuality.SD -> "SD"
}

/** One result: release name, size · seeders · age · tracker, and what's known about it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ResultRow(result: SearchResult, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        color = cs.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Row(Modifier.padding(start = 14.dp, end = 10.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(result.title, style = MaterialTheme.typography.bodyLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                val details = listOfNotNull(
                    formatSize(result.size).takeIf { result.size > 0 },
                    result.seeders?.let { pluralRes(R.plurals.search_seeders, it, it) },
                    result.age,
                    result.tracker
                ).joinToString(" · ")
                if (details.isNotEmpty()) {
                    Text(details, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                val quality = result.info.qualityLabel
                if (result.cached == true || result.owned == true || quality.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (result.cached == true) {
                            StatusPill(
                                stringResource(R.string.search_cached),
                                icon = Icons.Rounded.Bolt,
                                container = cs.tertiaryContainer,
                                content = cs.onTertiaryContainer
                            )
                        }
                        if (result.owned == true) StatusPill(stringResource(R.string.search_in_library), icon = Icons.Rounded.Check)
                        if (quality.isNotEmpty()) {
                            StatusPill(quality, container = cs.surfaceContainerHighest, content = cs.onSurfaceVariant)
                        }
                    }
                }
            }
            Icon(
                Icons.Rounded.Add,
                contentDescription = stringResource(R.string.action_add),
                tint = cs.primary,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }
}
