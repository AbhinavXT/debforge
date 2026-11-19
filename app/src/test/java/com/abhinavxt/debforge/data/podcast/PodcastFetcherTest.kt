package com.abhinavxt.debforge.data.podcast

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class PodcastFetcherTest {
    private val rss = """<?xml version="1.0"?><rss version="2.0"><channel><title>Show</title>
        <item><title>E1</title><guid>g1</guid><enclosure url="https://x.test/e1.mp3" type="audio/mpeg" length="1"/></item>
        </channel></rss>"""

    private var seen: Request? = null

    private fun fetcher(code: Int = 200, body: String = rss, headers: Map<String, String> = emptyMap(), fail: IOException? = null): PodcastFetcher {
        val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            seen = chain.request()
            if (fail != null) throw fail
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("m")
                .apply { headers.forEach { (k, v) -> header(k, v) } }
                .body(body.toResponseBody("application/rss+xml".toMediaType())).build()
        }).build()
        return PodcastFetcher(client)
    }

    private fun failure(block: suspend () -> Unit): PodcastFetchException {
        try {
            runBlocking { block() }
        } catch (e: PodcastFetchException) {
            return e
        }
        throw AssertionError("expected PodcastFetchException")
    }

    @Test fun parsesFeedAndReturnsValidators() {
        val r = runBlocking { fetcher(headers = mapOf("ETag" to "\"abc\"", "Last-Modified" to "Mon, 01 Jan 2024 00:00:00 GMT")).fetch("https://x.test/feed") }
        r as PodcastFetchResult.Fresh
        assertEquals("Show", r.feed.title)
        assertEquals("\"abc\"", r.etag)
        assertEquals("Mon, 01 Jan 2024 00:00:00 GMT", r.lastModified)
    }

    @Test fun sendsConditionalHeaders() {
        val f = fetcher(code = 304, body = "")
        val r = runBlocking { f.fetch("https://x.test/feed", etag = "\"abc\"", lastModified = "yesterday") }
        assertEquals(PodcastFetchResult.NotModified, r)
        assertEquals("\"abc\"", seen!!.header("If-None-Match"))
        assertEquals("yesterday", seen!!.header("If-Modified-Since"))
    }

    @Test fun noConditionalHeadersWhenUnknown() {
        runBlocking { fetcher().fetch("https://x.test/feed") }
        assertNull(seen!!.header("If-None-Match"))
        assertNull(seen!!.header("If-Modified-Since"))
    }

    @Test fun oversizedBodyIsRejected() {
        val big = rss + " ".repeat((PodcastFetcher.MAX_BYTES + 10).toInt())
        assertEquals(PodcastFetchException.Kind.TOO_BIG, failure { fetcher(body = big).fetch("https://x.test/feed") }.kind)
    }

    @Test fun badXmlIsNotAFeed() {
        assertEquals(PodcastFetchException.Kind.NOT_A_FEED, failure { fetcher(body = "<rss><channel>").fetch("https://x.test/feed") }.kind)
        assertEquals(PodcastFetchException.Kind.NOT_A_FEED, failure { fetcher(body = "<html><body>hi</body></html>").fetch("https://x.test/feed") }.kind)
    }

    @Test fun atomIsNotAFeed() {
        val e = failure { fetcher(body = """<feed xmlns="http://www.w3.org/2005/Atom"></feed>""").fetch("https://x.test/feed") }
        assertEquals(PodcastFetchException.Kind.NOT_A_FEED, e.kind)
        assertTrue(e.permanent)
    }

    @Test fun httpErrorsKeepTheCode() {
        val gone = failure { fetcher(code = 410, body = "").fetch("https://x.test/feed") }
        assertEquals(PodcastFetchException.Kind.HTTP, gone.kind)
        assertEquals(410, gone.httpCode)
        assertTrue(gone.permanent)
        assertTrue(failure { fetcher(code = 404, body = "").fetch("https://x.test/feed") }.permanent)
        assertTrue(!failure { fetcher(code = 503, body = "").fetch("https://x.test/feed") }.permanent)
    }

    @Test fun networkFailureIsTransient() {
        val e = failure { fetcher(fail = IOException("boom")).fetch("https://x.test/feed") }
        assertEquals(PodcastFetchException.Kind.NETWORK, e.kind)
        assertTrue(!e.permanent)
    }

    @Test fun invalidUrlIsNotAFeed() {
        assertEquals(PodcastFetchException.Kind.NOT_A_FEED, failure { fetcher().fetch("not a url") }.kind)
    }

    @Test fun httpsToHttpRedirectIsRefused() {
        val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            Response.Builder().request(chain.request().newBuilder().url("http://x.test/feed").build()).protocol(Protocol.HTTP_1_1)
                .code(200).message("m").body(rss.toResponseBody("application/rss+xml".toMediaType())).build()
        }).build()
        val e = failure { PodcastFetcher(client).fetch("https://x.test/feed") }
        assertEquals(PodcastFetchException.Kind.NETWORK, e.kind)
    }
}
