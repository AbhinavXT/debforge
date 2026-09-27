package com.abhinavxt.debforge.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.DataUsage
import androidx.compose.material3.Icon
import com.abhinavxt.debforge.ui.components.SectionCard
import com.abhinavxt.debforge.ui.components.SettingRow
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.abhinavxt.debforge.R
import com.abhinavxt.debforge.data.repository.AuthRepository
import com.abhinavxt.debforge.data.usage.DayUsage
import com.abhinavxt.debforge.data.usage.UsageDao
import com.abhinavxt.debforge.data.usage.UsageSummary
import com.abhinavxt.debforge.ui.browse.formatSize
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class DataUsageViewModel @Inject constructor(
    private val dao: UsageDao,
    private val auth: AuthRepository
) : ViewModel() {

    /** Re-reads when the date changes so "this month" and the chart roll over. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val summary: StateFlow<UsageSummary?> = flow {
        while (true) {
            emit(LocalDate.now())
            delay(60_000)
        }
    }
        .distinctUntilChanged()
        .flatMapLatest { today ->
            dao.observeSince(UsageSummary.fromDay(today).toString()).map { UsageSummary.summarize(it, today) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun serviceName(id: com.abhinavxt.debforge.domain.ProviderId): String = auth.info(id).displayName

    fun reset() = viewModelScope.launch { dao.clear() }
}

/** Settings row: "12.4 GB this month · Mobile 2.3 GB", tap for details. */
@Composable
fun DataUsageSection(viewModel: DataUsageViewModel = hiltViewModel()) {
    val summary by viewModel.summary.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    val s = summary

    SectionCard(title = stringResource(R.string.usage_title)) {
        SettingRow(
            icon = Icons.Rounded.DataUsage,
            title = stringResource(R.string.usage_this_month, fmt(s?.monthTotal ?: 0)),
            subtitle = stringResource(R.string.usage_split, fmt(s?.monthWifi ?: 0), fmt(s?.monthMobile ?: 0)),
            onClick = if (s != null) ({ open = true }) else null,
            trailing = if (s != null) ({
                Icon(
                    Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }) else null
        )
    }
    if (open && s != null) UsageSheet(s, viewModel) { open = false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UsageSheet(s: UsageSummary, vm: DataUsageViewModel, onDismiss: () -> Unit) {
    var confirmReset by remember { mutableStateOf(false) }
    val wifiColor = MaterialTheme.colorScheme.primary
    val mobileColor = MaterialTheme.colorScheme.tertiary

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
            Text(stringResource(R.string.usage_title), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.usage_this_month, fmt(s.monthTotal)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Legend(wifiColor, stringResource(R.string.usage_wifi), fmt(s.monthWifi))
                Legend(mobileColor, stringResource(R.string.usage_mobile), fmt(s.monthMobile))
            }
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.usage_last_days, UsageSummary.DAYS), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            DayBars(s.lastDays, wifiColor, mobileColor)
            Row(Modifier.fillMaxWidth()) {
                Text(shortDate(s.lastDays.first().day), style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                Text(stringResource(R.string.usage_today), style = MaterialTheme.typography.labelSmall)
            }
            if (s.monthByProvider.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                Text(stringResource(R.string.usage_by_service), style = MaterialTheme.typography.titleSmall)
                s.monthByProvider.forEach { (p, bytes) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Text(vm.serviceName(p), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Text(fmt(bytes), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = { confirmReset = true }) {
                Text(stringResource(R.string.usage_reset), color = MaterialTheme.colorScheme.error)
            }
        }
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(stringResource(R.string.usage_reset_q)) },
            confirmButton = {
                TextButton(onClick = { vm.reset(); confirmReset = false }) { Text(stringResource(R.string.usage_reset)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
}

@Composable
private fun Legend(color: Color, label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(color, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text("$label  $value", style = MaterialTheme.typography.bodyMedium)
    }
}

/** Stacked bars (Wi-Fi below, mobile on top), one per day, scaled to the busiest day. */
@Composable
private fun DayBars(days: List<DayUsage>, wifi: Color, mobile: Color) {
    val max = (days.maxOfOrNull { it.total } ?: 0L).coerceAtLeast(1L)
    val track = MaterialTheme.colorScheme.surfaceVariant
    val summaryText = stringResource(R.string.usage_chart_desc, days.size, fmt(days.maxOf { it.total }))
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(120.dp)
            .semantics { contentDescription = summaryText }
    ) {
        val gap = 2.dp.toPx()
        val barW = (size.width - gap * (days.size - 1)) / days.size
        days.forEachIndexed { i, d ->
            val x = i * (barW + gap)
            val wifiH = size.height * d.wifi / max
            val mobileH = size.height * d.mobile / max
            // Faint baseline so empty days still read as days.
            drawRect(track, Offset(x, size.height - 2f), Size(barW, 2f))
            if (wifiH > 0) drawRoundRect(wifi, Offset(x, size.height - wifiH), Size(barW, wifiH), CornerRadius(2f, 2f))
            if (mobileH > 0) drawRoundRect(mobile, Offset(x, size.height - wifiH - mobileH), Size(barW, mobileH), CornerRadius(2f, 2f))
        }
    }
}

private fun shortDate(d: LocalDate): String =
    "${d.dayOfMonth} ${d.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())}"

/** Like formatSize, but "0 B" instead of "—" for nothing downloaded. */
private fun fmt(bytes: Long): String = if (bytes <= 0) "0 B" else formatSize(bytes)

