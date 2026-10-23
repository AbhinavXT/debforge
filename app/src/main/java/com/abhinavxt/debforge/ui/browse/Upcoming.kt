package com.abhinavxt.debforge.ui.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.abhinavxt.debforge.R
import com.abhinavxt.debforge.data.metadata.MediaMeta
import com.abhinavxt.debforge.domain.MediaKind
import com.abhinavxt.debforge.domain.ReleaseInfo
import com.abhinavxt.debforge.domain.Upcoming
import com.abhinavxt.debforge.ui.components.GeneratedArt
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** "Coming up": the next episode of each followed show and when it airs. */
@Composable
fun UpcomingRow(
    entries: List<Upcoming>,
    meta: Map<String, MediaMeta?>,
    onRequestMeta: (ReleaseInfo) -> Unit
) {
    if (entries.isEmpty()) return
    val today = remember { LocalDate.now() }
    val dateFormat = remember {
        val locale = Locale.getDefault()
        DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(locale, "EEEdMMM"), locale)
    }
    Column(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        Text(
            stringResource(R.string.upcoming_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(end = 16.dp)
        ) {
            items(entries, key = { it.showKey }) { entry ->
                val info = remember(entry.title) {
                    ReleaseInfo(MediaKind.SHOW, entry.title, null, null, null, null, null, null, null)
                }
                LaunchedEffect(info.groupKey) { onRequestMeta(info) }
                val days = entry.next.daysFrom(today)
                val day = when {
                    days <= 0 -> stringResource(R.string.upcoming_today)
                    days == 1L -> stringResource(R.string.upcoming_tomorrow)
                    else -> entry.next.airDate.format(dateFormat)
                }
                UpcomingCard(entry.title, "${entry.next.episodeLabel} · $day", meta[info.groupKey], entry.next.episodeLabel)
            }
        }
    }
}

@Composable
private fun UpcomingCard(title: String, subtitle: String, meta: MediaMeta?, tag: String) {
    Column(Modifier.width(160.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(MaterialTheme.shapes.medium)
        ) {
            val image = meta?.backdropUrl ?: meta?.posterUrl
            if (image != null) {
                AsyncImage(model = image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                GeneratedArt(title = title, tag = tag, modifier = Modifier.fillMaxSize())
            }
        }
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp, start = 2.dp)
        )
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.padding(start = 2.dp)
        )
    }
}
