package com.abhinavxt.debforge.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusProperties
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.abhinavxt.debforge.ui.player.focusRing

/**
 * Makes a channel name tappable and D-pad focusable (a focus ring, no initial focus), like the names in search rows.
 * [label] is the spoken description ("Open channel X"). Apply to the name's Text. Adds no size, so linked and plain text lay out alike.
 * [focusable] false makes it touch-only (a list row where D-pad Down should move row to row); [focusRequester] and
 * [focusOrder] place it in a custom D-pad order.
 */
@Composable
fun Modifier.channelLink(
    label: String,
    focusable: Boolean = true,
    focusRequester: FocusRequester? = null,
    focusOrder: (FocusProperties.() -> Unit)? = null,
    onClick: () -> Unit
): Modifier {
    val source = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(8.dp)
    return clip(shape)
        .focusRing(source, shape)
        .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
        .focusProperties { if (!focusable) canFocus = false; focusOrder?.invoke(this) }
        .clickable(interactionSource = source, indication = androidx.compose.foundation.LocalIndication.current, role = Role.Button, onClick = onClick)
        .semantics { contentDescription = label }
}
