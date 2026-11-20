package com.abhinavxt.debforge.ui.video

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.abhinavxt.debforge.R
import com.abhinavxt.debforge.ui.player.focusRing

/** The mini player's video slot: its size (16:9 at the bar's height). The picture itself is the host's one surface, drawn over it. */
internal val MiniVideoWidth = 114.dp
internal val MiniBarHeight = 64.dp

/**
 * The small bar over the tabs (beside the rail on a tablet): the video, its title, play / pause and close.
 * The video is not drawn here: the bar only leaves a slot and tells [onVideoSlot] where it is (in the root's
 * coordinates) so the host's single surface can sit on it. A tap anywhere on the bar opens the watch page.
 */
@Composable
fun VideoMiniBar(
    title: String,
    playing: Boolean,
    progress: Float,
    onToggle: () -> Unit,
    onClose: () -> Unit,
    onExpand: () -> Unit,
    onVideoSlot: (Rect) -> Unit,
    modifier: Modifier = Modifier,
    toggleFocus: FocusRequester = remember { FocusRequester() },
) {
    val shape = RoundedCornerShape(16.dp)
    val barSource = remember { MutableInteractionSource() }
    val expandLabel = stringResource(R.string.video_mini_expand)
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Box(Modifier.focusRing(barSource, shape)) {
            Row(
                Modifier.fillMaxWidth().height(MiniBarHeight)
                    .clickable(interactionSource = barSource, indication = androidx.compose.material3.ripple(), onClickLabel = expandLabel, onClick = onExpand),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.width(MiniVideoWidth).height(MiniBarHeight).onGloballyPositioned { onVideoSlot(it.boundsInRoot()) })
                Text(
                    title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                )
                IconButton(onClick = onToggle, modifier = Modifier.focusRequester(toggleFocus)) {
                    Icon(
                        if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        stringResource(if (playing) R.string.action_pause else R.string.action_play),
                    )
                }
                IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, stringResource(R.string.action_close)) }
            }
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(2.dp).align(Alignment.BottomStart),
                trackColor = androidx.compose.ui.graphics.Color.Transparent,
            )
        }
    }
}

/** What the mini bar shows of the engine, polled while a video is shown. */
data class VideoPulse(val playing: Boolean = false, val progress: Float = 0f)

@Composable
fun rememberVideoPulse(session: com.abhinavxt.debforge.player.session.VideoSession, enabled: Boolean): VideoPulse {
    var pulse by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(VideoPulse()) }
    androidx.compose.runtime.LaunchedEffect(enabled) {
        while (enabled) {
            val p = session.player
            val duration = p?.duration?.takeIf { it > 0 && it != androidx.media3.common.C.TIME_UNSET }
            val next = VideoPulse(p?.playWhenReady == true && p.playbackState != androidx.media3.common.Player.STATE_ENDED, if (p != null && duration != null) p.currentPosition.toFloat() / duration else 0f)
            if (next != pulse) pulse = next
            kotlinx.coroutines.delay(500)
        }
    }
    return pulse
}
