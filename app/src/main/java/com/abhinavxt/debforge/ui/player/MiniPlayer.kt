package com.abhinavxt.debforge.ui.player

import com.abhinavxt.debforge.ui.video.pastSlop
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
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
import androidx.compose.material.icons.rounded.Close
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.absoluteOffset
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import com.abhinavxt.debforge.download.ArtworkProbe
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
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
import com.abhinavxt.debforge.ui.components.channelLink
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
        val fileKey by produceState(artFile?.let { ArtworkProbe.cachedKey(it) }, artFile) {
            value = artFile?.let { ArtworkProbe.key(it) }
        }
        val art = remember(artFile, artUrl, fileKey) {
            // A local file wins; a streamed YouTube track uses its thumbnail URL.
            val source: Pair<Any, String>? = artFile?.let { file -> fileKey?.let { file to it } }
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
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    /** Continues this item as video; the button shows only when the track can be watched. */
    onWatch: (() -> Unit)? = null,
    watchBusy: Boolean = false,
    /** Opens the artist's channel page: (name, url). Only offered for a track that has a channel url. */
    onChannel: (String, String) -> Unit = { _, _ -> },
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
    val linkFocus = remember { FocusRequester() }
    val hasLink = !track.artist.isNullOrBlank() && track.artistUrl != null
    // Swiping the bar away to either side stops playback and clears the queue. The offset is our own
    // Animatable, so it is never NaN (a fresh Material swipe state is until its anchors are set).
    // Live drag position is plain state so each delta is visible to onDragStopped at once; the Animatable only settles.
    var offsetPx by remember { mutableFloatStateOf(0f) }
    var widthPx by remember { mutableFloatStateOf(0f) }
    var dismissing by remember { mutableStateOf(false) }
    val anim = remember { object { var job: Job? = null } }
    val scope = rememberCoroutineScope()
    val latestClose by rememberUpdatedState(onClose)
    val animateOn = animatorDurationScale(LocalContext.current) > 0f
    val flingVelocity = with(LocalDensity.current) { DISMISS_FLING_DP.dp.toPx() }
    LaunchedEffect(track.id) {
        // A committed dismiss still closes; only a finished one (or none) is reset for the new track.
        if (shouldResetOnTrackChange(dismissing, anim.job?.isActive == true)) {
            dismissing = false
            offsetPx = 0f
        }
    }
    fun release(velocity: Float) {
        val width = widthPx
        if (dismissing || width <= 0f) return
        val dir = miniPlayerDismissDirection(offsetPx, velocity, width, flingVelocity)
        if (dir == 0) {
            anim.job = scope.launch {
                if (animateOn) {
                    Animatable(offsetPx).animateTo(
                        0f, spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
                        initialVelocity = velocity
                    ) { offsetPx = value }
                } else offsetPx = 0f
            }
        } else {
            dismissing = true
            anim.job = scope.launch {
                try {
                    if (animateOn) {
                        Animatable(offsetPx).animateTo(
                            dir * width, spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium),
                            initialVelocity = if (velocity * dir > 0f) velocity else 0f
                        ) { offsetPx = value }
                    } else offsetPx = dir * width
                } finally {
                    withContext(NonCancellable) { latestClose() }
                }
            }
        }
    }
    val slop = LocalViewConfiguration.current.touchSlop
    Box(
        modifier
            .onSizeChanged { widthPx = it.width.toFloat() }
            // Watches in the Initial pass, before the buttons: a drag past touch slop is the bar's and cancels their
            // press (consumed), however fast it is and whatever it started on. Taps are left alone.
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    if (dismissing || widthPx <= 0f) return@awaitEachGesture
                    var total = Offset.Zero
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                        if (!change.pressed) return@awaitEachGesture
                        total += change.positionChange()
                        if (total.getDistance() >= slop) {
                            if (abs(total.x) <= abs(total.y)) return@awaitEachGesture
                            change.consume()
                            break
                        }
                    }
                    anim.job?.cancel()
                    val tracker = VelocityTracker()
                    tracker.addPosition(down.uptimeMillis, down.position)
                    offsetPx += pastSlop(total.x, slop)
                    var last = down
                    var cancelled = false
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null) { cancelled = true; break }
                        tracker.addPosition(change.uptimeMillis, change.position)
                        last = change
                        change.consume()
                        if (!change.pressed) break
                        offsetPx += change.positionChange().x
                    }
                    var v = if (cancelled) 0f else tracker.calculateVelocity().x
                    // A very quick flick has few samples; its average speed is then the better estimate.
                    val ms = last.uptimeMillis - down.uptimeMillis
                    if (!cancelled && ms in 1..250) {
                        val avg = (last.position.x - down.position.x) / ms * 1000f
                        if (abs(avg) > abs(v)) v = avg
                    }
                    release(v)
                }
            }
            // Drag deltas are physical, so the offset must not be mirrored in right-to-left layouts.
            .graphicsLayer {
                val x = offsetPx.takeIf { it.isFinite() } ?: 0f
                translationX = x
                alpha = if (widthPx > 0f) (1f - 0.5f * (abs(x) / widthPx)).coerceIn(0.4f, 1f) else 1f
            }
    ) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).animateContentSize(),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(Modifier.focusRing(barSource, shape)) {
            Row(
                Modifier.fillMaxWidth().height(60.dp)
                    .focusRequester(rowFocus)
                    .focusProperties { right = if (hasLink) linkFocus else toggleFocus }
                    .clickable(interactionSource = barSource, indication = androidx.compose.material3.ripple(), onClickLabel = expandLabel, onClick = onExpand)
                    .padding(start = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Once the track changes while the full player is open (a swipe, Next), this bar stops offering its artwork
                // for sharing until the player closes, even if the track comes back: a hidden partner here would make the full
                // player's new artwork fly in from this bar instead of staying put. On close it is added as the full one hides,
                // so the closing flight still runs.
                val openedOn = remember(expanded) { track.id }
                val changedWhileOpen = remember(expanded) { booleanArrayOf(false) }
                if (track.id != openedOn) changedWhileOpen[0] = true
                val share = !expanded || !changedWhileOpen[0]
                if (sharedScope == null) TrackArt(track, Modifier.size(44.dp).clip(ArtShape))
                else TrackArt(
                    track,
                    Modifier.size(44.dp).then(if (share) Modifier.sharedArtwork(sharedScope, track.id, visible = !expanded) else Modifier).clip(ArtShape)
                )
                Crossfade(track, Modifier.weight(1f), label = "track") { t ->
                    Column {
                        Text(t.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val artistUrl = t.artistUrl?.takeIf { !t.artist.isNullOrBlank() }
                        Text(
                            t.artist ?: stringResource(R.string.audio_unknown_artist),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = if (artistUrl != null && t.artist != null) Modifier.channelLink(stringResource(R.string.yt_open_channel, t.artist), focusRequester = linkFocus, focusOrder = { left = rowFocus; right = toggleFocus }) { onChannel(t.artist, artistUrl) } else Modifier
                        )
                    }
                }
                val s1 = remember { MutableInteractionSource() }
                IconButton(onToggle, Modifier.size(48.dp).focusRequester(toggleFocus).focusProperties { left = if (hasLink) linkFocus else rowFocus }.focusRing(s1, RoundedCornerShape(24.dp)), interactionSource = s1) {
                    PlayPauseIcon(state.isPlaying)
                }
                if (onWatch != null && track.canWatch) WatchVideoButton(watchBusy, onWatch)
                val s2 = remember { MutableInteractionSource() }
                IconButton(onNext, Modifier.size(48.dp).focusRing(s2, RoundedCornerShape(24.dp)), enabled = state.hasNext, interactionSource = s2) {
                    Icon(Icons.Rounded.SkipNext, stringResource(R.string.audio_next))
                }
                val s3 = remember { MutableInteractionSource() }
                IconButton(onClose, Modifier.size(48.dp).focusRing(s3, RoundedCornerShape(24.dp)), interactionSource = s3) {
                    Icon(Icons.Rounded.Close, stringResource(R.string.audio_stop_close))
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
private fun MiniPlayerPreview() = DebforgeTheme { MiniPlayer(previewState, {}, {}, {}, {}) }
