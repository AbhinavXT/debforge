package com.abhinavxt.debforge.data.provider.direct

import android.content.Context
import com.abhinavxt.debforge.R
import com.abhinavxt.debforge.data.provider.AccountInfo
import com.abhinavxt.debforge.data.provider.AddResult
import com.abhinavxt.debforge.data.provider.DebridProvider
import com.abhinavxt.debforge.data.provider.ProviderException
import com.abhinavxt.debforge.data.provider.ProviderInfo
import com.abhinavxt.debforge.data.provider.ResolvedLink
import com.abhinavxt.debforge.di.DownloadHttpClient
import com.abhinavxt.debforge.domain.AddKind
import com.abhinavxt.debforge.domain.AddRequest
import com.abhinavxt.debforge.domain.DirectLinkParser
import com.abhinavxt.debforge.domain.DirectLinkParser.Target
import com.abhinavxt.debforge.domain.DownloadItem
import com.abhinavxt.debforge.domain.HlsPlaylist
import com.abhinavxt.debforge.domain.Page
import com.abhinavxt.debforge.domain.PageVideos
import com.abhinavxt.debforge.domain.ProviderId
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Downloads links straight to the device, no debrid service involved:
 * Pixeldrain files and lists, Google Drive files, and any plain file URL.
 *
 * Adding a link looks the file up (name and size) and returns it ready to
 * queue. The download engine then asks [resolveLink] for the URL like for
 * any service: Pixeldrain and Drive links are rebuilt each time (Drive's
 * confirmation link expires); a plain link is used as it is.
 *
 * Uses the download client: no service token is ever sent to these hosts.
 */
@Singleton
class DirectLinks @Inject constructor(
    @ApplicationContext private val context: Context,
    @DownloadHttpClient private val client: OkHttpClient
) : DebridProvider {

    override val info = ProviderInfo(
        id = ProviderId.DIRECT,
        displayName = "Direct",
        tokenUrl = "",
        tokenUrlLabel = "",
        tokenLabel = "",
        emptyListHint = "",
        websiteUrl = ""
    )

    override suspend fun validateToken(token: String): AccountInfo =
        throw ProviderException("Direct links need no sign-in")

    override suspend fun account(): AccountInfo =
        throw ProviderException("Direct links need no sign-in")

    /** Direct downloads live in the Downloads tab, not in a service library. */
    override suspend fun listFiles(page: Int, pageSize: Int): Page<DownloadItem> = Page(emptyList(), false)

    override val addCapabilities = setOf(AddKind.LINK)

    override suspend fun add(request: AddRequest): AddResult = withContext(Dispatchers.IO) {
        val url = (request as? AddRequest.Link)?.url
            ?: throw ProviderException(context.getString(R.string.direct_err_web_page, "?"))
        val items = when (val t = DirectLinkParser.classify(url)) {
            is Target.PixeldrainFile -> listOf(pixeldrainFile(t.id))
            is Target.PixeldrainList -> pixeldrainList(t.id)
            is Target.DriveFile -> listOf(driveFile(t.id))
            Target.DriveFolder -> throw ProviderException(context.getString(R.string.direct_err_folder))
            is Target.Plain -> listOf(plainFile(t.url))
            null -> throw ProviderException(context.getString(R.string.direct_err_web_page, url))
        }
        if (items.isEmpty()) throw ProviderException(context.getString(R.string.direct_err_folder))
        AddResult(
            message = context.resources.getQuantityString(R.plurals.direct_added, items.size, items.size),
            readyFiles = items
        )
    }

    override suspend fun resolveLink(sourceRef: String): ResolvedLink = withContext(Dispatchers.IO) {
        when {
            sourceRef.startsWith(PIXELDRAIN) ->
                ResolvedLink(DirectLinkParser.pixeldrainDownloadUrl(sourceRef.removePrefix(PIXELDRAIN)), null, null)
            sourceRef.startsWith(DRIVE) -> {
                val (link, probe) = driveResolve(sourceRef.removePrefix(DRIVE))
                ResolvedLink(link, null, probe.size.takeIf { it > 0 })
            }
            // The stream downloader reads the playlist itself.
            sourceRef.startsWith(DirectLinkParser.HLS_PREFIX) ->
                ResolvedLink(sourceRef.removePrefix(DirectLinkParser.HLS_PREFIX), 1, null)
            // A plain link can't be renewed: if it expired, the engine reports it.
            else -> ResolvedLink(sourceRef, null, null)
        }
    }

    // --- Pixeldrain -------------------------------------------------------------

    private fun pixeldrainFile(id: String): DownloadItem {
        val o = getJson(DirectLinkParser.pixeldrainInfoUrl(id), "pixeldrain.com")
        return item(
            sourceRef = PIXELDRAIN + id,
            url = DirectLinkParser.pixeldrainDownloadUrl(id),
            name = o.optString("name").ifBlank { "pixeldrain-$id" },
            size = o.optLong("size", 0L),
            host = "pixeldrain.com"
        )
    }

    private fun pixeldrainList(listId: String): List<DownloadItem> {
        val o = getJson(DirectLinkParser.pixeldrainListUrl(listId), "pixeldrain.com")
        val title = o.optString("title").ifBlank { null }
        val files = o.optJSONArray("files") ?: return emptyList()
        return (0 until files.length()).mapNotNull { i ->
            val f = files.optJSONObject(i) ?: return@mapNotNull null
            val id = f.optString("id").ifBlank { return@mapNotNull null }
            item(
                sourceRef = PIXELDRAIN + id,
                url = DirectLinkParser.pixeldrainDownloadUrl(id),
                name = f.optString("name").ifBlank { "pixeldrain-$id" },
                size = f.optLong("size", 0L),
                host = "pixeldrain.com",
                parentRef = "pixeldrain-list:$listId",
                parentName = title
            )
        }
    }

    // --- Google Drive -----------------------------------------------------------

    private fun driveFile(id: String): DownloadItem {
        val (link, probe) = driveResolve(id)
        return item(
            sourceRef = DRIVE + id,
            url = link,
            name = probe.name ?: DirectLinkParser.fallbackName(probe.contentType, id.take(8)),
            size = probe.size,
            host = "drive.google.com"
        )
    }

    /**
     * Drive's download link, past the "can't scan for viruses" page that big
     * files get. Any other page means Drive won't hand the file out.
     */
    private fun driveResolve(id: String): Pair<String, Probe> {
        val first = DirectLinkParser.driveDownloadUrl(id)
        val p1 = probe(first)
        if (!p1.webPage) return first to p1
        val confirm = DirectLinkParser.driveConfirmUrl(p1.html.orEmpty())
            ?: throw ProviderException(context.getString(R.string.direct_err_drive))
        val p2 = probe(confirm)
        if (p2.webPage) throw ProviderException(context.getString(R.string.direct_err_drive))
        return confirm to p2
    }

    // --- plain links --------------------------------------------------------------

    private fun plainFile(url: String): DownloadItem {
        val p = probe(url)
        if (p.webPage) {
            throw ProviderException(context.getString(R.string.direct_err_web_page, DirectLinkParser.hostOf(url)))
        }
        val tag = DirectLinkParser.itemId(url).removePrefix("direct:").take(8)
        return item(
            sourceRef = url,
            url = url,
            name = p.name ?: DirectLinkParser.fallbackName(p.contentType, tag),
            size = p.size,
            host = DirectLinkParser.hostOf(url)
        )
    }

    /** A file the in-app browser started downloading: it already knows the name and size. */
    fun browserFile(url: String, name: String, size: Long, referer: String?, userAgent: String?): DownloadItem =
        item(sourceRef = url, url = url, name = name, size = size, host = DirectLinkParser.hostOf(url), referer = referer, userAgent = userAgent)

    // --- videos on a web page ---------------------------------------------------

    /** What [scanPage] found: the page's videos, ready to queue, and how many were streams only. */
    class PageScan(val title: String?, val videos: List<DownloadItem>, val streams: Int)

    /**
     * The videos [url]'s page links to (see [PageVideos]), each checked for
     * its name and size; largest first. Null when [url] isn't a web page
     * (it's a file: add it as usual).
     */
    suspend fun scanPage(url: String): PageScan? = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).get().build()
        val (pageUrl, html) = client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw ProviderException(context.getString(R.string.direct_err_http, DirectLinkParser.hostOf(url), resp.code))
            }
            if (!DirectLinkParser.isWebPage(resp.header("Content-Type"))) return@withContext null
            // After redirects: relative links resolve against where we ended up.
            resp.request.url.toString() to resp.peekBody(MAX_SCAN_BYTES).string()
        }
        val found = PageVideos.find(html, pageUrl)
        val gate = Semaphore(PROBE_PARALLEL)
        val videos = coroutineScope {
            found.videos.take(MAX_CANDIDATES).map { video ->
                async {
                    gate.withPermit { lookUpVideo(video, parentRef = "page:$pageUrl", parentName = found.title) }
                }
            }.awaitAll().filterNotNull()
        }
        PageScan(found.title, videos.distinctBy { it.id }.sortedByDescending { it.filesize }, found.streams)
    }

    /**
     * [url] as a video ready to queue, with its name and size. Null when the
     * link is dead or forbidden, or isn't a video: it's just left out.
     * [referer] / [userAgent] (the in-app browser's) go with the lookup and the download.
     */
    suspend fun lookUpVideo(
        url: String,
        parentRef: String? = null,
        parentName: String? = null,
        referer: String? = null,
        userAgent: String? = null
    ): DownloadItem? =
        withContext(Dispatchers.IO) {
            val p = runCatching { probe(url, referer, userAgent) }.getOrNull() ?: return@withContext null
            if (p.webPage || !PageVideos.isVideo(p.name, p.contentType)) return@withContext null
            val tag = DirectLinkParser.itemId(url).removePrefix("direct:").take(8)
            item(
                sourceRef = url,
                url = url,
                name = p.name ?: DirectLinkParser.fallbackName(p.contentType, tag),
                size = p.size,
                host = DirectLinkParser.hostOf(url),
                parentRef = parentRef,
                parentName = parentName,
                referer = referer,
                userAgent = userAgent
            )
        }

    /**
     * [items]: a stream's qualities, ready to queue. [partOf]: URLs the page
     * loads for it that aren't to be offered on their own (its audio and
     * subtitles, the qualities not listed, the files its pieces come from).
     */
    class StreamScan(val items: List<DownloadItem>, val partOf: Set<String>)

    /**
     * The qualities of the HLS stream at [url], best first, ready to queue as
     * "[title] 1080p.ts" (.mp4 for fMP4 pieces or audio kept apart). Sizes are
     * estimates (bitrate x length). No items when the stream is live,
     * DRM-protected, or won't load.
     */
    suspend fun lookUpStream(url: String, title: String?, referer: String?, userAgent: String?): StreamScan =
        withContext(Dispatchers.IO) {
            val partOf = HashSet<String>()
            val streams = when (val top = runCatching { playlist(url, referer, userAgent) }.getOrNull()) {
                is HlsPlaylist.Master -> {
                    partOf += top.otherUrls
                    partOf += top.variants.drop(MAX_QUALITIES).map { it.url }
                    top.variants.take(MAX_QUALITIES).map { v -> v to runCatching { playlist(v.url, referer, userAgent) }.getOrNull() }
                }
                is HlsPlaylist.Media -> listOf(HlsPlaylist.Variant(url, 0L, null) to top)
                null -> emptyList()
            }
            streams.forEach { (_, media) ->
                if (media is HlsPlaylist.Media) partOf += (listOfNotNull(media.init) + media.segments).map { it.url }
            }
            // The engine cleans the name; titles can be whole sentences.
            val name = title?.trim()?.take(80)?.ifBlank { null } ?: DirectLinkParser.hostOf(url)
            val heights = streams.mapNotNull { it.first.height }
            val items = streams.mapNotNull { (v, media) ->
                if (media !is HlsPlaylist.Media || media.live || media.drm || media.segments.isEmpty()) return@mapNotNull null
                // Two qualities at one height (two bitrates): the bitrate tells their files apart.
                val rate = if (heights.count { it == v.height } > 1) " %.1f Mbps".format(v.bandwidth / 1e6) else ""
                val quality = v.height?.let { " ${it}p$rate" }.orEmpty()
                // Separate audio is combined with the video into an MP4.
                val ext = if (media.init != null || v.audioUrl != null) "mp4" else "ts"
                item(
                    sourceRef = DirectLinkParser.HLS_PREFIX + v.url,
                    url = v.url,
                    name = "$name$quality.$ext",
                    size = (v.bandwidth / 8 * media.durationSec).toLong(),
                    host = DirectLinkParser.hostOf(v.url),
                    referer = referer,
                    userAgent = userAgent
                ).copy(audioUrl = v.audioUrl)
            }
            StreamScan(items, partOf)
        }

    private fun playlist(url: String, referer: String?, userAgent: String?): HlsPlaylist.Playlist? {
        val req = get(url, referer, userAgent).build()
        return client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw ProviderException(context.getString(R.string.direct_err_http, DirectLinkParser.hostOf(url), resp.code))
            HlsPlaylist.parse(resp.peekBody(MAX_PAGE_BYTES * 8).string(), resp.request.url.toString())
        }
    }

    // --- HTTP -------------------------------------------------------------------

    private class Probe(
        val webPage: Boolean,
        val html: String?,
        val name: String?,
        val size: Long,
        val contentType: String?
    )

    /** One-byte ranged GET: name, size and type without downloading the file. */
    private fun probe(url: String, referer: String? = null, userAgent: String? = null): Probe {
        val req = get(url, referer, userAgent).header("Range", "bytes=0-0").build()
        return client.newCall(req).execute().use { resp ->
            if (resp.code != 200 && resp.code != 206) {
                throw ProviderException(context.getString(R.string.direct_err_http, DirectLinkParser.hostOf(url), resp.code))
            }
            val type = resp.header("Content-Type")
            if (DirectLinkParser.isWebPage(type)) {
                return@use Probe(true, resp.peekBody(MAX_PAGE_BYTES).string(), null, 0L, type)
            }
            val size = if (resp.code == 206) DirectLinkParser.contentRangeTotal(resp.header("Content-Range")) ?: 0L
            else resp.body?.contentLength()?.takeIf { it > 0 } ?: 0L
            // After redirects the final URL often carries the real name.
            val name = DirectLinkParser.filenameFromContentDisposition(resp.header("Content-Disposition"))
                ?: DirectLinkParser.filenameFromUrl(resp.request.url.toString())
                ?: DirectLinkParser.filenameFromUrl(url)
            Probe(false, null, name, size, type)
        }
    }

    /** The in-app browser's Referer / User-Agent go along when there are any. */
    private fun get(url: String, referer: String?, userAgent: String?): Request.Builder =
        Request.Builder().url(url).get().apply {
            referer?.let { header("Referer", it) }
            userAgent?.let { header("User-Agent", it) }
        }

    private fun getJson(url: String, host: String): JSONObject {
        val req = Request.Builder().url(url).get().build()
        return client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            val json = runCatching { JSONObject(body) }.getOrNull()
            if (!resp.isSuccessful || json == null) {
                // Pixeldrain explains itself: {"success":false,"message":"..."}
                val why = json?.optString("message")?.takeIf { it.isNotBlank() }
                throw ProviderException(
                    why?.let { "$host: $it" } ?: context.getString(R.string.direct_err_http, host, resp.code)
                )
            }
            json
        }
    }

    private fun item(
        sourceRef: String,
        url: String,
        name: String,
        size: Long,
        host: String,
        parentRef: String? = null,
        parentName: String? = null,
        referer: String? = null,
        userAgent: String? = null
    ) = DownloadItem(
        id = DirectLinkParser.itemId(sourceRef),
        provider = ProviderId.DIRECT,
        filename = name,
        sourceRef = sourceRef,
        downloadUrl = url,
        host = host,
        filesize = size,
        maxConnections = CONNECTIONS,
        addedAt = java.time.Instant.now().toString(),
        parentRef = parentRef,
        parentName = parentName,
        referer = referer,
        userAgent = userAgent
    )

    private companion object {
        const val PIXELDRAIN = "pixeldrain:"
        const val DRIVE = "gdrive:"
        /** Parallel connections per file: modest, these hosts throttle harder than debrid CDNs. */
        const val CONNECTIONS = 4
        const val MAX_PAGE_BYTES = 512L * 1024
        /** Pages with big inline scripts keep their player config near the end. */
        const val MAX_SCAN_BYTES = 4L * 1024 * 1024
        const val MAX_CANDIDATES = 40
        const val PROBE_PARALLEL = 6
        /** Qualities offered per stream (each costs a playlist fetch). */
        const val MAX_QUALITIES = 5
    }
}
