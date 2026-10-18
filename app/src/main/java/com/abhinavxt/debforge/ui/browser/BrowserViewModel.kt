package com.abhinavxt.debforge.ui.browser

import android.content.Context
import android.webkit.URLUtil
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.abhinavxt.debforge.R
import com.abhinavxt.debforge.data.provider.ProviderException
import com.abhinavxt.debforge.data.provider.direct.DirectLinks
import com.abhinavxt.debforge.data.prefs.SettingsStore
import com.abhinavxt.debforge.domain.AddRequest
import com.abhinavxt.debforge.domain.DirectLinkParser
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

    private val _videos = MutableStateFlow<List<String>>(emptyList())
    /** Video file URLs the current page loaded, first seen first. */
    val videos: StateFlow<List<String>> = _videos.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    fun onPageStarted(url: String) {
        this.url = url
        _videos.value = emptyList()
    }

    /** Every request the page makes; called off the main thread. */
    fun onRequest(url: String) {
        if (!PageVideos.isSniffable(url)) return
        val clean = url.substringBefore('#')
        _videos.update { if (clean in it || it.size >= MAX_VIDEOS) it else it + clean }
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

    /** Looked up first (name, size), so a dead or forbidden link says so now, not in the queue. */
    fun downloadVideo(url: String) {
        viewModelScope.launch {
            val files = try {
                directLinks.add(AddRequest.Link(url)).readyFiles
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: ProviderException) {
                _messages.send(e.message.orEmpty())
                return@launch
            } catch (e: Exception) {
                _messages.send(context.getString(R.string.page_err_open, DirectLinkParser.hostOf(url), e.message ?: e.javaClass.simpleName))
                return@launch
            }
            queue(files)
        }
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
    }
}
