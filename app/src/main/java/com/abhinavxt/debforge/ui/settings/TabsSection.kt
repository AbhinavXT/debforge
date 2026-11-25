package com.abhinavxt.debforge.ui.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Tab
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.abhinavxt.debforge.R
import com.abhinavxt.debforge.ui.components.RowDivider
import com.abhinavxt.debforge.ui.components.SectionCard
import com.abhinavxt.debforge.ui.home.HomeTab
import com.abhinavxt.debforge.ui.home.TabConfig

/**
 * Which tabs the phone's bottom bar shows and their order. Up and down buttons rather than dragging, so it
 * works with TalkBack and a D-pad. Tabs left out of the bar are listed after it and stay reachable elsewhere.
 */
@Composable
internal fun TabsCard(
    bar: List<String>,
    start: String,
    onBar: (List<String>) -> Unit,
    onStart: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    SectionCard(title = stringResource(R.string.set_tabs), modifier = modifier) {
        Text(
            stringResource(R.string.set_tabs_detail),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp)
        )
        TabConfig.rail(bar).mapNotNull(HomeTab::of).forEach { tab ->
            // Keyed so keyboard focus follows a tab as it moves.
            key(tab.route) {
                val label = stringResource(tab.labelRes)
                val index = bar.indexOf(tab.route)
                val inBar = index >= 0
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(tab.icon, contentDescription = null, modifier = Modifier.size(24.dp))
                    Text(
                        label,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(start = 16.dp)
                    )
                    if (inBar) {
                        IconButton(
                            onClick = { onBar(TabConfig.moved(bar, tab.route, -1)) },
                            enabled = index > 0
                        ) { Icon(Icons.Rounded.KeyboardArrowUp, stringResource(R.string.set_tab_up, label)) }
                        IconButton(
                            onClick = { onBar(TabConfig.moved(bar, tab.route, 1)) },
                            enabled = index < bar.lastIndex
                        ) { Icon(Icons.Rounded.KeyboardArrowDown, stringResource(R.string.set_tab_down, label)) }
                    }
                    val toggled = TabConfig.toggled(bar, tab.route)
                    Switch(
                        checked = inBar,
                        onCheckedChange = { toggled?.let(onBar) },
                        // At the minimum or maximum the switch cannot go that way.
                        enabled = toggled != null,
                        modifier = Modifier.semantics { contentDescription = label }
                    )
                }
            }
        }
        RowDivider()
        ChoiceRow(
            icon = Icons.Rounded.Tab,
            title = stringResource(R.string.set_start_tab),
            current = start,
            options = listOf(TabConfig.AUTO) + TabConfig.ALL,
            label = { k -> stringResource(HomeTab.of(k)?.labelRes ?: R.string.set_start_tab_auto) },
            onPick = onStart
        )
    }
}
