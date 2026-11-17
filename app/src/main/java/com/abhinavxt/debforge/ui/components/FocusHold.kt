package com.abhinavxt.debforge.ui.components

import androidx.compose.foundation.focusable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager

/**
 * D-pad: when the focused item disappears in a content swap, focus falls to the first focusable in the window (the Library tab).
 * Put this on the replacement content; with [hold] true (the content it replaced had items) and a keyboard in use, it takes focus
 * itself. Touch is unchanged.
 */
@Composable
fun Modifier.holdFocus(hold: Boolean): Modifier {
    val requester = remember { FocusRequester() }
    val keys = LocalInputModeManager.current.inputMode == InputMode.Keyboard
    val initial = remember { hold }
    LaunchedEffect(Unit) { if (keys && initial) runCatching { requester.requestFocus() } }
    return this.focusRequester(requester).focusable()
}
