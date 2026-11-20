package com.abhinavxt.debforge.ui.browse

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.abhinavxt.debforge.R
import com.abhinavxt.debforge.download.SpaceWarning

/** "Not enough space": queue anyway, or cancel. Shown wherever a download is started. */
@Composable
fun SpaceWarningDialog(warning: SpaceWarning, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.WarningAmber, contentDescription = null) },
        title = { Text(stringResource(R.string.space_title)) },
        text = {
            Text(stringResource(R.string.space_body, formatSize(warning.needed), formatSize(warning.available), warning.folder))
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.space_queue_anyway)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}
