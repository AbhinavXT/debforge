package com.abhinavxt.debforge.ui.bookmarks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.ui.platform.LocalInspectionMode
import com.abhinavxt.debforge.R
import com.abhinavxt.debforge.data.bookmarks.BOOKMARK_LIMIT
import com.abhinavxt.debforge.data.bookmarks.BOOKMARK_NAME_MAX
import com.abhinavxt.debforge.data.bookmarks.BookmarkDao
import com.abhinavxt.debforge.data.bookmarks.BookmarkEntity
import com.abhinavxt.debforge.data.bookmarks.bookmarkLabel
import com.abhinavxt.debforge.data.bookmarks.formatBookmarkTime
import com.abhinavxt.debforge.ui.player.focusRing
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class BookmarksViewModel @Inject constructor(private val dao: BookmarkDao) : ViewModel() {
    private val key = MutableStateFlow<String?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val items = key.flatMapLatest { k -> if (k == null) flowOf(emptyList()) else dao.observe(k) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun show(itemKey: String) { key.value = itemKey }

    /** Whole seconds, like the label; a second bookmark on the same second is skipped. */
    fun add(positionMs: Long, name: String?) {
        val k = key.value ?: return
        val at = positionMs.coerceAtLeast(0) / 1000 * 1000
        viewModelScope.launch {
            if (items.value.none { it.positionMs == at } && dao.count() < BOOKMARK_LIMIT) {
                dao.insert(k, at, name?.trim()?.take(BOOKMARK_NAME_MAX)?.ifEmpty { null }, System.currentTimeMillis())
            }
        }
    }

    fun rename(id: Long, name: String?) {
        viewModelScope.launch { dao.rename(id, name?.trim()?.take(BOOKMARK_NAME_MAX)?.ifEmpty { null }) }
    }

    fun delete(row: BookmarkEntity) { viewModelScope.launch { dao.delete(row.id) } }

    fun restore(row: BookmarkEntity) { viewModelScope.launch { dao.restore(row) } }
}

/** Where a jump lands: inside the item. */
internal fun clampBookmarkJump(positionMs: Long, durationMs: Long): Long =
    if (durationMs > 0) positionMs.coerceIn(0, durationMs) else positionMs.coerceAtLeast(0)

/** The bookmarks of [itemKey] in a bottom sheet; [onJump] after a jump, so the caller can close it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookmarksSheet(
    itemKey: String,
    positionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    onDismiss: () -> Unit,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    contentColor: Color = MaterialTheme.colorScheme.onSurface
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = containerColor,
        contentColor = contentColor
    ) {
        Text(
            stringResource(R.string.bookmarks), color = contentColor, style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
        )
        BookmarksContent(itemKey, positionMs, durationMs, onSeek, contentColor, Modifier.padding(bottom = 12.dp), onJump = onDismiss)
    }
}

/** Add at the current position, jump, rename and delete (with undo). Updates live from the database. */
@Composable
fun BookmarksContent(
    itemKey: String,
    positionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    color: Color,
    modifier: Modifier = Modifier,
    onJump: () -> Unit = {}
) {
    if (LocalInspectionMode.current) return
    val vm: BookmarksViewModel = hiltViewModel()
    LaunchedEffect(itemKey) { vm.show(itemKey) }
    val rows by vm.items.collectAsState()
    var editing by remember { mutableStateOf<Editing?>(null) }
    var undo by remember { mutableStateOf<BookmarkEntity?>(null) }
    val muted = color.copy(alpha = 0.7f)

    Column(modifier.fillMaxWidth()) {
        FilledTonalButton(
            { undo = null; editing = Editing(null, positionMs) },
            Modifier.fillMaxWidth().padding(horizontal = 20.dp)
        ) {
            Icon(Icons.Rounded.BookmarkAdd, null, Modifier.size(20.dp))
            Text(stringResource(R.string.bookmarks_add, formatBookmarkTime(positionMs)), Modifier.padding(start = 8.dp))
        }
        undo?.let { gone ->
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.bookmarks_deleted), Modifier.weight(1f), color = muted)
                TextButton({ vm.restore(gone); undo = null }) { Text(stringResource(R.string.bookmarks_undo)) }
            }
        }
        if (rows.isEmpty() && undo == null) {
            Text(
                stringResource(R.string.bookmarks_empty), color = muted,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)
            )
        }
        LazyColumn(Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(rows, key = { it.id }) { row ->
                val label = bookmarkLabel(row.name, row.positionMs)
                val time = formatBookmarkTime(row.positionMs)
                val src = remember { MutableInteractionSource() }
                Row(
                    Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp).focusRing(src, RoundedCornerShape(12.dp))
                        .clickable(
                            interactionSource = src, indication = androidx.compose.foundation.LocalIndication.current,
                            onClickLabel = stringResource(R.string.bookmarks_go_to, time)
                        ) { onSeek(clampBookmarkJump(row.positionMs, durationMs)); onJump() }
                        .padding(start = 8.dp).heightIn(min = 56.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(label, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (row.name != null) Text(time, color = muted, style = MaterialTheme.typography.bodySmall)
                    }
                    val s1 = remember { MutableInteractionSource() }
                    IconButton({ editing = Editing(row, row.positionMs) }, Modifier.size(48.dp).focusRing(s1, CircleShape), interactionSource = s1) {
                        Icon(Icons.Rounded.Edit, stringResource(R.string.bookmarks_rename), tint = muted)
                    }
                    val s2 = remember { MutableInteractionSource() }
                    IconButton({ vm.delete(row); undo = row }, Modifier.size(48.dp).focusRing(s2, CircleShape), interactionSource = s2) {
                        Icon(Icons.Rounded.Delete, stringResource(R.string.bookmarks_delete), tint = muted)
                    }
                }
            }
        }
    }

    editing?.let { e ->
        var text by remember(e) { mutableStateOf(e.row?.name.orEmpty()) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(if (e.row == null) stringResource(R.string.bookmarks_add, formatBookmarkTime(e.positionMs)) else stringResource(R.string.bookmarks_rename)) },
            text = {
                OutlinedTextField(
                    text, { text = it.take(BOOKMARK_NAME_MAX) }, singleLine = true,
                    label = { Text(stringResource(R.string.bookmarks_name_hint)) }
                )
            },
            confirmButton = {
                TextButton({
                    if (e.row == null) vm.add(e.positionMs, text) else vm.rename(e.row.id, text)
                    editing = null
                }) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = { TextButton({ editing = null }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }
}

/** [row] null = a new bookmark at [positionMs] (taken when the dialog opened: playback goes on meanwhile). */
private data class Editing(val row: BookmarkEntity?, val positionMs: Long)

/** Android TV has Media3's own controller, so the bookmarks open as a sheet from the player's menu; the position is polled here. */
@Composable
fun TvBookmarks(state: com.abhinavxt.debforge.player.controls.ControlsState, actions: com.abhinavxt.debforge.player.controls.ControlsActions) {
    val key = state.bookmarkKey ?: return
    if (state.panel != com.abhinavxt.debforge.player.controls.Panel.BOOKMARKS) return
    var snap by remember { mutableStateOf(actions.snapshot()) }
    LaunchedEffect(Unit) {
        while (true) {
            snap = actions.snapshot()
            kotlinx.coroutines.delay(500)
        }
    }
    BookmarksSheet(key, snap.positionMs, snap.durationMs, actions::seekTo, { state.panel = null })
}
