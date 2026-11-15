package com.abhinavxt.debforge.ui.player

import android.net.Uri
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import com.abhinavxt.debforge.R
import com.abhinavxt.debforge.player.AudioState
import com.abhinavxt.debforge.player.AudioTrack
import com.abhinavxt.debforge.ui.components.GeneratedArt
import com.abhinavxt.debforge.ui.theme.DebforgeTheme

/** Artwork with the generated gradient while loading and when there is none. Decorative. */
@Composable
internal fun TrackArt(track: AudioTrack, modifier: Modifier = Modifier) {
    val fallback = @Composable { GeneratedArt(title = track.title, tag = "", showTitle = false, modifier = Modifier.fillMaxSize()) }
    Box(modifier.clearAndSetSemantics { }) {
        val file = track.artworkFile?.takeIf { it.exists() }
        if (file == null) fallback() else SubcomposeAsyncImage(
            model = file,
            contentDescription = null,
            loading = { fallback() },
            error = { fallback() },
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
    }
}

/** Focus ring for D-pad users. */
@Composable
internal fun Modifier.focusRing(source: MutableInteractionSource, shape: androidx.compose.ui.graphics.Shape): Modifier {
    val focused = source.collectIsFocusedAsState().value
    return if (focused) border(2.dp, MaterialTheme.colorScheme.primary, shape) else this
}

@Composable
internal fun PlayPauseIcon(playing: Boolean, modifier: Modifier = Modifier) {
    Crossfade(playing, modifier, label = "playPause") {
        Icon(
            if (it) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
            contentDescription = stringResource(if (it) R.string.audio_pause else R.string.audio_play)
        )
    }
}

/** 64dp bar above the navigation bar: artwork, title, play/pause, next; tap to expand. */
@Composable
fun MiniPlayer(
    state: AudioState,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier
) {
    val track = state.track ?: return
    val shape = RoundedCornerShape(16.dp)
    val expandLabel = stringResource(R.string.audio_expand)
    val barSource = remember { MutableInteractionSource() }
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).animateContentSize(),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(Modifier.focusRing(barSource, shape)) {
            Row(
                Modifier.fillMaxWidth().height(60.dp)
                    .clickable(interactionSource = barSource, indication = androidx.compose.material3.ripple(), onClickLabel = expandLabel, onClick = onExpand)
                    .padding(start = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TrackArt(track, Modifier.size(44.dp).clip(MaterialTheme.shapes.small))
                Crossfade(track, Modifier.weight(1f), label = "track") { t ->
                    Column {
                        Text(t.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            t.artist ?: stringResource(R.string.audio_unknown_artist),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                val s1 = remember { MutableInteractionSource() }
                IconButton(onToggle, Modifier.size(48.dp).focusRing(s1, RoundedCornerShape(24.dp)), interactionSource = s1) {
                    PlayPauseIcon(state.isPlaying)
                }
                val s2 = remember { MutableInteractionSource() }
                IconButton(onNext, Modifier.size(48.dp).focusRing(s2, RoundedCornerShape(24.dp)), enabled = state.hasNext, interactionSource = s2) {
                    Icon(Icons.Rounded.SkipNext, stringResource(R.string.audio_next))
                }
            }
            LinearProgressIndicator(
                progress = { if (state.durationMs > 0) (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f },
                modifier = Modifier.fillMaxWidth().height(2.dp),
                trackColor = MaterialTheme.colorScheme.outlineVariant,
                drawStopIndicator = {}
            )
        }
    }
}

internal val previewState = AudioState(
    queue = listOf(
        AudioTrack("1", Uri.EMPTY, "Midnight City", "M83", null),
        AudioTrack("2", Uri.EMPTY, "A Very Long Track Title That Must Not Wrap Onto A Second Line", "Some Artist", null),
        AudioTrack("3", Uri.EMPTY, "Intro", null, null)
    ),
    index = 0, isPlaying = true, positionMs = 95_000, durationMs = 243_000
)

@Preview(showBackground = true)
@Composable
private fun MiniPlayerPreview() = DebforgeTheme { MiniPlayer(previewState, {}, {}, {}) }
