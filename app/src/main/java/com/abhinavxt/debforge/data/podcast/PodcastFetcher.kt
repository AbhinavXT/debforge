package com.abhinavxt.debforge.data.podcast

import android.content.Context
import com.abhinavxt.debforge.R
import com.abhinavxt.debforge.di.ApiHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Why a feed could not be fetched. */
class PodcastFetchException(val kind: Kind, val httpCode: Int? = null, cause: Throwable? = null) : Exception("Podcast feed: $kind ${httpCode ?: ""}", cause) {
    enum class Kind { NOT_A_FEED, TOO_BIG, NETWORK, HTTP }

    /** Retrying can never help: the feed is gone (404 / 410) or is not a podcast feed. */
    val permanent: Boolean get() = kind == Kind.NOT_A_FEED || (kind == Kind.HTTP && (httpCode == 404 || httpCode == 410))

    fun userMessage(context: Context): String = when (kind) {
        Kind.NOT_A_FEED -> context.getString(R.string.podcast_err_not_feed)
        Kind.TOO_BIG -> context.getString(R.string.podcast_err_too_big)
        Kind.NETWORK -> context.getString(R.string.podcast_err_network)
        Kind.HTTP -> context.getString(R.string.podcast_err_http, httpCode ?: 0)
    }
}

sealed interface PodcastFetchResult {
    /** The server said the feed is unchanged (304). */
    data object NotModified : PodcastFetchResult
    class Fresh(val feed: PodcastFeed, val etag: String?, val lastModified: String?) : PodcastFetchResult
}

/** Downloads and parses a podcast RSS feed, with a conditional GET and a size cap. */
@Singleton
class PodcastFetcher @Inject constructor(@ApiHttpClient http: OkHttpClient) {
    /** Shares the pool and dispatcher of [http], adds a whole-call timeout. */
    private val client: OkHttpClient = http.newBuilder().callTimeout(CALL_TIMEOUT_SEC, java.util.concurrent.TimeUnit.SECONDS).build()

    /** Throws [PodcastFetchException]; cancellation propagates. */
    suspend fun fetch(url: String, etag: String? = null, lastModified: String? = null): PodcastFetchResult = runInterruptible(Dispatchers.IO) {
        val request = try {
            Request.Builder().url(url).header("Accept", "application/rss+xml, application/xml, text/xml, */*;q=0.5")
                .apply { etag?.let { header("If-None-Match", it) }; lastModified?.let { header("If-Modified-Since", it) } }
                .build()
        } catch (e: IllegalArgumentException) {
            throw PodcastFetchException(PodcastFetchException.Kind.NOT_A_FEED, cause = e)
        }
        val call = client.newCall(request)
        try {
            call.execute().use { resp ->
                // A feed may not send us from https to plain http.
                if (request.isHttps && !resp.request.isHttps) throw PodcastFetchException(PodcastFetchException.Kind.NETWORK)
                if (resp.code == 304) return@runInterruptible PodcastFetchResult.NotModified
                if (!resp.isSuccessful) throw PodcastFetchException(PodcastFetchException.Kind.HTTP, resp.code)
                val body = resp.body ?: throw PodcastFetchException(PodcastFetchException.Kind.NOT_A_FEED)
                if (body.contentLength() > MAX_BYTES) throw PodcastFetchException(PodcastFetchException.Kind.TOO_BIG)
                val bytes = readCapped(body.byteStream())
                val feed = try {
                    RssParser.parse(bytes.inputStream())
                } catch (e: UnsupportedFeedException) {
                    throw PodcastFetchException(PodcastFetchException.Kind.NOT_A_FEED, cause = e)
                } catch (e: MalformedFeedException) {
                    throw PodcastFetchException(PodcastFetchException.Kind.NOT_A_FEED, cause = e)
                }
                PodcastFetchResult.Fresh(feed, resp.header("ETag"), resp.header("Last-Modified"))
            }
        } catch (e: PodcastFetchException) {
            throw e
        } catch (e: IOException) {
            // An interrupt from coroutine cancellation is turned into CancellationException by runInterruptible.
            throw PodcastFetchException(PodcastFetchException.Kind.NETWORK, cause = e)
        }
    }

    private fun readCapped(input: java.io.InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0L
        input.use {
            while (true) {
                val n = it.read(buf)
                if (n < 0) break
                total += n
                if (total > MAX_BYTES) throw PodcastFetchException(PodcastFetchException.Kind.TOO_BIG)
                out.write(buf, 0, n)
            }
        }
        return out.toByteArray()
    }

    companion object {
        const val CALL_TIMEOUT_SEC = 60L
        const val MAX_BYTES = 15L * 1024 * 1024
    }
}
