package com.abhinavxt.debforge.ui

import androidx.annotation.PluralsRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * Plural string in Compose. (Compose's own pluralStringResource was
 * experimental in the Compose version this project pins.)
 */
@Composable
fun pluralRes(@PluralsRes id: Int, count: Int, vararg args: Any): String =
    LocalContext.current.resources.getQuantityString(id, count, *args)
