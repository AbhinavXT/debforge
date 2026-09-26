package com.abhinavxt.debforge.ui.add

import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Something handed to DebForge by another app (browser, file manager, share sheet). */
sealed interface PendingAdd {
    data class Text(val text: String) : PendingAdd
    data class TorrentUri(val uri: Uri) : PendingAdd
}

/**
 * Bridges MainActivity's incoming intents to the Add dialog. Held until the
 * dialog consumes it, so a magnet tapped before sign-in is still offered
 * right after the user signs in.
 */
@Singleton
class PendingAddStore @Inject constructor() {

    private val _pending = MutableStateFlow<PendingAdd?>(null)
    val pending: StateFlow<PendingAdd?> = _pending.asStateFlow()

    fun offer(add: PendingAdd) {
        _pending.value = add
    }

    fun consume(): PendingAdd? = _pending.value.also { _pending.value = null }

    /**
     * Returns true if [intent] carried something addable:
     *  - VIEW magnet:?…                (tapping a magnet link in a browser)
     *  - VIEW content://… .torrent     (opening a .torrent from a file manager)
     *  - SEND text                     (share a magnet or URL)
     *  - SEND stream .torrent          (share a .torrent file)
     */
    fun offerFromIntent(intent: Intent?): Boolean {
        intent ?: return false
        val add: PendingAdd? = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data?.let { data ->
                when (data.scheme?.lowercase()) {
                    "magnet" -> PendingAdd.Text(data.toString())
                    "content", "file" -> PendingAdd.TorrentUri(data)
                    else -> null
                }
            }
            Intent.ACTION_SEND -> {
                // Deprecated on API 33+ but works everywhere; avoids depending
                // on a newer androidx.core than the project pins.
                @Suppress("DEPRECATION")
                val stream: Uri? = intent.getParcelableExtra(Intent.EXTRA_STREAM)
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                when {
                    stream != null -> PendingAdd.TorrentUri(stream)
                    !text.isNullOrBlank() -> PendingAdd.Text(text)
                    else -> null
                }
            }
            else -> null
        }
        if (add != null) offer(add)
        return add != null
    }
}

/**
 * App-wide signal that a provider's remote list changed (something was added)
 * so Browse can reload and show the confirmation.
 */
@Singleton
class ProviderEvents @Inject constructor() {
    private val _listChanged = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val listChanged: SharedFlow<String> = _listChanged.asSharedFlow()

    fun notifyListChanged(message: String) {
        _listChanged.tryEmit(message)
    }
}
