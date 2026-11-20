package com.abhinavxt.debforge.player.controls

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abhinavxt.debforge.R
import com.abhinavxt.debforge.player.gestures.GestureMath
import com.abhinavxt.debforge.player.subtitles.SubtitleTiming
import java.util.Locale

/** "+2.3 s · 25→23.976", or null when there is no correction. [seconds] formats a number of seconds ("+2.3") with its unit. */
internal fun correctionText(summary: CorrectionSummary, seconds: (String) -> String): String? {
    if (summary.isIdentity) return null
    val parts = ArrayList<String>(2)
    if (summary.offsetMs != 0L || (summary.rate == null && summary.fpsPair == null)) {
        parts += seconds(String.format(Locale.US, "%+.1f", summary.offsetMs / 1000.0))
    }
    summary.fpsPair?.let { parts += SyncMath.presetLabel(it.first, it.second) }
    summary.rate?.let { parts += "×" + String.format(Locale.US, "%.4f", it) }
    return parts.joinToString(" · ")
}

/** The Subtitles panel's entry to the sync tools: the correction in force, and the way in. */
@Composable
internal fun SyncToolsEntry(state: ControlsState, actions: ControlsActions, open: () -> Unit) {
    val sub = remember(state.tracksVersion, state.panel, state.syncStep, state.subtitleDelayMs) { actions.syncSubtitle() }
    if (sub == null) {
        Text(
            stringResource(R.string.player_sync_tools_files_only),
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
        )
        return
    }
    val unit = stringResource(R.string.player_sync_value, "\u0000")
    val detail = correctionText(SyncMath.summary(sub.timing, state.subtitleDelayMs, state.snapshot.positionMs)) { unit.replace("\u0000", it) }
    SyncRow(stringResource(R.string.player_sync_tools) + " ›", detail, onClick = open)
}

@Composable
private fun SyncRow(title: String, detail: String? = null, enabled: Boolean = true, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Text(title, color = Color.White.copy(alpha = if (enabled) 1f else 0.4f), fontSize = 15.sp)
        if (detail != null) Text(detail, color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        color = Color.White.copy(alpha = 0.6f),
        fontSize = 12.sp,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
    )
}

/** The Sync tools panel: line, drift (frame-rate presets), two lines, reset. */
@Composable
internal fun SyncPanel(state: ControlsState, actions: ControlsActions) {
    var rev by remember { mutableIntStateOf(0) }
    val sub = remember(state.tracksVersion, rev) { actions.syncSubtitle() }
    if (sub == null) {
        Hint(stringResource(R.string.player_sync_tools_files_only))
        return
    }
    val applied = stringResource(R.string.player_sync_applied)
    val firstSet = stringResource(R.string.player_sync_first_set)
    val tooClose = stringResource(R.string.player_sync_too_close)
    val implausible = stringResource(R.string.player_sync_implausible)
    val resetDone = stringResource(R.string.player_sync_reset_done)

    fun apply(timing: SubtitleTiming, message: String) {
        actions.applySyncTiming(sub.key, timing)
        actions.showSyncMessage(message)
        rev++
        state.syncStep = SyncStep.MENU
    }

    when (state.syncStep) {
        SyncStep.MENU -> {
            val unit = stringResource(R.string.player_sync_value, "\u0000")
            val current = correctionText(SyncMath.summary(sub.timing, state.subtitleDelayMs, state.snapshot.positionMs)) { unit.replace("\u0000", it) }
            val first = state.twoLinesFirst?.takeIf { it.key == sub.key }
            LazyColumn {
                item {
                    Hint(current?.let { stringResource(R.string.player_sync_current, it) } ?: stringResource(R.string.player_sync_none))
                }
                item { SyncRow(stringResource(R.string.player_sync_line), stringResource(R.string.player_sync_line_hint)) { state.syncStep = SyncStep.LINE } }
                item { SyncRow(stringResource(R.string.player_sync_drift), stringResource(R.string.player_sync_drift_hint)) { state.syncStep = SyncStep.DRIFT } }
                if (first == null) {
                    item {
                        SyncRow(stringResource(R.string.player_sync_two_lines), stringResource(R.string.player_sync_two_lines_hint)) {
                            state.syncStep = SyncStep.FIRST_LINE
                        }
                    }
                } else {
                    item {
                        SyncRow(stringResource(R.string.player_sync_two_lines_second), stringResource(R.string.player_sync_two_lines_pending)) {
                            state.syncStep = SyncStep.SECOND_LINE
                        }
                    }
                    item { SyncRow(stringResource(R.string.player_sync_two_lines_cancel)) { state.twoLinesFirst = null } }
                }
                item {
                    SyncRow(
                        stringResource(R.string.player_sync_reset_timing),
                        enabled = !sub.timing.isIdentity || state.subtitleDelayMs != 0L
                    ) {
                        state.twoLinesFirst = null
                        apply(SubtitleTiming.IDENTITY, resetDone)
                    }
                }
            }
        }
        SyncStep.DRIFT -> {
            LazyColumn {
                item { Hint(stringResource(R.string.player_sync_drift_pick)) }
                items(SubtitleTiming.FPS_PRESETS) { (subFps, videoFps) ->
                    SyncRow(
                        SyncMath.presetLabel(subFps, videoFps),
                        stringResource(R.string.player_sync_preset_detail, SyncMath.fpsText(subFps), SyncMath.fpsText(videoFps))
                    ) {
                        val at = actions.snapshot().positionMs
                        apply(SyncMath.preset(sub.timing, state.subtitleDelayMs, subFps, videoFps, at), applied)
                    }
                }
            }
        }
        SyncStep.LINE, SyncStep.FIRST_LINE, SyncStep.SECOND_LINE -> PickLines(state, actions, sub) { line, position ->
            when (state.syncStep) {
                SyncStep.LINE ->
                    apply(SyncMath.tapLine(sub.timing, state.subtitleDelayMs, line, position), applied)
                SyncStep.FIRST_LINE -> {
                    state.twoLinesFirst = SyncMath.firstPoint(sub.key, line, position)
                    actions.showSyncMessage(firstSet)
                    state.syncStep = SyncStep.MENU
                }
                else -> {
                    val first = state.twoLinesFirst
                    if (first == null || first.key != sub.key) {
                        state.syncStep = SyncStep.MENU
                    } else {
                        val (fit, rejected) = SyncMath.secondPoint(first, line, position)
                        if (fit != null) {
                            state.twoLinesFirst = null
                            apply(fit, applied)
                        } else {
                            actions.showSyncMessage(if (rejected == FitRejection.TOO_CLOSE) tooClose else implausible)
                        }
                    }
                }
            }
        }
    }
}

/** Playback pauses while the lines within 20 s of now are listed; the current one is highlighted. */
@Composable
private fun PickLines(state: ControlsState, actions: ControlsActions, sub: SyncSubtitle, onPick: (SyncLine, Long) -> Unit) {
    var position by remember { mutableLongStateOf(-1L) }
    DisposableEffect(Unit) {
        actions.beginSyncPick()
        position = actions.snapshot().positionMs
        onDispose { actions.endSyncPick() }
    }
    if (position < 0) return
    val lines = remember(position, sub) { SyncMath.lines(actions.syncCues(sub.key), sub.timing, state.subtitleDelayMs, position) }
    val listState = rememberLazyListState()
    LaunchedEffect(lines) { listState.scrollToItem(if (lines.isEmpty()) 0 else SyncMath.focusIndex(lines, position) + 1) }
    val hint = when (state.syncStep) {
        SyncStep.FIRST_LINE -> R.string.player_sync_two_lines_first
        SyncStep.SECOND_LINE -> R.string.player_sync_two_lines_second_hint
        else -> R.string.player_sync_line_pick
    }
    LazyColumn(state = listState) {
        item { Hint(stringResource(hint)) }
        if (lines.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.player_sync_no_lines),
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                )
            }
        }
        items(lines) { line ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onPick(line, position) }
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    GestureMath.time(line.shownStartMs.coerceAtLeast(0)),
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    line.text,
                    color = if (line.current) MaterialTheme.colorScheme.primary else Color.White,
                    fontWeight = if (line.current) FontWeight.Bold else FontWeight.Normal,
                    fontSize = 14.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
