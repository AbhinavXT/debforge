package com.abhinavxt.debforge.data.youtube

import okhttp3.OkHttpClient
import okhttp3.Request as OkRequest
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException

/** NewPipe's HTTP layer on the app's OkHttp client. */
class NewPipeDownloader(private val client: OkHttpClient) : Downloader() {
    override fun execute(request: Request): Response {
        val b = OkRequest.Builder().url(request.url())
        request.headers().forEach { (k, v) -> b.removeHeader(k); v.forEach { b.addHeader(k, it) } }
        if (request.headers()["User-Agent"] == null) b.header("User-Agent", USER_AGENT)
        val method = request.httpMethod()
        val body = request.dataToSend()?.toRequestBody() ?: if (method == "POST") ByteArray(0).toRequestBody() else null
        b.method(method, body)
        client.newCall(b.build()).execute().use { r ->
            if (r.code == 429) throw ReCaptchaException("YouTube asked for a captcha", request.url())
            return Response(r.code, r.message, r.headers.toMultimap(), r.body?.string(), r.request.url.toString())
        }
    }

    internal companion object {
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; rv:128.0) Gecko/20100101 Firefox/128.0"
    }
}
