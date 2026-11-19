package com.abhinavxt.debforge.ui.youtube

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.abhinavxt.debforge.R
import com.abhinavxt.debforge.data.playback.Playlist
import com.abhinavxt.debforge.data.playback.YouTubeAddResult
import com.abhinavxt.debforge.data.playback.YouTubeMeta
import com.abhinavxt.debforge.data.playback.YouTubeMode
import com.abhinavxt.debforge.domain.YouTubeLinks
import com.abhinavxt.debforge.domain.YouTubeSearchResult
import com.abhinavxt.debforge.domain.YouTubeVideo
import com.abhinavxt.debforge.ui.media.PlaylistNameDialog

/** A video the user wants in a playlist. [audio] is the mode the dialog opens with. */
data class AddToPlaylistRequest(val videoId: String, val meta: YouTubeMeta, val audio: Boolean)

/** From a search row there is no mode yet: audio is the default. */
internal fun YouTubeSearchResult.toAddRequest(): AddToPlaylistRequest? =
    YouTubeLinks.videoId(url)?.let { AddToPlaylistRequest(it, YouTubeMeta(title, channel?.takeIf { c -> c.isNotBlank() }, durationSec.toInt().takeIf { d -> d > 0 }), audio = true) }

/** From the preview the dialog opens in the mode currently showing. */
internal fun YouTubeVideo.toAddRequest(previewAudio: Boolean): AddToPlaylistRequest =
    AddToPlaylistRequest(id, YouTubeMeta(title, uploader.takeIf { it.isNotBlank() }, durationSec.toInt().takeIf { it > 0 }), previewAudio)

internal fun playlistAddMode(audio: Boolean) = if (audio) YouTubeMode.AUDIO else YouTubeMode.VIDEO

/** The snackbar text for an add result; the `%1$s` ones take the playlist name. */
@StringRes
internal fun playlistAddMessage(r: YouTubeAddResult): Int = when (r) {
    YouTubeAddResult.ADDED -> R.string.yt_pl_added
    YouTubeAddResult.ALREADY_IN_LIST -> R.string.yt_pl_already
    YouTubeAddResult.LIMIT_REACHED -> R.string.yt_pl_limit
    YouTubeAddResult.NO_SUCH_LIST, YouTubeAddResult.INVALID_ID -> R.string.yt_pl_failed
}

/** Opens the add-to-playlist dialog; null (previews) hides the buttons. */
internal val LocalAddToPlaylist = compositionLocalOf<((AddToPlaylistRequest) -> Unit)?> { null }

/** Audio / Video choice, then an existing playlist or a new one. */
@Composable
internal fun YouTubeAddToPlaylistFlow(
    request: AddToPlaylistRequest,
    playlists: List<Playlist>,
    onAdd: (listId: String, mode: YouTubeMode) -> Unit,
    onCreate: (name: String, mode: YouTubeMode) -> Unit,
    onDismiss: () -> Unit,
) {
    var audio by remember(request) { mutableStateOf(request.audio) }
    var naming by remember(request) { mutableStateOf(false) }
    if (naming) {
        PlaylistNameDialog(
            title = stringResource(R.string.media_new_playlist), initial = "",
            onSave = { onCreate(it, playlistAddMode(audio)) }, onDismiss = onDismiss,
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.media_add_to_playlist)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(request.meta.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(stringResource(R.string.yt_pl_add_as), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp))
                Row(Modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(audio, { audio = true }, label = { Text(stringResource(R.string.yt_mode_audio)) })
                    FilterChip(!audio, { audio = false }, label = { Text(stringResource(R.string.yt_mode_video)) })
                }
                playlists.forEach { p ->
                    TextButton(onClick = { onAdd(p.id, playlistAddMode(audio)) }, Modifier.fillMaxWidth()) {
                        Text(p.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                TextButton(onClick = { naming = true }, Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.Add, null)
                    Text(stringResource(R.string.media_new_playlist))
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
