package com.abhinavxt.debforge.player.gestures

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BrightnessMedium
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abhinavxt.debforge.R
import com.abhinavxt.debforge.ui.pluralRes

/**
 * What the gestures show on screen. Plain Compose state the activity sets;
 * null = hidden. The overlay never takes touches (they go to the gestures).
 */
class GestureUiState {
    /** A run of double-tap seeks. */
    data class SeekTaps(val forward: Boolean, val seconds: Int)
    enum class LevelKind { BRIGHTNESS, VOLUME }
    /** [value] 0..[max]; volume goes past 1 (100 %) into the boost. */
    data class Level(val kind: LevelKind, val value: Float, val max: Float = 1f)
    data class Scrub(val targetMs: Long, val deltaMs: Long)

    var seekTaps by mutableStateOf<SeekTaps?>(null)
    var level by mutableStateOf<Level?>(null)
    var scrub by mutableStateOf<Scrub?>(null)
    /** Long-press speed while held. */
    var speed by mutableStateOf<Float?>(null)
    /** Zoom in percent while pinching / just after. */
    var zoomPercent by mutableStateOf<Int?>(null)
    /** A short note ("Crop", "Fit"). */
    var message by mutableStateOf<String?>(null)
}

private val Pill = Color(0xB3000000)

@Composable
fun GestureOverlay(state: GestureUiState) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val sideWidth = maxWidth * 0.35f

        // Double-tap seek: a soft half-oval over that side.
        val taps = state.seekTaps
        AnimatedVisibility(
            visible = taps != null && !taps.forward,
            enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.CenterStart)
        ) {
            SeekSide(taps?.seconds ?: 0, forward = false, width = sideWidth)
        }
        AnimatedVisibility(
            visible = taps != null && taps.forward,
            enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.CenterEnd)
        ) {
            SeekSide(taps?.seconds ?: 0, forward = true, width = sideWidth)
        }

        // Brightness / volume.
        state.level?.let { level ->
            Row(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 48.dp)
                    .background(Pill, RoundedCornerShape(24.dp))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val icon = when {
                    level.kind == GestureUiState.LevelKind.BRIGHTNESS -> Icons.Rounded.BrightnessMedium
                    level.value <= 0f -> Icons.AutoMirrored.Rounded.VolumeOff
                    else -> Icons.AutoMirrored.Rounded.VolumeUp
                }
                Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(12.dp))
                LinearProgressIndicator(
                    progress = { (level.value / level.max).coerceIn(0f, 1f) },
                    modifier = Modifier.width(140.dp).height(6.dp),
                    // Past 100 %: the boost, in the accent colour.
                    color = if (level.value > 1f) androidx.compose.material3.MaterialTheme.colorScheme.primary else Color.White,
                    trackColor = Color.White.copy(alpha = 0.25f),
                    strokeCap = StrokeCap.Round
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    "${(level.value * 100).toInt()}%",
                    color = Color.White,
                    fontSize = 14.sp,
                    modifier = Modifier.width(48.dp)
                )
            }
        }

        // Scrubbing: where it lands and how far that is.
        state.scrub?.let { scrub ->
            Column(
                Modifier
                    .align(Alignment.Center)
                    .background(Pill, RoundedCornerShape(20.dp))
                    .padding(horizontal = 24.dp, vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(GestureMath.time(scrub.targetMs), color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text(GestureMath.delta(scrub.deltaMs), color = Color.White.copy(alpha = 0.8f), fontSize = 16.sp)
            }
        }

        // Long-press speed, with the other speeds to slide to.
        state.speed?.let { speed ->
            Column(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 48.dp)
                    .background(Pill, RoundedCornerShape(20.dp))
                    .padding(horizontal = 18.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.FastForward, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(GestureMath.speedLabel(speed), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GestureMath.SPEEDS.forEach { s ->
                        Text(
                            GestureMath.speedLabel(s),
                            color = if (s == speed) Color.White else Color.White.copy(alpha = 0.45f),
                            fontSize = 12.sp,
                            fontWeight = if (s == speed) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }
        }

        state.message?.let { text ->
            Text(
                text,
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 96.dp)
                    .background(Pill, RoundedCornerShape(20.dp))
                    .padding(horizontal = 18.dp, vertical = 10.dp)
            )
        }

        // Pinch zoom.
        state.zoomPercent?.let { zoom ->
            Row(
                Modifier
                    .align(Alignment.Center)
                    .background(Pill, RoundedCornerShape(20.dp))
                    .padding(horizontal = 18.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.ZoomIn, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text("$zoom%", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun SeekSide(seconds: Int, forward: Boolean, width: androidx.compose.ui.unit.Dp) {
    // Rounded on the side facing the middle of the screen.
    val shape = if (forward) RoundedCornerShape(topStartPercent = 50, bottomStartPercent = 50)
    else RoundedCornerShape(topEndPercent = 50, bottomEndPercent = 50)
    Box(
        Modifier
            .width(width)
            .fillMaxHeight()
            .background(Color.White.copy(alpha = 0.14f), shape),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                if (forward) Icons.Rounded.FastForward else Icons.Rounded.FastRewind,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(36.dp)
            )
            Spacer(Modifier.height(4.dp))
            Text(
                (if (forward) "+" else "−") + pluralRes(R.plurals.player_gesture_seek_n, seconds, seconds),
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
