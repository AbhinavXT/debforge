package com.abhinavxt.debforge.ui.player

import android.net.Uri
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.Crossfade
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
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
import coil.request.ImageRequest
import coil.imageLoader
import coil.memory.MemoryCache
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
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
        val artFile = track.artworkFile
        val context = LocalContext.current
        // Disk and cache lookups happen once per artwork file, not on every recomposition.
        val artUrl = track.artworkUrl
        val art = remember(artFile, artUrl) {
            // A local file wins; a streamed YouTube track uses its thumbnail URL.
            val source: Pair<Any, String>? = artFile?.takeIf { it.exists() }?.let { it to "art:${it.path}:${it.lastModified()}" }
                ?: artUrl?.let { it to "art:$it" }
            source?.let { (data, key) ->
                // One cache key at every size. The bitmap the mini player already decoded is read straight from
                // the memory cache and drawn underneath, so the big artwork has pixels on its very first frame.
                val bitmap = context.imageLoader.memoryCache?.get(MemoryCache.Key(key))?.bitmap
                Triple(
                    ImageRequest.Builder(context).data(data).memoryCacheKey(key).build(),
                    bitmap,
                    bitmap?.asImageBitmap()?.let { BitmapPainter(it) }
                )
            }
        }
        if (art == null) fallback() else {
            val (request, cached, painter) = art
            Box(Modifier.fillMaxSize()) {
                if (painter != null) Image(painter, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                SubcomposeAsyncImage(
                    model = request,
                    contentDescription = null,
                    loading = { if (cached == null) fallback() },
                    error = { fallback() },
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

/** The scopes the mini player and the full player need to share the artwork; null means no shared transition. */
@OptIn(ExperimentalSharedTransitionApi::class)
class ArtworkShare(val transition: SharedTransitionScope, val visibility: AnimatedVisibilityScope)

private const val ART_SHARE_MS = 350
private const val ART_MINI_DP = 44f
private const val ART_FULL_DP = 300f

/** Corner radius (dp) for artwork of the given width: the mini player's 12 growing to the full player's 24. */
internal fun artCornerDp(widthDp: Float): Float {
    val f = ((widthDp - ART_MINI_DP) / (ART_FULL_DP - ART_MINI_DP)).coerceIn(0f, 1f)
    return 12f + (24f - 12f) * f
}

/** No clip in the overlay: the artwork clips itself with [ArtShape], which follows the animated size. */
@OptIn(ExperimentalSharedTransitionApi::class)
private object NoOverlayClip : SharedTransitionScope.OverlayClip {
    override fun getClipPath(
        sharedContentState: SharedTransitionScope.SharedContentState,
        bounds: Rect,
        layoutDirection: LayoutDirection,
        density: Density
    ): Path? = null
}

/** Rounded corners that grow with the artwork's own width, so they animate cleanly during the flight. */
internal object ArtShape : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val r = artCornerDp(size.width / density.density) * density.density
        return Outline.Rounded(RoundRect(0f, 0f, size.width, size.height, CornerRadius(r)))
    }
}

/**
 * Marks this artwork as the shared element for [trackId]. [visible] is managed by the caller so both
 * sides stay composed for the whole flight. Put it before the clip, so the clip applies in place and not in the overlay.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun Modifier.sharedArtwork(scope: SharedTransitionScope?, trackId: String, visible: Boolean): Modifier {
    if (scope == null) return this
    return with(scope) {
        this@sharedArtwork.sharedElementWithCallerManagedVisibility(
            rememberSharedContentState("art-$trackId"),
            visible,
            boundsTransform = { _, _ -> tween(ART_SHARE_MS, easing = FastOutSlowInEasing) },
            clipInOverlayDuringTransition = NoOverlayClip
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
    Crossfade(playing, label = "playPause") {
        // The size goes on the icon: on the Crossfade it left a 24dp icon in the box's top corner.
        Icon(
            if (it) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
            contentDescription = stringResource(if (it) R.string.audio_pause else R.string.audio_play),
            modifier = modifier
        )
    }
}

/** 64dp bar above the navigation bar: artwork, title, play/pause, next; tap to expand. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun MiniPlayer(
    state: AudioState,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
    /** The shared transition scope; null when no shared transition runs. */
    sharedScope: SharedTransitionScope? = null,
    /** True while the full player is open: the artwork then lives there and this slot stays empty. */
    expanded: Boolean = false,
    /** Receives focus when the full player closes, so D-pad users land back here. */
    expandFocus: androidx.compose.ui.focus.FocusRequester? = null
) {
    val track = state.track ?: return
    val shape = RoundedCornerShape(16.dp)
    val expandLabel = stringResource(R.string.audio_expand)
    val barSource = remember { MutableInteractionSource() }
    // The row is a focus target around the buttons; arrows do not enter it on their own, so route them.
    val rowFocus = expandFocus ?: remember { FocusRequester() }
    val toggleFocus = remember { FocusRequester() }
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).animateContentSize(),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(Modifier.focusRing(barSource, shape)) {
            Row(
                Modifier.fillMaxWidth().height(60.dp)
                    .focusRequester(rowFocus)
                    .focusProperties { right = toggleFocus }
                    .clickable(interactionSource = barSource, indication = androidx.compose.material3.ripple(), onClickLabel = expandLabel, onClick = onExpand)
                    .padding(start = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (sharedScope == null) TrackArt(track, Modifier.size(44.dp).clip(ArtShape))
                else TrackArt(
                    track,
                    Modifier.size(44.dp).sharedArtwork(sharedScope, track.id, visible = !expanded).clip(ArtShape)
                )
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
                IconButton(onToggle, Modifier.size(48.dp).focusRequester(toggleFocus).focusProperties { left = rowFocus }.focusRing(s1, RoundedCornerShape(24.dp)), interactionSource = s1) {
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

@OptIn(ExperimentalSharedTransitionApi::class)
@Preview(showBackground = true)
@Composable
private fun MiniPlayerPreview() = DebforgeTheme { MiniPlayer(previewState, {}, {}, {}) }
