package com.abhinavxt.debforge.ui.browse

import androidx.compose.animation.core.RepeatMode
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.abhinavxt.debforge.R
import com.abhinavxt.debforge.domain.GridSize
import com.abhinavxt.debforge.domain.SortOrder
import com.abhinavxt.debforge.ui.components.SearchPill
import kotlin.math.roundToInt

/** Pure arithmetic of the collapsing header, kept free of Compose so it can be unit tested. */
internal object CollapseMath {
    /** [collapsed] is the new collapse; [consumed] is the part of the incoming delta taken, with its sign. */
    data class Step(val collapsed: Float, val consumed: Float)

    /**
     * Content moving up (dy < 0) collapses the header; moving down (dy > 0) expands it.
     * [canScroll] false means the list has nothing to scroll forward: never collapse then,
     * though an expanding drag is still honoured.
     */
    fun step(collapsed: Float, maxCollapse: Float, dy: Float, canScroll: Boolean = true): Step {
        val max = maxCollapse.coerceAtLeast(0f)
        val current = collapsed.coerceIn(0f, max)
        if (dy < 0f && !canScroll) return Step(current, 0f)
        val target = (current - dy).coerceIn(0f, max)
        // Collapsing by x consumes -x: the consumed amount carries the sign of dy.
        return Step(target, current - target)
    }
}

/**
 * Scroll state for the Library: how far the title block has collapsed, and
 * whether the grid is being scrolled down (the FAB then shrinks). Fed by
 * nested scroll from whichever list sits below; [TrackLibraryScroll] adds the
 * exact top / cannot-scroll rule from that list's real scroll state.
 */
class LibraryScrollState {
    var collapsed by mutableFloatStateOf(0f)
    var maxCollapse by mutableFloatStateOf(0f)
    var scrollingDown by mutableStateOf(false)

    /** True while the header is not on screen (multi-select); the collapse is then left alone. */
    var suspended = false
    /** Number of screens (empty, error, skeleton) currently holding the header open. */
    internal var holds = 0
    internal var canScroll = true
    internal var lastDragNanos = 0L

    private val active get() = !suspended && holds == 0

    /** Header fully open and the FAB expanded: the list is at its top (or was reset). */
    fun expand() {
        collapsed = 0f
        scrollingDown = false
        canScroll = true
    }

    val connection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (!active) return Offset.Zero
            val dy = available.y
            if (dy < -1f) scrollingDown = true else if (dy > 1f) scrollingDown = false
            if (dy != 0f) lastDragNanos = System.nanoTime()
            val step = CollapseMath.step(collapsed, maxCollapse, dy, canScroll)
            collapsed = step.collapsed
            return Offset(0f, step.consumed)
        }

        override suspend fun onPreFling(available: Velocity): Velocity = Velocity.Zero
    }

    companion object {
        val Saver = androidx.compose.runtime.saveable.Saver<LibraryScrollState, Float>(
            save = { it.collapsed },
            restore = { LibraryScrollState().also { s -> s.collapsed = it } }
        )
    }
}

/**
 * The exact rule for both views: header and FAB fully open whenever the first item is at
 * offset zero (also after a fling or programmatic scroll) or the content cannot scroll.
 */
@Composable
internal fun TrackLibraryScroll(
    scroll: LibraryScrollState,
    atTop: () -> Boolean,
    cannotScroll: () -> Boolean
) {
    LaunchedEffect(scroll) {
        snapshotFlow { atTop() }.collect { top -> if (top) scroll.expand() }
    }
    // Nothing to scroll (checked after a beat so a collapsing header growing the viewport can't flap it).
    LaunchedEffect(scroll) {
        snapshotFlow { cannotScroll() }.collectLatest { cannot ->
            if (cannot) {
                kotlinx.coroutines.delay(300)
                if (System.nanoTime() - scroll.lastDragNanos > 300_000_000L) scroll.expand()
                scroll.canScroll = false
            } else {
                scroll.canScroll = true
            }
        }
    }
}

/** While in composition, keeps the Library header fully open (empty, error and skeleton states). */
@Composable
internal fun KeepHeaderOpen(state: LibraryScrollState) {
    DisposableEffect(state) {
        state.holds++
        state.expand()
        onDispose { state.holds-- }
    }
}

/** The title block: collapses with [state], and is not composed at all once fully collapsed. */
@Composable
internal fun CollapsingHeader(state: LibraryScrollState, content: @Composable () -> Unit) {
    val hidden by remember(state) {
        derivedStateOf { state.maxCollapse > 0f && state.collapsed >= state.maxCollapse - 0.5f }
    }
    Box(Modifier.collapsing(state)) { if (!hidden) content() }
}

/** Lets [content] shrink away (and fade) as [state] collapses. */
fun Modifier.collapsing(state: LibraryScrollState): Modifier = this
    .layout { measurable, constraints ->
        val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = androidx.compose.ui.unit.Constraints.Infinity))
        // An empty (fully collapsed) header measures 0: keep the last real height.
        if (placeable.height > 0 && state.maxCollapse != placeable.height.toFloat()) {
            state.maxCollapse = placeable.height.toFloat()
        }
        val collapsed = state.collapsed.coerceIn(0f, placeable.height.toFloat())
        val visible = (placeable.height - collapsed).roundToInt().coerceAtLeast(0)
        layout(placeable.width, visible) { placeable.place(0, -collapsed.roundToInt()) }
    }
    .clipToBounds()
    .graphicsLayer {
        alpha = if (state.maxCollapse > 0f) (1f - state.collapsed / state.maxCollapse).coerceIn(0f, 1f) else 1f
    }

// --- control row ---------------------------------------------------------------

@Composable
internal fun ControlBar(
    query: String,
    onQuery: (String) -> Unit,
    sort: SortOrder,
    onSort: (SortOrder) -> Unit,
    posterView: Boolean,
    onTogglePosters: () -> Unit,
    grouped: Boolean,
    onToggleGroup: () -> Unit,
    /** Null: no service signed in, so the list is the downloads already and there's no chip. */
    downloadsView: Boolean?,
    onToggleDownloads: () -> Unit,
    /** D-pad UP from the search field; return true when handled (the title block was collapsed and is being reopened). */
    onSearchUp: () -> Boolean = { false }
) {
    Column(modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)) {
        SearchPill(
            query = query,
            onQuery = onQuery,
            placeholder = stringResource(R.string.browse_search_hint),
            clearDescription = stringResource(R.string.action_clear),
            // Capped like the YouTube field so it doesn't stretch across a tablet.
            modifier = Modifier.padding(horizontal = 16.dp).widthIn(max = 560.dp)
                .onPreviewKeyEvent { it.key == androidx.compose.ui.input.key.Key.DirectionUp && it.type == androidx.compose.ui.input.key.KeyEventType.KeyDown && onSearchUp() }
        )
        Row(verticalAlignment = Alignment.Top) {
            // Filters wrap onto a second line rather than scroll, so "Group as series" and
            // any long translation are always visible; the view toggle stays put at the end.
            @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
            androidx.compose.foundation.layout.FlowRow(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                SortPicker(sort = sort, onSort = onSort)
                if (downloadsView != null) {
                    FilterChip(
                        selected = downloadsView,
                        onClick = onToggleDownloads,
                        leadingIcon = if (downloadsView) {
                            { Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                        } else null,
                        label = { Text(stringResource(R.string.browse_downloaded)) }
                    )
                }
                if (!posterView) {
                    FilterChip(
                        selected = grouped,
                        onClick = onToggleGroup,
                        leadingIcon = if (grouped) {
                            { Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                        } else null,
                        label = { Text(stringResource(R.string.browse_group_series)) }
                    )
                }
            }
            // The view mode is a button, not a filter: it shows the view you would switch to.
            FilledTonalIconButton(onClick = onTogglePosters, modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp)) {
                Icon(
                    if (posterView) Icons.AutoMirrored.Rounded.ViewList else Icons.Rounded.GridView,
                    contentDescription = stringResource(
                        if (posterView) R.string.library_view_as_list else R.string.library_view_as_posters
                    )
                )
            }
        }
    }
}

@Composable
private fun SortPicker(sort: SortOrder, onSort: (SortOrder) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        AssistChip(
            onClick = { expanded = true },
            leadingIcon = { Icon(Icons.AutoMirrored.Rounded.Sort, contentDescription = null, modifier = Modifier.size(18.dp)) },
            trailingIcon = { Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(18.dp)) },
            label = { Text(sort.label()) }
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SortOrder.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label()) },
                    leadingIcon = {
                        if (option == sort) Icon(Icons.Rounded.Check, contentDescription = null)
                    },
                    onClick = { onSort(option); expanded = false }
                )
            }
        }
    }
}

@Composable
private fun SortOrder.label(): String = stringResource(
    when (this) {
        SortOrder.DATE_DESC -> R.string.sort_newest
        SortOrder.DATE_ASC -> R.string.sort_oldest
        SortOrder.NAME_ASC -> R.string.sort_name_az
        SortOrder.NAME_DESC -> R.string.sort_name_za
        SortOrder.SIZE_DESC -> R.string.sort_largest
        SortOrder.SIZE_ASC -> R.string.sort_smallest
    }
)

// --- TMDB prompt -----------------------------------------------------------------

/** One-line nudge while no TMDB key is set; closing it is remembered by the caller. */
@Composable
internal fun PosterPromptRow(onSetUp: () -> Unit, onDismiss: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Rounded.Image, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(
                stringResource(R.string.library_tmdb_prompt),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 10.dp)
            )
            TextButton(onClick = onSetUp) {
                Text(stringResource(R.string.poster_banner_action), fontWeight = FontWeight.Bold)
            }
            IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.action_dismiss),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

// --- loading skeleton ----------------------------------------------------------------

@Composable
private fun rememberPulse(): State<Float> {
    val transition = rememberInfiniteTransition(label = "skeleton")
    return transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "pulse"
    )
}

/** Placeholder tiles in the poster grid's real columns and 2:3 ratio. */
@Composable
internal fun PosterSkeleton(modifier: Modifier = Modifier, gridSize: GridSize = GridSize.MEDIUM) {
    val pulse = rememberPulse()
    val loading = stringResource(R.string.library_loading)
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = gridSize.minDp.dp),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        userScrollEnabled = false,
        modifier = modifier.fillMaxSize().semantics { contentDescription = loading }
    ) {
        items(SKELETON_TILES) {
            Column(Modifier.graphicsLayer { alpha = pulse.value }) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(2f / 3f)
                        .clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                )
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier
                        .fillMaxWidth(0.7f)
                        .height(10.dp)
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                )
            }
        }
    }
}

/** Placeholder rows shaped like the list view's file rows. */
@Composable
internal fun ListSkeleton(modifier: Modifier = Modifier) {
    val pulse = rememberPulse()
    val loading = stringResource(R.string.library_loading)
    LazyColumn(
        contentPadding = PaddingValues(top = 8.dp, bottom = 88.dp),
        userScrollEnabled = false,
        modifier = modifier.fillMaxSize().semantics { contentDescription = loading }
    ) {
        items(SKELETON_ROWS) {
            Row(
                modifier = Modifier
                    .graphicsLayer { alpha = pulse.value }
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 3.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(40.dp).clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                )
                Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(
                        Modifier.fillMaxWidth(0.8f).height(12.dp).clip(MaterialTheme.shapes.small)
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    )
                    Box(
                        Modifier.fillMaxWidth(0.5f).height(10.dp).clip(MaterialTheme.shapes.small)
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    )
                }
            }
        }
    }
}

private val SKELETON_TILES = List(18) { it }
private val SKELETON_ROWS = List(9) { it }
