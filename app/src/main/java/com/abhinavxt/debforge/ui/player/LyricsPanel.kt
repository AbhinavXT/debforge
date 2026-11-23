package com.abhinavxt.debforge.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.abhinavxt.debforge.R
import com.abhinavxt.debforge.data.lyrics.LyricsResult
import com.abhinavxt.debforge.data.lyrics.lineAt
import com.abhinavxt.debforge.player.AudioTrack
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** Lyrics of [track], loaded on demand while shown: synced lines follow [positionMs] and a tap seeks there. */
@Composable
internal fun LyricsPanel(
    track: AudioTrack,
    positionMs: Long,
    durationMs: Long,
    load: suspend (title: String, artist: String?, durationMs: Long) -> LyricsResult,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    var retry by remember { mutableIntStateOf(0) }
    var result by remember(track.id) { mutableStateOf<LyricsResult?>(null) }
    val latestDuration by rememberUpdatedState(durationMs)
    // The duration shown at a track change still belongs to the previous track; it is trusted only on the first open or a retry.
    val seen = remember { arrayOf<String?>(null) }
    LaunchedEffect(track.id, retry) {
        result = null
        val stale = if (seen[0] != null && seen[0] != track.id) latestDuration else -1L
        seen[0] = track.id
        // The length picks the right record; wait briefly for the player to report this track's, else search without it.
        val len = withTimeoutOrNull(3000) { snapshotFlow { latestDuration }.first { it > 0 && it != stale } } ?: 0L
        result = load(track.title, track.artist, len)
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        when (val r = result) {
            null -> Message(stringResource(R.string.lyrics_loading))
            is LyricsResult.Synced -> {
                val pos by rememberUpdatedState(positionMs)
                val activeState = remember(r) { derivedStateOf { lineAt(r.lines, pos) } }
                val active by activeState
                val list = rememberLazyListState()
                val first = remember(r) { booleanArrayOf(true) }
                LaunchedEffect(active) {
                    if (active < 0) return@LaunchedEffect
                    val off = -(list.layoutInfo.viewportSize.height / 2 - 40)
                    if (first[0]) list.scrollToItem(active, off) else list.animateScrollToItem(active, off)
                    first[0] = false
                }
                LazyColumn(Modifier.fillMaxWidth(), list, contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 120.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    itemsIndexed(r.lines) { i, line ->
                        Text(
                            line.text.ifEmpty { "♪" },
                            Modifier.fillMaxWidth().clickable(role = Role.Button) { onSeek(line.timeMs) }.padding(vertical = 8.dp, horizontal = 8.dp),
                            color = if (i == active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = if (i == active) FontWeight.Bold else FontWeight.Normal,
                            style = MaterialTheme.typography.titleLarge,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
            is LyricsResult.Plain -> Text(
                r.text,
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(8.dp),
                style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center
            )
            LyricsResult.Instrumental -> Message(stringResource(R.string.lyrics_instrumental))
            LyricsResult.NotFound -> Message(stringResource(R.string.lyrics_none))
            LyricsResult.Failed -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Message(stringResource(R.string.lyrics_failed))
                TextButton({ retry++ }) { Text(stringResource(R.string.lyrics_retry)) }
            }
        }
    }
}

@Composable
private fun Message(text: String) =
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
