package com.abhinavxt.debforge.ui.media

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.abhinavxt.debforge.R
import com.abhinavxt.debforge.data.playback.Playlist

/** Everything the Audio list UI can ask the ViewModel to do. Defaults are no-ops (previews). */
class AudioListActions(
    val onPick: (String?) -> Unit = {},
    val onToggleFavourite: (String) -> Unit = {},
    val onCreate: (String, List<String>) -> Unit = { _, _ -> },
    val onRename: (String, String) -> Unit = { _, _ -> },
    val onDelete: (String) -> Unit = {},
    val onAddTo: (String, List<String>) -> Unit = { _, _ -> },
    val onRemoveFrom: (String, String) -> Unit = { _, _ -> }
)

/** Single text field for creating or renaming a playlist. Save is off while the name is blank. */
@Composable
internal fun PlaylistNameDialog(title: String, initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    // Cursor at the end, so renaming continues the name instead of typing in front of it.
    var field by remember { mutableStateOf(TextFieldValue(initial, TextRange(initial.length))) }
    val name = field.text.trim()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = field, onValueChange = { field = it.copy(text = it.text.take(60)) },
                singleLine = true,
                label = { Text(stringResource(R.string.media_playlist_name)) },
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(name); onDismiss() }, enabled = name.isNotEmpty()) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    )
}

/** Lists the playlists; tapping one adds the tracks, "New playlist" asks for a name first. */
@Composable
internal fun AddToPlaylistDialog(playlists: List<Playlist>, onPick: (Playlist) -> Unit, onNew: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.media_add_to_playlist)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                playlists.forEach { p ->
                    TextButton(onClick = { onPick(p); onDismiss() }, Modifier.fillMaxWidth()) {
                        Text(p.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                TextButton(onClick = { onNew(); onDismiss() }, Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.Add, null)
                    Text(stringResource(R.string.media_new_playlist))
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    )
}
