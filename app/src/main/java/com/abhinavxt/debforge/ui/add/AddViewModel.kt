package com.abhinavxt.debforge.ui.add

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.abhinavxt.debforge.data.repository.AuthRepository
import com.abhinavxt.debforge.data.repository.DownloadsRepository
import com.abhinavxt.debforge.domain.AddInputParser
import com.abhinavxt.debforge.domain.AddKind
import com.abhinavxt.debforge.domain.AddRequest
import com.abhinavxt.debforge.domain.DataResult
import com.abhinavxt.debforge.domain.ProviderId
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject

data class AddUiState(
    val visible: Boolean = false,
    val providerName: String = "",
    val capabilities: Set<AddKind> = emptySet(),
    val text: String = "",
    val torrentUri: Uri? = null,
    val torrentName: String? = null,
    val submitting: Boolean = false,
    val error: String? = null
) {
    val acceptsText: Boolean get() = AddKind.MAGNET in capabilities || AddKind.LINK in capabilities
    val acceptsTorrentFile: Boolean get() = AddKind.TORRENT_FILE in capabilities
    val canSubmit: Boolean get() = !submitting && (text.isNotBlank() || torrentUri != null)

    val textHint: String
        get() = when {
            AddKind.MAGNET in capabilities && AddKind.LINK in capabilities -> "Magnet links or URLs, one per line"
            AddKind.MAGNET in capabilities -> "Magnet links, one per line"
            else -> "Hoster links, one per line"
        }
}

/**
 * Drives the "Add to <service>" dialog. Opened from the Browse FAB or by an
 * incoming intent (magnet tap, shared link, opened .torrent) via
 * [PendingAddStore]. Items are sent to the ACTIVE provider.
 */
@HiltViewModel
class AddViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val auth: AuthRepository,
    private val repository: DownloadsRepository,
    private val pendingAdds: PendingAddStore,
    private val events: ProviderEvents
) : ViewModel() {

    private val _state = MutableStateFlow(AddUiState())
    val state: StateFlow<AddUiState> = _state.asStateFlow()

    private var provider: ProviderId? = null

    init {
        // One collector for both inputs: an intent that arrived before the
        // provider was known (e.g. a magnet tapped before sign-in) is still
        // delivered the moment a signed-in provider appears.
        viewModelScope.launch {
            combine(auth.signedInProvider, pendingAdds.pending) { id, add -> id to add }
                .collect { (id, add) ->
                    if (id == null) return@collect
                    if (id != provider) {
                        provider = id
                        _state.update {
                            it.copy(
                                providerName = auth.info(id).displayName,
                                capabilities = repository.addCapabilities(id)
                            )
                        }
                    }
                    if (add != null) {
                        when (val consumed = pendingAdds.consume()) {
                            is PendingAdd.Text -> open(prefillText = consumed.text)
                            is PendingAdd.TorrentUri -> open(torrent = consumed.uri)
                            null -> Unit
                        }
                    }
                }
        }
    }

    fun open(prefillText: String? = null, torrent: Uri? = null) {
        _state.update {
            it.copy(
                visible = true,
                // Append rather than replace: sharing several magnets in a row
                // collects them in one dialog.
                text = listOfNotNull(it.text.takeIf { t -> t.isNotBlank() }, prefillText)
                    .joinToString("\n"),
                error = null
            )
        }
        if (torrent != null) onTorrentPicked(torrent)
    }

    fun dismiss() {
        if (_state.value.submitting) return
        _state.value = AddUiState(
            providerName = _state.value.providerName,
            capabilities = _state.value.capabilities
        )
    }

    fun onTextChange(value: String) = _state.update { it.copy(text = value, error = null) }

    fun onTorrentPicked(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            val name = withContext(Dispatchers.IO) { displayName(uri) } ?: "file.torrent"
            _state.update { it.copy(torrentUri = uri, torrentName = name, error = null) }
        }
    }

    fun clearTorrent() = _state.update { it.copy(torrentUri = null, torrentName = null) }

    fun submit() {
        val s = _state.value
        val p = provider ?: return
        if (!s.canSubmit) return
        _state.update { it.copy(submitting = true, error = null) }

        viewModelScope.launch {
            val parsed = AddInputParser.parse(s.text)
            val requests = parsed.requests.toMutableList()

            if (s.torrentUri != null) {
                when (val file = readTorrent(s.torrentUri, s.torrentName ?: "file.torrent")) {
                    is DataResult.Success -> requests += file.data
                    is DataResult.Error -> {
                        _state.update { it.copy(submitting = false, error = file.message) }
                        return@launch
                    }
                }
            }

            if (requests.isEmpty()) {
                _state.update {
                    it.copy(submitting = false, error = "Nothing to add — paste a magnet link or an http(s) URL")
                }
                return@launch
            }
            val unsupported = requests.filter { it.kind !in s.capabilities }.map { it.kind }.toSet()
            if (unsupported.isNotEmpty()) {
                _state.update {
                    it.copy(
                        submitting = false,
                        error = "${s.providerName} doesn't support " +
                            unsupported.joinToString(" or ") { k -> k.label } + " here"
                    )
                }
                return@launch
            }

            // Sequential on purpose: TorBox limits uncached creations to
            // 60/hour, and sequential failures are easier to report.
            var added = 0
            var lastMessage = ""
            val failures = mutableListOf<String>()
            requests.forEach { req ->
                when (val r = repository.add(p, req)) {
                    is DataResult.Success -> { added++; lastMessage = r.data }
                    is DataResult.Error -> failures += r.message
                }
            }

            if (failures.isEmpty()) {
                val msg = if (added == 1) lastMessage else "Added $added items to ${s.providerName}"
                events.notifyListChanged(msg)
                _state.value = AddUiState(providerName = s.providerName, capabilities = s.capabilities)
            } else {
                if (added > 0) events.notifyListChanged("Added $added of ${requests.size}")
                _state.update {
                    it.copy(
                        submitting = false,
                        error = failures.distinct().joinToString("\n") +
                            if (added > 0) "\n($added of ${requests.size} added)" else ""
                    )
                }
            }
            if (parsed.ignored.isNotEmpty() && failures.isEmpty()) {
                events.notifyListChanged("Skipped ${parsed.ignored.size} unrecognised item(s)")
            }
        }
    }

    // --- file helpers ------------------------------------------------------

    private suspend fun readTorrent(uri: Uri, name: String): DataResult<AddRequest> =
        withContext(Dispatchers.IO) {
            try {
                val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                    // .torrent files are small; refuse anything absurd rather
                    // than reading it all into memory.
                    val buf = input.readNBytesCompat(MAX_TORRENT_BYTES + 1)
                    if (buf.size > MAX_TORRENT_BYTES) {
                        return@withContext DataResult.Error("That file is too large to be a .torrent")
                    }
                    buf
                } ?: return@withContext DataResult.Error("Couldn't open the file")
                // Bencoded torrents always start with 'd' (a dictionary).
                if (bytes.isEmpty() || bytes[0] != 'd'.code.toByte()) {
                    return@withContext DataResult.Error("$name doesn't look like a .torrent file")
                }
                DataResult.Success(AddRequest.TorrentFile(name, bytes))
            } catch (e: IOException) {
                DataResult.Error("Couldn't read the file: ${e.message}")
            } catch (e: SecurityException) {
                DataResult.Error("No permission to read that file — try picking it again")
            }
        }

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull() ?: uri.lastPathSegment

    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0
        while (total < limit) {
            val n = read(buf, 0, minOf(buf.size, limit - total))
            if (n < 0) break
            out.write(buf, 0, n)
            total += n
        }
        return out.toByteArray()
    }

    private companion object {
        const val MAX_TORRENT_BYTES = 10 * 1024 * 1024
    }
}
