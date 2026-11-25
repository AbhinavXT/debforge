package com.abhinavxt.debforge.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import com.abhinavxt.debforge.R
import com.abhinavxt.debforge.domain.GridSize
import com.abhinavxt.debforge.ui.components.SettingRow

/** A setting row that opens a menu of [options]; the current one shows as the subtitle. */
@Composable
internal fun <T> ChoiceRow(
    icon: ImageVector,
    title: String,
    current: T,
    options: List<T>,
    label: @Composable (T) -> String,
    onPick: (T) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    Box {
        SettingRow(icon = icon, title = title, subtitle = label(current), onClick = { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(label(option)) },
                    leadingIcon = { if (option == current) Icon(Icons.Rounded.Check, contentDescription = null) },
                    onClick = { onPick(option); open = false }
                )
            }
        }
    }
}

@StringRes
internal fun GridSize.labelRes() = when (this) {
    GridSize.SMALL -> R.string.set_grid_small
    GridSize.MEDIUM -> R.string.set_grid_medium
    GridSize.LARGE -> R.string.set_grid_large
}
