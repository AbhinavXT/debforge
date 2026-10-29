package com.abhinavxt.debforge.ui.browser

import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.text.format.DateUtils
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.outlined.RemoveModerator
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.ByteArrayInputStream
import java.text.NumberFormat
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.abhinavxt.debforge.R
import com.abhinavxt.debforge.domain.DirectLinkParser
import com.abhinavxt.debforge.ui.browse.formatSize

/**
 * A plain browser whose downloads land in DebForge's queue. Magnet links
 * open the Add dialog ([onMagnet]); other app links (intent:, market:) are
 * ignored. Ads are blocked and hidden; the shield in the address bar shows
 * how many and turns it off for a site.
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(onMagnet: (String) -> Unit, viewModel: BrowserViewModel = hiltViewModel()) {
    val videos by viewModel.videos.collectAsStateWithLifecycle()
    val adBlock by viewModel.adBlock.collectAsStateWithLifecycle()
    var showAdBlock by remember { mutableStateOf(false) }
    val adHiding = remember { AdHiding(viewModel::hidingCss) }
    val snackbar = remember { SnackbarHostState() }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var address by remember { mutableStateOf(viewModel.url) }
    var progress by remember { mutableIntStateOf(100) }
    var canGoBack by remember { mutableStateOf(false) }
    var showVideos by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var fullscreen by remember { mutableStateOf<Pair<View, WebChromeClient.CustomViewCallback>?>(null) }
    val activity = LocalContext.current as Activity

    LaunchedEffect(Unit) { viewModel.messages.collect { snackbar.showSnackbar(it) } }
    BackHandler(enabled = canGoBack) { webView?.goBack() }
    // Declared after the one above, so it wins while a video is full screen.
    BackHandler(enabled = fullscreen != null) {
        fullscreen?.second?.onCustomViewHidden()
        fullscreen = null
    }
    // A page's player went full screen: its view covers the whole window, in landscape, bars hidden.
    DisposableEffect(fullscreen) {
        val view = fullscreen?.first ?: return@DisposableEffect onDispose {}
        val decor = activity.window.decorView as ViewGroup
        val bars = WindowInsetsControllerCompat(activity.window, decor)
        val orientation = activity.requestedOrientation
        decor.addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        bars.hide(WindowInsetsCompat.Type.systemBars())
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose {
            decor.removeView(view)
            bars.show(WindowInsetsCompat.Type.systemBars())
            activity.requestedOrientation = orientation
        }
    }
    DisposableEffect(Unit) { onDispose { webView?.destroy() } }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (videos.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { showVideos = true },
                    icon = { Icon(Icons.Filled.Download, contentDescription = null) },
                    text = { Text(pluralStringResource(R.plurals.browser_videos_n, videos.size, videos.size)) }
                )
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { webView?.goBack() }, enabled = canGoBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.page_back))
                }
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    modifier = Modifier.weight(1f).padding(vertical = 4.dp),
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.browser_address_hint)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = {
                        if (address.isNotBlank()) webView?.loadUrl(DirectLinkParser.addressToUrl(address))
                        keyboard?.hide()
                        focus.clearFocus()
                    })
                )
                val adBlockOn = adBlock.enabled && adBlock.onHere
                // The badge sits on the button, outside its round clip.
                BadgedBox(badge = {
                    if (adBlockOn && adBlock.blocked > 0) {
                        Badge(Modifier.offset(x = (-8).dp, y = 8.dp)) { Text(if (adBlock.blocked > 99) "99+" else "${adBlock.blocked}") }
                    }
                }) {
                    IconButton(onClick = { showAdBlock = true }) {
                        Icon(
                            if (adBlockOn) Icons.Filled.Shield else Icons.Outlined.RemoveModerator,
                            contentDescription = stringResource(R.string.browser_adblock)
                        )
                    }
                }
                IconButton(onClick = { webView?.reload() }) {
                    Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.action_refresh))
                }
            }
            if (progress < 100) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
            Box(Modifier.weight(1f)) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            viewModel.userAgent = settings.userAgentString
                            // Logins that go through another site (Google, a CDN) need these.
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                            adHiding.install(this)
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                    val url = request.url.toString()
                                    if (url.startsWith("magnet:", ignoreCase = true)) {
                                        onMagnet(url)
                                        return true
                                    }
                                    if (request.url.scheme !in setOf("http", "https")) return true
                                    // Pop-unders and redirects to ad and scam pages go nowhere.
                                    return request.isForMainFrame && viewModel.blocksNavigation(url)
                                }

                                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                                    if (request.isForMainFrame) return null
                                    val url = request.url.toString()
                                    val headers = request.requestHeaders
                                    val referer = headers["Referer"]
                                    // An empty answer: the ad (and its video) never loads.
                                    if (viewModel.blocksRequest(url, referer, headers["Accept"], headers["X-Requested-With"])) {
                                        return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                                    }
                                    viewModel.onRequest(url, referer)
                                    return null
                                }

                                override fun onPageCommitVisible(view: WebView, url: String) {
                                    adHiding.onPageVisible(view)
                                }

                                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                                    viewModel.onPageStarted(url)
                                    address = url
                                }

                                override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                                    canGoBack = view.canGoBack()
                                    address = url
                                }
                            }
                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView, newProgress: Int) {
                                    progress = newProgress
                                }

                                override fun onReceivedTitle(view: WebView, title: String?) {
                                    viewModel.title = title
                                }

                                override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                                    fullscreen = view to callback
                                }

                                override fun onHideCustomView() {
                                    fullscreen = null
                                }
                            }
                            setDownloadListener { url, _, contentDisposition, mimeType, contentLength ->
                                // A load that turned into a download never finishes: the page stays as it was.
                                progress = 100
                                address = this.url ?: address
                                viewModel.onDownload(url, contentDisposition, mimeType, contentLength)
                            }
                            loadUrl(viewModel.url)
                            webView = this
                        }
                    }
                )
            }
        }
    }

    if (showAdBlock) {
        AdBlockSheet(
            state = adBlock,
            onDismiss = { showAdBlock = false },
            onEnabled = { viewModel.setAdBlock(it); webView?.reload() },
            onHere = { viewModel.setAdBlockHere(it); webView?.reload() },
            onUpdate = viewModel::updateFilterLists
        )
    }

    if (showVideos) {
        ModalBottomSheet(onDismissRequest = { showVideos = false }) {
            Text(
                stringResource(R.string.browser_videos_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            LazyColumn {
                items(videos, key = { it.id }) { v ->
                    ListItem(
                        headlineContent = { Text(v.filename, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            // A stream's size is bitrate x length: close, not exact.
                            val approx = if (v.sourceRef.startsWith(DirectLinkParser.HLS_PREFIX)) "~" else ""
                            Text(if (v.filesize > 0) "$approx${formatSize(v.filesize)} · ${v.host}" else v.host)
                        },
                        trailingContent = { Icon(Icons.Filled.Download, contentDescription = null) },
                        modifier = Modifier.clickable {
                            viewModel.downloadVideo(v)
                            showVideos = false
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdBlockSheet(
    state: BrowserViewModel.AdBlock,
    onDismiss: () -> Unit,
    onEnabled: (Boolean) -> Unit,
    onHere: (Boolean) -> Unit,
    onUpdate: () -> Unit
) {
    // Rows on the sheet's own colour, not a band of surface colour.
    val rowColors = ListItemDefaults.colors(containerColor = Color.Transparent)
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            stringResource(R.string.browser_adblock),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        if (state.enabled && state.onHere) {
            Text(
                pluralStringResource(R.plurals.browser_adblock_blocked_n, state.blocked, state.blocked),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
        ListItem(
            colors = rowColors,
            headlineContent = { Text(stringResource(R.string.browser_adblock_block)) },
            supportingContent = { Text(stringResource(R.string.browser_adblock_block_detail)) },
            trailingContent = { Switch(checked = state.enabled, onCheckedChange = onEnabled) }
        )
        if (state.enabled && state.site.isNotEmpty()) {
            ListItem(
                colors = rowColors,
                headlineContent = { Text(stringResource(R.string.browser_adblock_on_site, state.site)) },
                supportingContent = { Text(stringResource(R.string.browser_adblock_on_site_detail)) },
                trailingContent = { Switch(checked = state.onHere, onCheckedChange = onHere) }
            )
        }
        if (state.enabled) {
            val lists = state.lists
            ListItem(
                colors = rowColors,
                headlineContent = { Text(stringResource(R.string.browser_adblock_lists)) },
                supportingContent = {
                    Text(
                        when {
                            lists.updating -> stringResource(R.string.browser_adblock_lists_updating)
                            lists.failed && lists.filterCount == 0 -> stringResource(R.string.browser_adblock_lists_failed_none)
                            lists.failed -> stringResource(R.string.browser_adblock_lists_failed)
                            lists.filterCount == 0 -> stringResource(R.string.browser_adblock_lists_none)
                            else -> stringResource(
                                R.string.browser_adblock_lists_detail,
                                NumberFormat.getInstance().format(lists.filterCount),
                                DateUtils.getRelativeTimeSpanString(lists.updatedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
                            )
                        }
                    )
                },
                trailingContent = {
                    if (lists.updating) CircularProgressIndicator(Modifier.padding(horizontal = 12.dp).size(24.dp), strokeWidth = 2.dp)
                    else TextButton(onClick = onUpdate) { Text(stringResource(R.string.browser_adblock_update)) }
                }
            )
        }
    }
}
