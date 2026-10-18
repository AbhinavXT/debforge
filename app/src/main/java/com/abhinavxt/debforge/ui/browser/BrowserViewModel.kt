package com.abhinavxt.debforge.ui.browser

import android.content.Context
import android.webkit.URLUtil
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.abhinavxt.debforge.R
import com.abhinavxt.debforge.data.provider.direct.DirectLinks
import com.abhinavxt.debforge.data.prefs.SettingsStore
import com.abhinavxt.debforge.domain.DownloadItem
import com.abhinavxt.debforge.domain.PageVideos
import com.abhinavxt.debforge.download.DownloadController
import com.abhinavxt.debforge.download.StorageAccess
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import javax.inject.Inject

/**
 * The Browser tab: every download a page starts goes to DebForge's queue
 * instead of the system, and video files the page loads (players built with
 * JavaScript included) are listed to download.
 *
 * Kept across tab switches; the WebView itself is not, it reloads [url].
 */
@HiltViewModel
class BrowserViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val controller: DownloadController,
    private val settings: SettingsStore,
    private val directLinks: DirectLinks
) : ViewModel() {

    var url: String = HOME
        private set

    private val _videos = MutableStateFlow<List<DownloadItem>>(emptyList())
    /** Videos the current page loaded, looked up (name, size), largest first: the main video before ad clips. */
    val videos: StateFlow<List<DownloadItem>> = _videos.asStateFlow()

    /** URLs already looked up on this page; guarded by itself (requests arrive on WebView threads). */
    private val seen = HashSet<String>()
    /** Bumped per page, so a lookup finishing after navigating away is dropped. */
    private var page = 0
    private val lookups = Semaphore(LOOKUPS_PARALLEL)

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    fun onPageStarted(url: String) {
        this.url = url
        synchronized(seen) {
            seen.clear()
            page++
        }
        _videos.value = emptyList()
    }

    /** Every request the page makes; called off the main thread. */
    fun onRequest(url: String) {
        if (!PageVideos.isSniffable(url)) return
        val clean = url.substringBefore('#')
        val forPage = synchronized(seen) {
            if (seen.size >= MAX_VIDEOS || !seen.add(clean)) return
            page
        }
        viewModelScope.launch {
            val video = lookups.withPermit { directLinks.lookUpVideo(clean) } ?: return@launch
            if (synchronized(seen) { forPage != page }) return@launch
            _videos.update { (it + video).distinctBy { v -> v.id }.sortedByDescending { v -> v.filesize } }
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
        viewModelScope.launch { queue(listOf(directLinks.browserFile(url, name, contentLength.coerceAtLeast(0)))) }
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
