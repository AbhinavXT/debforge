package com.abhinavxt.debforge.ui.browser

import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlin.random.Random

/**
 * Element hiding for the Browser tab: as each page and frame starts, a tiny
 * script asks [stylesheetFor] for that address's stylesheet and adds it, so ad boxes
 * never show. The bridge's name is random per launch, so pages can't look
 * for DebForge by it.
 */
internal class AdHiding(private val stylesheetFor: (String) -> String) {

    private val name = "_" + (1..12).map { ('a'..'z').random(Random) }.joinToString("")
    private val script = """
        (function(){try{var b=window.$name;if(!b)return;var c=b.css(location.href);if(!c)return;
        var s=document.createElement('style');s.textContent=c;(document.head||document.documentElement).appendChild(s);}catch(e){}})();
    """.trimIndent()
    private val atStart = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)

    @JavascriptInterface
    fun css(url: String): String = stylesheetFor(url)

    /** Before the first page loads. */
    fun install(webView: WebView) {
        webView.addJavascriptInterface(this, name)
        if (atStart) WebViewCompat.addDocumentStartJavaScript(webView, script, setOf("*"))
    }

    /** WebViews too old to run it at the start get it once the page shows. */
    fun onPageVisible(webView: WebView) {
        if (!atStart) webView.evaluateJavascript(script, null)
    }
}
