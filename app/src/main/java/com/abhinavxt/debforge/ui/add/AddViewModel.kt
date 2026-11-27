package com.abhinavxt.debforge.ui.add

import com.abhinavxt.debforge.R
import android.content.Context
import android.net.Uri
import com.abhinavxt.debforge.data.prefs.SettingsStore
import com.abhinavxt.debforge.download.StorageAccess
import kotlinx.coroutines.flow.first
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.abhinavxt.debforge.data.repository.AuthRepository
import com.abhinavxt.debforge.data.repository.DownloadsRepository
import com.abhinavxt.debforge.data.provider.NotCachedException
import com.abhinavxt.debforge.data.provider.ProviderException
import com.abhinavxt.debforge.data.provider.direct.DirectLinks
import com.abhinavxt.debforge.domain.DownloadItem
import com.abhinavxt.debforge.domain.AddInputParser
import com.abhinavxt.debforge.domain.AddKind
import com.abhinavxt.debforge.domain.AddRequest
import com.abhinavxt.debforge.domain.DataResult
import com.abhinavxt.debforge.domain.DirectLinkParser
import com.abhinavxt.debforge.domain.ExtraFiles
import com.abhinavxt.debforge.domain.Nzb
import com.abhinavxt.debforge.domain.ProviderId
import com.abhinavxt.debforge.domain.ServicePicker
import com.abhinavxt.debforge.domain.TorrentEntry
import com.abhinavxt.debforge.download.DownloadController
import com.abhinavxt.debforge.download.watch.ReadyWatcher
import com.abhinavxt.debforge.download.watch.shouldWatchNotifyOnly
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject

/** How many of the torrents in the dialog the service already has cached. */
data class CacheSummary(val checking: Boolean, val total: Int, val cached: Int, val failure: String? = null) {
    val allCached: Boolean get() = !checking && total > 0 && cached == total
    val noneCached: Boolean get() = !checking && failure == null && cached == 0

    companion object {
        /** Summary from what's known per torrent ([known], null = not asked yet); null = nothing to show. */
        fun of(total: Int, checking: Boolean, failure: String?, known: List<Boolean?>): CacheSummary? = when {
            total == 0 -> null
            checking -> CacheSummary(true, total, 0)
            failure != null -> CacheSummary(false, total, 0, failure)
            known.any { it == null } -> null
            else -> CacheSummary(false, total, known.count { it == true })
        }
    }
}

/** What the watch stores: null when every file is ticked, else the ticked paths joined by newlines. */
fun selectedValue(files: List<TorrentEntry>?, unticked: Set<String>): String? {
    if (files == null || unticked.none { u -> files.any { it.path == u } }) return null
    return files.filter { it.path !in unticked }.joinToString("\n") { it.path }
}

/** A signed-in service the dialog can send to (shown when there are two or more). */
data class AddTarget(
    val id: ProviderId,
    val name: String,
    /** Can take everything currently in the dialog. */
    val supportsInput: Boolean,
    /** Cache status there; null = not checked or can't be checked (a failed check is [CacheSummary.failure]). */
    val cache: CacheSummary?
)

data class AddUiState(
    val visible: Boolean = false,
    /** Name of the service the items go to (the selected target). */
    val providerName: String = "",
    /** What the input accepts: everything any signed-in service can take. */
    val capabilities: Set<AddKind> = emptySet(),
    /** Signed-in services to choose from; empty when there's only one. */
    val targets: List<AddTarget> = emptyList(),
    val target: ProviderId? = null,
    /** The target was chosen for the user, not the active service (cached there, or the active one can't take the input). */
    val targetIsSuggestion: Boolean = false,
    /** The active service's name, to explain a suggestion. */
    val activeName: String = "",
    /** The active service can take what's in the dialog (else that's why another was picked). */
    val activeCanTake: Boolean = true,
    /** Downloading straight to the device (no service): Pixeldrain, Drive, file links. */
    val isDirect: Boolean = false,
    /** Direct download was picked for the user because the links are direct ones. */
    val directSuggested: Boolean = false,
    val text: String = "",
    val torrentUri: Uri? = null,
    val torrentName: String? = null,
    /** The picked file is an .nzb (by name or MIME type), not a .torrent. */
    val torrentIsNzb: Boolean = false,
    val submitting: Boolean = false,
    val error: String? = null,
    /** Queue the files automatically once the service has them. */
    val autoDownload: Boolean = true,
    /** Add only what the service already has cached (remembered in settings). */
    val onlyIfCached: Boolean = false,
    /** The target can do that (TorBox) and checks the cache, so the switch is shown. */
    val canOnlyIfCached: Boolean = false,
    /** Current download folder is writable (permission / app storage). */
    val canWriteStorage: Boolean = true,
    /** Cache status of the pasted magnets / picked file; null = nothing to check or unsupported. */
    val cache: CacheSummary? = null,
    /** Looking for videos on the pasted page. */
    val scanning: Boolean = false,
    /** Videos found on the pasted page, to pick from; null = not showing the list. */
    val pageVideos: List<DownloadItem>? = null,
    val pageTitle: String? = null,
    /** Ids of the [pageVideos] ticked for download. */
    val pickedVideos: Set<String> = emptySet(),
    /** Files of the single torrent in the dialog; null = unknown (nothing to show). */
    val files: List<TorrentEntry>? = null,
    /** Paths of [files] the user unticked. */
    val unticked: Set<String> = emptySet()
) {
    val acceptsText: Boolean get() = AddKind.MAGNET in capabilities || AddKind.LINK in capabilities
    val acceptsTorrentFile: Boolean get() = AddKind.TORRENT_FILE in capabilities || AddKind.NZB_FILE in capabilities
    val canSubmit: Boolean
        get() = !submitting && !scanning &&
            if (pageVideos != null) pickedVideos.isNotEmpty() else text.isNotBlank() || torrentUri != null

    /**
     * One web link that isn't a file (and nothing else): offer to look for
     * videos on that page.
     */
    val canScanPage: Boolean
        get() {
            if (torrentUri != null || pageVideos != null) return false
            val requests = AddInputParser.parse(text).requests
            val link = requests.singleOrNull() as? AddRequest.Link ?: return false
            return !DirectLinkParser.prefersDirect(link.url)
        }

    val textHintRes: Int
        get() = when {
            AddKind.MAGNET in capabilities && AddKind.LINK in capabilities -> R.string.add_hint_both
            AddKind.MAGNET in capabilities -> R.string.add_hint_magnet
            else -> R.string.add_hint_links
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
    private val events: ProviderEvents,
    private val controller: DownloadController,
    private val watcher: ReadyWatcher,
    private val settings: SettingsStore,
    private val directLinks: DirectLinks
) : ViewModel() {

    /** Download folder chosen with the system folder picker from the dialog. */
    fun useFolder(tree: Uri) {
        viewModelScope.launch {
            val previous = settings.downloadDirFlow.first()
            runCatching { StorageAccess.rememberPickedFolder(context, tree, previous) }
            settings.setDownloadDir(tree.toString())
            refreshStorageAccess()
        }
    }

    /** Re-checked on open and when returning from the permission screen. */
    fun refreshStorageAccess() {
        viewModelScope.launch {
            val ok = StorageAccess.canWrite(context, settings.downloadDirFlow.first())
            _state.update { it.copy(canWriteStorage = ok) }
        }
    }

    private val _state = MutableStateFlow(AddUiState())
    val state: StateFlow<AddUiState> = _state.asStateFlow()

    /** What's waiting for the user to sign in to a service (there's none yet); null when nothing is. */
    val waitingForService: StateFlow<PendingAdd?> =
        combine(auth.signedInProvider, pendingAdds.pending) { id, add -> add?.takeIf { id == null && needsService(it) } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Magnets and torrents go through a service; plain links can be downloaded straight away. */
    private fun needsService(add: PendingAdd) =
        add !is PendingAdd.Text || AddInputParser.parse(add.text).requests.any { it !is AddRequest.Link }

    /** A magnet found in the app (Browser tab): the dialog opens now, or right after signing in. */
    fun offer(text: String) = pendingAdds.offer(PendingAdd.Text(text))

    /** The active service (the one the Library shows). */
    private var provider: ProviderId? = null
    /** Every signed-in service, in a stable order. */
    private var signedIn: List<ProviderId> = emptyList()
    /** Where this dialog sends to; picked by [ServicePicker] unless the user tapped one. */
    private var target: ProviderId? = null
    private var userPickedTarget = false
    private var onlyIfCached = false

    // --- instant-availability check -------------------------------------------
    private var torrentHash: String? = null
    private var cacheJob: kotlinx.coroutines.Job? = null
    /** provider|hash -> cached, so retyping doesn't re-ask the service. */
    private val cacheResults = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    /** Services whose cache check is in flight. */
    private val checking = java.util.Collections.synchronizedSet(HashSet<ProviderId>())
    /** Services whose check failed for the current input, with why ("Couldn't check ..." + Retry in the dialog). */
    private val checkFailed = java.util.concurrent.ConcurrentHashMap<ProviderId, String>()

    /**
     * Debounced: waits for typing to pause, then asks every signed-in service
     * that can answer (TorBox, Premiumize; the others removed this API) which
     * torrents in the dialog are cached. Checks run in parallel; the results
     * decide which service is suggested ([refreshTargets]).
     */
    private fun scheduleCacheCheck() {
        loadPreview()
        cacheJob?.cancel()
        checking.clear()
        checkFailed.clear()
        val hashes = currentHashes()
        val askable = signedIn.filter { repository.supportsCacheCheck(it) }
        val toAsk = if (hashes.isEmpty()) emptyList()
        else askable.filter { p -> hashes.any { h -> cacheResults["$p|$h"] == null } }
        if (toAsk.isEmpty()) {
            refreshTargets()
            return
        }
        checking += toAsk
        refreshTargets()
        cacheJob = viewModelScope.launch {
            kotlinx.coroutines.delay(600)
            toAsk.map { p ->
                launch {
                    when (val r = repository.cachedHashes(p, hashes)) {
                        is DataResult.Success -> hashes.forEach { h -> cacheResults["$p|$h"] = h in r.data }
                        // Informational only: never block adding because the check failed.
                        is DataResult.Error -> checkFailed[p] = r.message
                    }
                    checking -= p
                    refreshTargets()
                }
            }
        }
    }

    private var torrentEntries: List<TorrentEntry>? = null
    private var previewJob: kotlinx.coroutines.Job? = null
    private var previewKey: String? = null

    /** The file list of the one torrent in the dialog: from the .torrent itself, or the service for a magnet. Background; never blocks adding. */
    private fun loadPreview() {
        val hashes = currentHashes()
        val key = hashes.singleOrNull()
        if (key == previewKey) return
        previewKey = key
        previewJob?.cancel()
        _state.update { it.copy(files = null, unticked = emptySet()) }
        if (key == null) return
        val local = if (torrentHash == key) torrentEntries else null
        previewJob = viewModelScope.launch {
            val files = if (local != null) local else {
                val p = target ?: provider ?: return@launch
                kotlinx.coroutines.delay(600)
                repository.previewFiles(p, key)
            }
            _state.update { it.copy(files = files?.takeIf { f -> f.isNotEmpty() }) }
        }
    }

    /** The "Retry" in the dialog: asks again only the services whose check failed (answers already in are kept). */
    fun retryCacheCheck() = scheduleCacheCheck()

    /** Cache status of the current input on [p]; null = can't tell. */
    private fun cacheOn(p: ProviderId, hashes: List<String>): CacheSummary? {
        if (!repository.supportsCacheCheck(p)) return null
        return CacheSummary.of(hashes.size, p in checking, checkFailed[p], hashes.map { h -> cacheResults["$p|$h"] })
    }

    /** Kinds of item in the dialog right now (magnet, link, .torrent file). */
    private fun inputKinds(): Set<AddKind> {
        val s = _state.value
        val kinds = AddInputParser.parse(s.text).requests.mapTo(HashSet()) { it.kind }
        if (s.torrentUri != null) kinds += if (s.torrentIsNzb) AddKind.NZB_FILE else AddKind.TORRENT_FILE
        return kinds
    }

    /**
     * Rebuilds the "Send to" choices and picks the target: the user's pick,
     * else a service that already has everything cached, else the active one
     * (or the first that can take the input). See [ServicePicker].
     */
    private fun refreshTargets() {
        val active = provider ?: return
        val hashes = currentHashes()
        val kinds = inputKinds()
        val all = (signedIn.ifEmpty { listOf(active) } + ProviderId.DIRECT).distinct()
        val targets = all.map { p ->
            AddTarget(
                id = p,
                name = if (p == ProviderId.DIRECT) context.getString(R.string.direct_target_name) else auth.info(p).displayName,
                supportsInput = repository.addCapabilities(p).containsAll(kinds),
                cache = cacheOn(p, hashes)
            )
        }
        val picked = ServicePicker.pick(
            active = active,
            options = targets.map { ServicePicker.Option(it.id, it.supportsInput, it.cache?.allCached == true) },
            current = target,
            userPicked = userPickedTarget,
            preferred = ProviderId.DIRECT.takeIf { directPreferred() }
        )
        target = picked
        // The active service can briefly be missing from the signed-in set
        // (mid sign-out): wait for the next emission rather than crash.
        val chosen = targets.firstOrNull { it.id == picked } ?: return
        _state.update {
            it.copy(
                providerName = chosen.name,
                capabilities = all.flatMapTo(HashSet()) { p -> repository.addCapabilities(p) },
                targets = if (targets.size > 1) targets else emptyList(),
                target = picked,
                targetIsSuggestion = !userPickedTarget && picked != active,
                isDirect = picked == ProviderId.DIRECT,
                directSuggested = !userPickedTarget && picked == ProviderId.DIRECT,
                canOnlyIfCached = repository.canAddOnlyIfCached(picked) && repository.supportsCacheCheck(picked),
                activeName = auth.info(active).displayName,
                activeCanTake = targets.firstOrNull { t -> t.id == active }?.supportsInput != false,
                cache = chosen.cache
            )
        }
    }

    /**
     * Only links, and every one is better downloaded directly (Pixeldrain,
     * Google Drive, a file URL): suggest direct download over the service.
     */
    private fun directPreferred(): Boolean {
        val s = _state.value
        if (s.torrentUri != null) return false
        val requests = AddInputParser.parse(s.text).requests
        return requests.isNotEmpty() && requests.all { it is AddRequest.Link && DirectLinkParser.prefersDirect(it.url) }
    }

    /** The user tapped a service under "Send to". */
    fun selectTarget(id: ProviderId) {
        userPickedTarget = true
        target = id
        refreshTargets()
    }

    private fun currentHashes(): List<String> {
        val fromText = AddInputParser.parse(_state.value.text).requests
            .filterIsInstance<AddRequest.Magnet>()
            .mapNotNull { com.abhinavxt.debforge.domain.TorrentHash.fromMagnet(it.uri) }
        return (fromText + listOfNotNull(torrentHash)).distinct()
    }

    init {
        viewModelScope.launch {
            settings.addOnlyIfCachedFlow.collect { v ->
                onlyIfCached = v
                _state.update { it.copy(onlyIfCached = v) }
            }
        }
        // One collector for both inputs: an intent that arrived before the
        // provider was known (e.g. a magnet tapped before sign-in) is still
        // delivered the moment a signed-in provider appears.
        viewModelScope.launch {
            combine(auth.signedInProvider, auth.signedInProviders, pendingAdds.pending) { id, all, add ->
                Triple(id, all, add)
            }
                .collect { (id, all, add) ->
                    // No service yet: the dialog still downloads links straight to the device.
                    val active = id ?: ProviderId.DIRECT
                    // Registry order, so the "Send to" chips don't jump around.
                    val ordered = ProviderId.entries.filter { it in all }
                    if (active != provider || ordered != signedIn) {
                        provider = active
                        signedIn = ordered
                        scheduleCacheCheck()
                    }
                    // Magnets and torrents keep waiting for a service (Setup shows meanwhile).
                    if (add != null && (id != null || !needsService(add))) {
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
        refreshStorageAccess()
        scheduleCacheCheck()
    }

    fun dismiss() {
        if (_state.value.submitting) return
        cacheJob?.cancel()
        scanJob?.cancel()
        torrentHash = null
        resetForm()
    }

    /** Empty dialog, sending to the active service again next time. */
    private fun resetForm() {
        userPickedTarget = false
        target = null
        _state.value = AddUiState(onlyIfCached = onlyIfCached)
        refreshTargets()
    }

    fun onTextChange(value: String) {
        _state.update { it.copy(text = value, error = null, pageVideos = null, pickedVideos = emptySet()) }
        scheduleCacheCheck()
    }

    fun onTorrentPicked(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            val name = withContext(Dispatchers.IO) { displayName(uri) } ?: "file.torrent"
            // Some apps hand over an .nzb without the extension: the MIME type tells.
            val nzb = Nzb.isNzbName(name) ||
                withContext(Dispatchers.IO) { runCatching { context.contentResolver.getType(uri) }.getOrNull() } == "application/x-nzb"
            _state.update { it.copy(torrentUri = uri, torrentName = name, torrentIsNzb = nzb, error = null) }
            // Hash the file now (it's small) so the cache line can show before submitting.
            torrentHash = if (nzb) null else (readTorrent(uri, name, false) as? DataResult.Success)?.data
                ?.let { it as? AddRequest.TorrentFile }
                ?.let { com.abhinavxt.debforge.domain.TorrentHash.fromTorrentFile(it.bytes) }
            torrentEntries = if (nzb) null else (readTorrent(uri, name, false) as? DataResult.Success)?.data
                ?.let { it as? AddRequest.TorrentFile }
                ?.let { com.abhinavxt.debforge.domain.TorrentHash.filesFromTorrentFile(it.bytes) }
            scheduleCacheCheck()
        }
    }

    fun toggleFile(path: String) = _state.update {
        it.copy(unticked = if (path in it.unticked) it.unticked - path else it.unticked + path)
    }

    fun toggleAllFiles() = _state.update {
        it.copy(unticked = if (it.unticked.isEmpty()) it.files.orEmpty().map { f -> f.path }.toSet() else emptySet())
    }

    fun onAutoDownloadChange(value: Boolean) = _state.update { it.copy(autoDownload = value) }

    fun onOnlyIfCachedChange(value: Boolean) {
        viewModelScope.launch { settings.setAddOnlyIfCached(value) }
    }

    fun clearTorrent() {
        torrentEntries = null
        _state.update { it.copy(torrentUri = null, torrentName = null, torrentIsNzb = false) }
        torrentHash = null
        scheduleCacheCheck()
    }

    // --- videos on a web page ----------------------------------------------------

    private var scanJob: kotlinx.coroutines.Job? = null

    /** Opens the pasted page and lists the videos it links to (see [DirectLinks.scanPage]). */
    fun findPageVideos() {
        val s = _state.value
        if (!s.canScanPage || s.scanning || s.submitting) return
        val url = (AddInputParser.parse(s.text).requests.single() as AddRequest.Link).url
        val host = DirectLinkParser.hostOf(url)
        _state.update { it.copy(scanning = true, error = null) }
        scanJob = viewModelScope.launch {
            val scan = try {
                directLinks.scanPage(url)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: ProviderException) {
                _state.update { it.copy(scanning = false, error = e.message) }
                return@launch
            } catch (e: Exception) {
                _state.update { it.copy(scanning = false, error = context.getString(R.string.page_err_open, host, e.message ?: e.javaClass.simpleName)) }
                return@launch
            }
            // Text changed while looking: the result is for another link.
            if (_state.value.text != s.text) {
                _state.update { it.copy(scanning = false) }
                return@launch
            }
            val error = when {
                scan == null -> context.getString(R.string.page_is_file)
                scan.videos.isNotEmpty() -> null
                scan.streams > 0 -> context.getString(R.string.page_streams_only, host)
                else -> context.getString(R.string.page_none, host)
            }
            _state.update {
                it.copy(
                    scanning = false,
                    error = error,
                    pageVideos = scan?.videos?.takeIf { v -> v.isNotEmpty() },
                    pageTitle = scan?.title,
                    // Only one: nothing to choose.
                    pickedVideos = scan?.videos?.singleOrNull()?.let { v -> setOf(v.id) } ?: emptySet()
                )
            }
        }
    }

    fun togglePageVideo(id: String) = _state.update {
        it.copy(pickedVideos = if (id in it.pickedVideos) it.pickedVideos - id else it.pickedVideos + id)
    }

    /** Back from the list to the link. */
    fun closePageVideos() = _state.update { it.copy(pageVideos = null, pickedVideos = emptySet(), error = null) }

    /** Queues the ticked videos as direct downloads. */
    private fun downloadPickedVideos(s: AddUiState) {
        val picked = s.pageVideos.orEmpty().filter { it.id in s.pickedVideos }
        if (picked.isEmpty()) return
        _state.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            if (!StorageAccess.canWrite(context, settings.downloadDirFlow.first())) {
                _state.update { it.copy(submitting = false, error = context.getString(R.string.add_needs_storage)) }
                return@launch
            }
            picked.forEach { controller.enqueue(it, foreground = true) }
            events.notifyListChanged(context.resources.getQuantityString(R.plurals.direct_added, picked.size, picked.size))
            resetForm()
        }
    }

    fun submit() {
        val s = _state.value
        if (s.pageVideos != null) {
            if (s.canSubmit) downloadPickedVideos(s)
            return
        }
        if (s.canSubmit && isYouTube(s.text)) {
            pendingAdds.offerYouTube(s.text)
            dismiss()
            return
        }
        val p = target ?: provider ?: return
        val active = provider
        if (!s.canSubmit) return
        _state.update { it.copy(submitting = true, error = null) }

        viewModelScope.launch {
            val parsed = AddInputParser.parse(s.text)
            // A .nzb link only becomes a Usenet job on a service that takes Usenet.
            val requests = parsed.requests.mapTo(mutableListOf()) { Nzb.route(it, p) }

            if (s.torrentUri != null) {
                when (val file = readTorrent(s.torrentUri, s.torrentName ?: "file.torrent", s.torrentIsNzb)) {
                    is DataResult.Success -> requests += file.data
                    is DataResult.Error -> {
                        _state.update { it.copy(submitting = false, error = file.message) }
                        return@launch
                    }
                }
            }

            if (requests.isEmpty()) {
                _state.update {
                    it.copy(submitting = false, error = context.getString(R.string.add_err_nothing))
                }
                return@launch
            }
            val unsupported = requests.filter { it.kind !in repository.addCapabilities(p) }.map { it.kind }.toSet()
            if (unsupported.isNotEmpty()) {
                _state.update {
                    it.copy(
                        submitting = false,
                        error = context.getString(R.string.add_err_unsupported, s.providerName)
                    )
                }
                return@launch
            }

            val canWrite = StorageAccess.canWrite(context, settings.downloadDirFlow.first())
            val direct = p == ProviderId.DIRECT
            // Direct links download right away: nowhere to keep them otherwise.
            if (direct && !canWrite) {
                _state.update { it.copy(submitting = false, error = context.getString(R.string.add_needs_storage)) }
                return@launch
            }
            val auto = (s.autoDownload || direct) && canWrite
            val keepExtras = settings.showExtraFilesFlow.first()

            // Sequential on purpose: TorBox limits uncached creations to
            // 60/hour, and sequential failures are easier to report.
            var added = 0
            var directFiles = 0
            var lastMessage = ""
            val failures = mutableListOf<String>()
            var notCached = 0
            // What went through: a retry after a partial result must not send it again.
            val addedKeys = mutableSetOf<String>()
            var fileAdded = false
            // Links are not torrents or Usenet jobs: nothing to check the cache of.
            val cachedOnly = s.onlyIfCached && s.canOnlyIfCached
            requests.forEach { req ->
                val result = if (cachedOnly && req !is AddRequest.Link) repository.addOnlyIfCached(p, req) else repository.add(p, req)
                when (val r = result) {
                    is DataResult.Success -> {
                        added++
                        when (req) {
                            is AddRequest.Magnet -> addedKeys += req.uri
                            is AddRequest.Link -> addedKeys += req.url
                            is AddRequest.NzbLink -> addedKeys += req.url
                            is AddRequest.TorrentFile, is AddRequest.NzbFile -> fileAdded = true
                        }
                        directFiles += r.data.readyFiles.size
                        lastMessage = r.data.message
                        if (auto) {
                            // Ready now (e.g. RD link): queue immediately.
                            // Minus the torrent's .txt/covers/samples unless wanted.
                            val ready = r.data.readyFiles
                            (if (keepExtras) ready else ExtraFiles.hide(ready)).forEach { controller.enqueue(it, foreground = true) }
                            // Still fetching (TorBox torrent): watch it.
                            r.data.jobRef?.let { ref -> watcher.watch(p, ref, displayNameFor(req), if (requests.size == 1) selectedValue(s.files, s.unticked) else null) }
                        } else r.data.jobRef?.let { ref ->
                            if (shouldWatchNotifyOnly(settings.notifyCloudReadyFlow.first(), false, ref, r.data.readyFiles.isNotEmpty())) watcher.watchNotifyOnly(p, ref, displayNameFor(req))
                        }
                    }
                    is DataResult.Error -> if (r.cause is NotCachedException) notCached++ else failures += r.message
                }
            }
            if (notCached > 0) {
                // Names the switch that caused it.
                val switchLabel = context.getString(R.string.add_only_if_cached, s.providerName)
                failures += if (notCached == requests.size) context.getString(R.string.add_not_cached, s.providerName, switchLabel)
                else context.getString(R.string.add_not_cached_some, notCached, s.providerName, switchLabel)
            }

            // Cached torrents are ready instantly — don't wait for the next poll.
            if (auto && added > 0) viewModelScope.launch { watcher.checkNow(fromBackground = false) }

            if (failures.isEmpty()) {
                val msg = if (added == 1) lastMessage
                else if (direct) context.resources.getQuantityString(R.plurals.direct_added, directFiles, directFiles)
                else context.resources.getQuantityString(R.plurals.add_added_n, added, added, s.providerName)
                // Sent somewhere the Library isn't showing: say where it went.
                val elsewhere = if (active != null && p != active && !direct) {
                    " " + context.getString(R.string.add_sent_elsewhere, s.providerName, auth.info(active).displayName)
                } else ""
                events.notifyListChanged(msg + elsewhere)
                torrentHash = null
                resetForm()
            } else {
                if (added > 0) events.notifyListChanged(context.getString(R.string.add_partial, added, requests.size))
                if (fileAdded) torrentHash = null
                _state.update {
                    it.copy(
                        submitting = false,
                        text = AddInputParser.withoutAdded(it.text, addedKeys),
                        torrentUri = if (fileAdded) null else it.torrentUri,
                        torrentName = if (fileAdded) null else it.torrentName,
                        torrentIsNzb = if (fileAdded) false else it.torrentIsNzb,
                        error = failures.distinct().joinToString("\n") +
                            if (added > 0) "\n" + context.getString(R.string.add_partial_suffix, added, requests.size) else ""
                    )
                }
            }
            if (parsed.ignored.isNotEmpty() && failures.isEmpty()) {
                events.notifyListChanged(
                    context.resources.getQuantityString(R.plurals.add_skipped_n, parsed.ignored.size, parsed.ignored.size)
                )
            }
        }
    }

    /** Human name for a watch: magnet "dn", file name, or the link's last segment. */
    private fun displayNameFor(req: AddRequest): String = when (req) {
        is AddRequest.Magnet -> Regex("[?&]dn=([^&]+)").find(req.uri)?.groupValues?.get(1)
            ?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
            ?: context.getString(R.string.add_magnet_link)
        is AddRequest.TorrentFile -> req.name.removeSuffix(".torrent")
        is AddRequest.NzbFile -> req.name.removeSuffix(".nzb")
        is AddRequest.Link -> Uri.parse(req.url).lastPathSegment ?: req.url
        is AddRequest.NzbLink -> Uri.parse(req.url).lastPathSegment?.removeSuffix(".nzb") ?: req.url
    }

    // --- file helpers ------------------------------------------------------

    private suspend fun readTorrent(uri: Uri, name: String, nzb: Boolean): DataResult<AddRequest> =
        withContext(Dispatchers.IO) {
            try {
                if (nzb) return@withContext readNzb(uri, name)
                val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                    // .torrent files are small; refuse anything absurd rather
                    // than reading it all into memory.
                    val buf = input.readNBytesCompat(MAX_TORRENT_BYTES + 1)
                    if (buf.size > MAX_TORRENT_BYTES) {
                        return@withContext DataResult.Error(context.getString(R.string.add_err_too_large))
                    }
                    buf
                } ?: return@withContext DataResult.Error(context.getString(R.string.add_err_open))
                // Bencoded torrents always start with 'd' (a dictionary).
                if (bytes.isEmpty() || bytes[0] != 'd'.code.toByte()) {
                    return@withContext DataResult.Error(context.getString(R.string.add_err_not_torrent, name))
                }
                DataResult.Success(AddRequest.TorrentFile(name, bytes))
            } catch (e: IOException) {
                DataResult.Error(context.getString(R.string.add_err_read, e.message.orEmpty()))
            } catch (e: SecurityException) {
                DataResult.Error(context.getString(R.string.add_err_permission))
            }
        }

    /**
     * Checks an .nzb without keeping it: one pass over the stream counts its
     * size and keeps only the first 64 KB to sniff. The upload reads the file
     * again, so memory use stays flat whatever the size.
     */
    private fun readNzb(uri: Uri, name: String): DataResult<AddRequest> {
        val head = ByteArray(Nzb.SNIFF_BYTES)
        var headLen = 0
        var total = 0L
        val buf = ByteArray(64 * 1024)
        context.contentResolver.openInputStream(uri)?.use { input ->
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                val take = minOf(n, head.size - headLen)
                if (take > 0) { System.arraycopy(buf, 0, head, headLen, take); headLen += take }
                total += n
                if (total > Nzb.MAX_BYTES) return DataResult.Error(context.getString(R.string.add_err_nzb_too_large, name))
            }
        } ?: return DataResult.Error(context.getString(R.string.add_err_open))
        val sniffed = head.copyOf(headLen)
        if (Nzb.isGzip(sniffed)) return DataResult.Error(context.getString(R.string.add_err_nzb_gzip, name))
        if (!Nzb.looksLikeNzb(sniffed)) return DataResult.Error(context.getString(R.string.add_err_not_nzb, name))
        return DataResult.Success(AddRequest.NzbFile(name, total) {
            context.contentResolver.openInputStream(uri) ?: throw IOException(context.getString(R.string.add_err_open))
        })
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
