package com.abhinavxt.debforge.ui.browse

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.view.ViewTreeObserver
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.abhinavxt.debforge.data.prefs.SettingsStore
import com.abhinavxt.debforge.domain.ClipboardLinks
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * "Add the magnet you just copied?" Checks the clipboard when the Library
 * window gains focus (Android only lets the focused app read it).
 *
 * Kept polite on purpose:
 *  - each clip is looked at once (by its timestamp), so it's never offered
 *    twice and Android's "pasted from clipboard" notice shows at most once
 *    per copy;
 *  - clips marked sensitive (passwords from a password manager) and non-text
 *    clips are skipped without reading them;
 *  - only magnets and .torrent links are offered (see [ClipboardLinks]);
 *  - it can be switched off in Settings.
 */
@HiltViewModel
class ClipboardOfferViewModel @Inject constructor(
    private val settings: SettingsStore
) : ViewModel() {

    val enabled: StateFlow<Boolean> = settings.clipboardOfferFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun check(context: Context, onOffer: (String) -> Unit) {
        val cm = context.getSystemService(ClipboardManager::class.java) ?: return
        // The description is readable without Android's clipboard notice.
        val desc = runCatching { cm.primaryClipDescription }.getOrNull() ?: return
        if (!desc.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) &&
            !desc.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML) &&
            !desc.hasMimeType(ClipDescription.MIMETYPE_TEXT_URILIST)
        ) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            desc.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true
        ) return
        val stamp = desc.timestamp
        viewModelScope.launch {
            if (stamp <= settings.clipboardSeenFlow.first()) return@launch
            settings.setClipboardSeen(stamp)
            val text = runCatching { cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() }.getOrNull()
            ClipboardLinks.pick(text)?.let(onOffer)
        }
    }
}

/** Calls [onOffer] with links worth adding whenever a new clip holds some. */
@Composable
fun ClipboardOfferEffect(
    onOffer: (String) -> Unit,
    viewModel: ClipboardOfferViewModel = hiltViewModel()
) {
    val enabled by viewModel.enabled.collectAsStateWithLifecycle()
    if (!enabled) return
    val view = LocalView.current
    val context = LocalContext.current
    val currentOnOffer by rememberUpdatedState(onOffer)
    DisposableEffect(view) {
        val listener = ViewTreeObserver.OnWindowFocusChangeListener { focused ->
            if (focused) viewModel.check(context) { currentOnOffer(it) }
        }
        view.viewTreeObserver.addOnWindowFocusChangeListener(listener)
        if (view.hasWindowFocus()) viewModel.check(context) { currentOnOffer(it) }
        onDispose { view.viewTreeObserver.removeOnWindowFocusChangeListener(listener) }
    }
}
