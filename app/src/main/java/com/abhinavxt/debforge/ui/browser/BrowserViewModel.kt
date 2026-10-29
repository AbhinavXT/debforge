package com.abhinavxt.debforge.ui.browser

import android.content.Context
import android.net.Uri
import android.webkit.URLUtil
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.abhinavxt.debforge.R
import com.abhinavxt.debforge.data.adblock.FilterLists
import com.abhinavxt.debforge.data.provider.direct.DirectLinks
import com.abhinavxt.debforge.data.prefs.SettingsStore
import com.abhinavxt.debforge.domain.AdHosts
import com.abhinavxt.debforge.domain.DownloadItem
import com.abhinavxt.debforge.domain.PageVideos
import com.abhinavxt.debforge.domain.adblock.PageFlags
import com.abhinavxt.debforge.domain.adblock.RequestType
import com.abhinavxt.debforge.download.DownloadController
import com.abhinavxt.debforge.download.StorageAccess
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import javax.inject.Inject

/**
 * The Browser tab: every download a page starts goes to DebForge's queue
 * instead of the system, and video files and HLS streams the page loads
 * (players built with JavaScript included) are listed to download.
 *
 * Ads and trackers are blocked with uBlock Origin's lists ([FilterLists]);
 * until those are on the phone, with the short built-in [AdHosts].
 *
 * Kept across tab switches; the WebView itself is not, it reloads [url].
 */
@HiltViewModel
class BrowserViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val controller: DownloadController,
    private val settings: SettingsStore,
    private val directLinks: DirectLinks,
    private val filterLists: FilterLists
) : ViewModel() {

    var url: String = HOME
        private set

    /** The WebView's own, sent with this tab's downloads: some hosts refuse other clients. */
    var userAgent: String? = null

    /** The page's title, to name stream downloads by. */
    @Volatile var title: String? = null

    private val _videos = MutableStateFlow<List<DownloadItem>>(emptyList())
    /** Videos the current page loaded, looked up (name, size), largest first: the main video before ad clips. */
    val videos: StateFlow<List<DownloadItem>> = _videos.asStateFlow()

    /** URLs already looked up on this page; guarded by itself (requests arrive on WebView threads). */
    private val seen = HashSet<String>()
    /** URLs that belong to a stream on this page (its audio, its pieces' files): never listed on their own. Guarded by [seen]. */
    private val partOfStream = HashSet<String>()
    /** Bumped per page, so a lookup finishing after navigating away is dropped. */
    private var page = 0
    private val lookups = Semaphore(LOOKUPS_PARALLEL)

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    data class AdBlock(
        val enabled: Boolean = true,
        /** The page's site ("example.com"); blank on pages without one. */
        val site: String = "",
        val onHere: Boolean = true,
        /** Requests and pages stopped since the page started loading. */
        val blocked: Int = 0,
        val lists: FilterLists.Status = FilterLists.Status()
    )

    @Volatile private var adBlockOn = true
    @Volatile private var offSites: Set<String> = emptySet()
    /** Whether anything is blocked on the page showing now: set as each page starts. */
    @Volatile private var blockingHere = true
    private val _site = MutableStateFlow("")
    private val _blocked = MutableStateFlow(0)

    val adBlock: StateFlow<AdBlock> = combine(
        settings.adBlockFlow, settings.adBlockOffSitesFlow, _site, _blocked, filterLists.status
    ) { on, off, site, blocked, lists ->
        AdBlock(on, site, site.isEmpty() || !isOff(site, off), blocked, lists)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, AdBlock())

    init {
        viewModelScope.launch {
            combine(settings.adBlockFlow, settings.adBlockOffSitesFlow) { on, off -> on to off }.collect { (on, off) ->
                adBlockOn = on
                offSites = off
                if (on) filterLists.prepare()
                updateBlockingHere()
            }
        }
    }

    fun setAdBlock(on: Boolean) = viewModelScope.launch { settings.setAdBlock(on) }

    fun setAdBlockHere(on: Boolean) {
        val site = _site.value.ifEmpty { return }
        viewModelScope.launch { settings.setAdBlockOnSite(site, on) }
    }

    fun updateFilterLists() = filterLists.refresh()

    /** A request the page makes: true when it must not load. Called off the main thread. */
    fun blocksRequest(url: String, referer: String?, accept: String?, requestedWith: String?): Boolean {
        if (!blockingHere) return false
        val blocked = AdHosts.blocks(Uri.parse(url).host) ||
            filterLists.filters.value?.blocks(url, referer ?: this.url, RequestType.infer(url, accept, requestedWith)) == true
        if (blocked) _blocked.update { it + 1 }
        return blocked
    }

    /** The page is taking the browser to [url] (a link, a redirect, a pop-up): true to stay put. */
    fun blocksNavigation(url: String): Boolean {
        if (!blockingHere) return false
        val host = Uri.parse(url).host
        val blocked = AdHosts.blocks(host) || filterLists.filters.value?.blocks(url, this.url, RequestType.NAVIGATION) == true
        if (blocked) {
            _blocked.update { it + 1 }
            _messages.trySend(context.getString(R.string.browser_adblock_stopped, host.orEmpty()))
        }
        return blocked
    }

    /** The ad-hiding stylesheet for a page or frame at [frameUrl]. Called off the main thread. */
    fun hidingCss(frameUrl: String): String =
        if (!blockingHere) "" else filterLists.filters.value?.css(frameUrl).orEmpty()

    private fun updateBlockingHere() {
        val site = _site.value
        blockingHere = adBlockOn && (site.isEmpty() || !isOff(site, offSites)) &&
            ((filterLists.filters.value?.pageFlags(url) ?: 0) and PageFlags.NETWORK) == 0
    }

    private fun isOff(site: String, off: Set<String>): Boolean =
        off.any { site == it || site.endsWith(".$it") }

    fun onPageStarted(url: String) {
        this.url = url
        title = null
        _site.value = Uri.parse(url).host?.lowercase()?.removePrefix("www.").orEmpty()
        _blocked.value = 0
        updateBlockingHere()
        synchronized(seen) {
            seen.clear()
            partOfStream.clear()
            page++
        }
        _videos.value = emptyList()
    }

    /** Every request the page makes, with the page that made it; called off the main thread. */
    fun onRequest(url: String, referer: String?) {
        val stream = PageVideos.isHlsPlaylist(url)
        if (!stream && !PageVideos.isSniffable(url)) return
        val clean = url.substringBefore('#')
        val forPage = synchronized(seen) {
            if (seen.size >= MAX_VIDEOS || clean in partOfStream || !seen.add(clean)) return
            page
        }
        viewModelScope.launch {
            val from = referer ?: this@BrowserViewModel.url
            val scan = lookups.withPermit {
                if (stream) directLinks.lookUpStream(clean, title, from, userAgent)
                else DirectLinks.StreamScan(listOfNotNull(directLinks.lookUpVideo(clean, referer = from, userAgent = userAgent)), emptySet())
            }
            // Lookups finish in any order: a stream's audio or piece file may already be listed.
            val partOf = synchronized(seen) {
                if (forPage != page) return@launch
                partOfStream += scan.partOf
                partOfStream.toSet()
            }
            // A quality seen both in its stream's list and on its own: keep the entry with the size estimate.
            _videos.update { listed ->
                (listed + scan.items).filter { v -> v.downloadUrl !in partOf }
                    .sortedByDescending { v -> v.filesize }.distinctBy { v -> v.id }
            }
        }
    }

    /** The page started a download (a link, a button, a redirect to a file). */
    fun onDownload(url: String, contentDisposition: String?, mimeType: String?, contentLength: Long) {
        // blob: / data: files exist only inside the page.
        if (!URLUtil.isNetworkUrl(url)) {
            _messages.trySend(context.getString(R.string.browser_err_blob))
            return
        }
        val name = URLUtil.guessFileName(url, contentDisposition, mimeType)
        // The page showing now is the one that started it.
        val file = directLinks.browserFile(url, name, contentLength.coerceAtLeast(0), referer = this.url, userAgent = userAgent)
        viewModelScope.launch { queue(listOf(file)) }
    }

    fun downloadVideo(video: DownloadItem) {
        viewModelScope.launch { queue(listOf(video)) }
    }

    private suspend fun queue(files: List<DownloadItem>) {
        if (!StorageAccess.canWrite(context, settings.downloadDirFlow.first())) {
            _messages.send(context.getString(R.string.add_needs_storage))
            return
        }
        files.forEach { controller.enqueue(it) }
        files.singleOrNull()?.let { _messages.send(context.getString(R.string.browser_queued, it.filename)) }
            ?: _messages.send(context.resources.getQuantityString(R.plurals.direct_added, files.size, files.size))
    }

    private companion object {
        const val HOME = "https://duckduckgo.com/"
        /** A page streaming in .mp4 pieces would otherwise list hundreds. */
        const val MAX_VIDEOS = 50
        const val LOOKUPS_PARALLEL = 4
    }
}
