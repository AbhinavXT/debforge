package com.abhinavxt.debforge.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.abhinavxt.debforge.ui.player.focusRing

/**
 * Makes a channel name tappable and D-pad focusable (a focus ring, no initial focus), like the names in search rows.
 * [label] is the spoken description ("Open channel X"). Apply to the name's Text.
 */
@Composable
fun Modifier.channelLink(label: String, onClick: () -> Unit): Modifier {
    val source = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(8.dp)
    return clip(shape)
        .focusRing(source, shape)
        .clickable(interactionSource = source, indication = androidx.compose.foundation.LocalIndication.current, role = Role.Button, onClick = onClick)
        .semantics { contentDescription = label }
        .padding(vertical = 4.dp)
}
