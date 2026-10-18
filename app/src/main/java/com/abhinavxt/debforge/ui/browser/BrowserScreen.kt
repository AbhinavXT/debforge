package com.abhinavxt.debforge.ui.browser

import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.abhinavxt.debforge.R
import com.abhinavxt.debforge.domain.DirectLinkParser

/**
 * A plain browser whose downloads land in DebForge's queue. Magnet links
 * open the Add dialog ([onMagnet]); other app links (intent:, market:) are
 * ignored.
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(onMagnet: (String) -> Unit, viewModel: BrowserViewModel = hiltViewModel()) {
    val videos by viewModel.videos.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val focus = LocalFocusManager.current
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
                        focus.clearFocus()
                    })
                )
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
                            // Logins that go through another site (Google, a CDN) need these.
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                    val url = request.url.toString()
                                    if (url.startsWith("magnet:", ignoreCase = true)) {
                                        onMagnet(url)
                                        return true
                                    }
                                    return request.url.scheme !in setOf("http", "https")
                                }

                                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                                    if (!request.isForMainFrame) viewModel.onRequest(request.url.toString())
                                    return null
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

                                override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                                    fullscreen = view to callback
                                }

                                override fun onHideCustomView() {
                                    fullscreen = null
                                }
                            }
                            setDownloadListener { url, _, contentDisposition, mimeType, contentLength ->
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

    if (showVideos) {
        ModalBottomSheet(onDismissRequest = { showVideos = false }) {
            Text(
                stringResource(R.string.browser_videos_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            LazyColumn {
                items(videos, key = { it }) { url ->
                    ListItem(
                        headlineContent = {
                            Text(DirectLinkParser.filenameFromUrl(url) ?: url, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        },
                        supportingContent = { Text(DirectLinkParser.hostOf(url)) },
                        trailingContent = { Icon(Icons.Filled.Download, contentDescription = null) },
                        modifier = Modifier.clickable {
                            viewModel.downloadVideo(url)
                            showVideos = false
                        }
                    )
                }
            }
        }
    }
}
