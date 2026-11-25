package com.abhinavxt.debforge.data.provider.offcloud

import com.abhinavxt.debforge.data.provider.AccountInfo
import com.abhinavxt.debforge.data.provider.AddResult
import com.abhinavxt.debforge.data.provider.DebridProvider
import com.abhinavxt.debforge.data.provider.JobFailedException
import com.abhinavxt.debforge.data.provider.ProviderException
import com.abhinavxt.debforge.data.provider.ProviderInfo
import com.abhinavxt.debforge.data.provider.RemoteJob
import com.abhinavxt.debforge.data.provider.ResolvedLink
import com.abhinavxt.debforge.data.prefs.TokenStore
import com.abhinavxt.debforge.domain.AddKind
import com.abhinavxt.debforge.domain.AddRequest
import com.abhinavxt.debforge.domain.DownloadItem
import com.abhinavxt.debforge.domain.Page
import com.abhinavxt.debforge.domain.ProviderId
import com.abhinavxt.debforge.domain.TorrentHash
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import okhttp3.ResponseBody
import retrofit2.HttpException
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import java.net.URLEncoder
import java.time.Instant
import java.time.OffsetDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Offcloud API (https://github.com/offcloud/offcloud-api, plus the newer
 * endpoints current clients use: account/info, cloud/history, cloud/remove).
 * Auth: `Authorization: Bearer <API key>` (added by AuthInterceptor). Bodies
 * are JSON. A bad key makes every endpoint answer HTTP 401
 * `{"error":"NOAUTH"}`; other errors may come back as HTTP 200 with
 * `{"error": "..."}`, and a missing add-on as
 * `{"not_available": "premium" | "cloud" | ...}`.
 *
 * Offcloud has no torrent/file split: everything is a cloud "request". A
 * single-file request downloads from `https://<server>.offcloud.com/cloud/
 * download/<requestId>/<fileName>`; a folder (most torrents) lists its files
 * through cloud/explore.
 */
interface OffcloudApi {

    @GET("account/info")
    suspend fun accountInfo(): OcAccount

    @GET("account/info")
    suspend fun validate(@Header("Authorization") bearer: String): OcAccount

    @GET("cloud/history")
    suspend fun history(): List<OcRequest>

    /** `{"files": [{path, size, url}]}` with format=detailed; older servers return `["url", ...]`. */
    @GET("cloud/explore/{id}")
    suspend fun explore(@Path("id") requestId: String, @Query("format") format: String = "detailed"): ResponseBody

    @POST("cloud")
    suspend fun addCloud(@Body body: OcUrlBody): OcRequest

    @POST("cloud/remove")
    suspend fun removeMany(@Body body: OcRemoveBody): ResponseBody

    @GET("cloud/remove/{id}")
    suspend fun removeOne(@Path("id") requestId: String): ResponseBody

    /** `{"cachedItems": ["<hash>", ...]}` */
    @POST("cache")
    suspend fun cache(@Body body: OcCacheBody): OcCache

    companion object {
        const val BASE_URL = "https://offcloud.com/api/"
    }
}

@JsonClass(generateAdapter = true)
data class OcUrlBody(@Json(name = "url") val url: String)

@JsonClass(generateAdapter = true)
data class OcRemoveBody(@Json(name = "requestIds") val requestIds: List<String>)

@JsonClass(generateAdapter = true)
data class OcCacheBody(@Json(name = "hashes") val hashes: List<String>)

@JsonClass(generateAdapter = true)
data class OcCache(
    @Json(name = "error") val error: String? = null,
    @Json(name = "cachedItems") val cachedItems: List<String>? = null
)

/** Newer and older servers name these differently; read both. */
@JsonClass(generateAdapter = true)
data class OcAccount(
    @Json(name = "error") val error: String? = null,
    @Json(name = "email") val email: String? = null,
    @Json(name = "user_id") val userIdNew: String? = null,
    @Json(name = "userId") val userIdOld: String? = null,
    @Json(name = "is_premium") val premiumNew: Boolean? = null,
    @Json(name = "isPremium") val premiumOld: Boolean? = null,
    @Json(name = "expiration_date") val expiresNew: String? = null,
    @Json(name = "expirationDate") val expiresOld: String? = null
)

/** One cloud request: a torrent, a link, or the result of adding one. */
@JsonClass(generateAdapter = true)
data class OcRequest(
    @Json(name = "error") val error: String? = null,
    @Json(name = "not_available") val notAvailable: String? = null,
    @Json(name = "requestId") val requestId: String? = null,
    @Json(name = "fileName") val fileName: String? = null,
    /** created, queued, downloading, downloaded, error, canceled */
    @Json(name = "status") val status: String? = null,
    @Json(name = "originalLink") val originalLink: String? = null,
    @Json(name = "isDirectory") val isDirectory: Boolean? = null,
    @Json(name = "server") val server: String? = null,
    @Json(name = "createdOn") val createdOn: String? = null,
    @Json(name = "fileSize") val fileSize: Long? = null,
    /** Direct link, present on some add responses for single files. */
    @Json(name = "url") val url: String? = null
)

/** A file inside a folder request, from cloud/explore. */
data class OcFile(val path: String, val size: Long?, val url: String)

/** Pure parsing, kept apart from the provider so it can be unit-tested. */
object OffcloudParse {

    private val moshi = Moshi.Builder().build()
    private val anyAdapter = moshi.adapter(Any::class.java)

    /**
     * Files of an explore response, in either shape. Throws
     * [ProviderException] on `{"error": ...}` ("Bad archive" means the
     * request is a single file, which [OffcloudProvider] handles first).
     */
    fun exploreFiles(json: String): List<OcFile> {
        val root = runCatching { anyAdapter.fromJson(json) }.getOrNull()
            ?: throw ProviderException("Offcloud: unreadable folder listing")
        val entries: List<*> = when (root) {
            is List<*> -> root
            is Map<*, *> -> {
                (root["error"] as? String)?.let { throw ProviderException("Offcloud: $it") }
                root["files"] as? List<*> ?: emptyList<Any>()
            }
            else -> emptyList<Any>()
        }
        return entries.mapNotNull { e ->
            when (e) {
                is String -> e.takeIf { it.startsWith("http") }?.let { OcFile(pathFromUrl(it), null, it) }
                is Map<*, *> -> {
                    val url = e["url"] as? String ?: return@mapNotNull null
                    val path = (e["path"] as? String)?.trimStart('/')?.takeIf { it.isNotBlank() } ?: pathFromUrl(url)
                    OcFile(path, (e["size"] as? Number)?.toLong()?.takeIf { it > 0 }, url)
                }
                else -> null
            }
        }
    }

    /** `.../cloud/download/<id>/Folder/Sub/file.mkv` -> "Folder/Sub/file.mkv" (decoded). */
    fun pathFromUrl(url: String): String {
        val tail = url.substringAfter("/cloud/download/", "").substringAfter('/', "")
            .ifEmpty { url.substringAfterLast('/') }
            .substringBefore('?')
        return runCatching { java.net.URLDecoder.decode(tail.replace("+", "%2B"), "UTF-8") }.getOrDefault(tail)
    }

    /** Download link of a single-file request. */
    fun singleFileUrl(server: String, requestId: String, fileName: String): String {
        val name = URLEncoder.encode(fileName, "UTF-8").replace("+", "%20")
        return "https://$server.offcloud.com/cloud/download/$requestId/$name"
    }

    /** `{"error": ...}` / `{"success": false}` in a free-form response. */
    fun errorOf(json: String): String? {
        val root = runCatching { anyAdapter.fromJson(json) }.getOrNull() as? Map<*, *> ?: return null
        (root["error"] as? String)?.let { return it }
        return if (root["success"] == false) "request failed" else null
    }

    /** ISO-8601 or epoch millis/seconds, to an ISO instant string. */
    fun isoDate(raw: String?): String? = instant(raw)?.toString()

    /** ISO-8601 or epoch millis/seconds, to an [Instant]; null if unparseable. */
    fun instant(raw: String?): Instant? {
        if (raw.isNullOrBlank()) return null
        runCatching { return Instant.parse(raw) }
        runCatching { return OffsetDateTime.parse(raw).toInstant() }
        val n = raw.toLongOrNull() ?: return null
        return Instant.ofEpochMilli(if (n < 100_000_000_000L) n * 1000 else n)
    }

    /** How a cloud/remove response reads: a JSON object or array is "recognised". */
    data class RemoveOutcome(val recognised: Boolean, val error: String?)

    fun removeOutcome(json: String): RemoveOutcome {
        val root = runCatching { anyAdapter.fromJson(json) }.getOrNull()
        return RemoveOutcome(root is Map<*, *> || root is List<*>, errorOf(json))
    }

    fun notAvailableMessage(what: String): String = when (what) {
        "premium" -> "Offcloud: this needs a premium account"
        "cloud" -> "Offcloud: your plan's cloud downloads are used up"
        "links" -> "Offcloud: your plan's link limit is used up"
        "proxy" -> "Offcloud: this needs the proxy add-on"
        "video" -> "Offcloud: this needs the video sites add-on"
        else -> "Offcloud: not available on your plan ($what)"
    }
}

private fun ocCheck(error: String?) {
    if (!error.isNullOrBlank()) {
        throw ProviderException(if (error == "NOAUTH") "Offcloud: invalid API key" else "Offcloud: $error")
    }
}

@Singleton
class OffcloudProvider internal constructor(
    private val api: OffcloudApi,
    private val currentToken: suspend () -> String?
) : DebridProvider {

    @Inject constructor(api: OffcloudApi, tokenStore: TokenStore) :
        this(api, { tokenStore.token(ProviderId.OFFCLOUD) })

    override val info = ProviderInfo(
        id = ProviderId.OFFCLOUD,
        displayName = "Offcloud",
        tokenUrl = "https://offcloud.com/#/account",
        tokenUrlLabel = "offcloud.com/#/account",
        tokenLabel = "API key",
        emptyListHint = "Add a magnet or link on offcloud.com (Cloud) to populate this list.",
        websiteUrl = "https://offcloud.com/"
    )

    /** A finished request's files never change, so explore each one once. */
    private val folderCache = ConcurrentHashMap<String, List<OcFile>>()

    /** Sign-in point: whatever was cached belongs to the previous account. */
    override suspend fun validateToken(token: String): AccountInfo {
        clearCaches()
        try {
            return api.validate("Bearer $token").toAccount()
        } finally {
            clearCaches()
        }
    }

    /** Non-suspending; fetches already in flight won't store their results. */
    private fun clearCaches() {
        accountGen.incrementAndGet()
        folderCache.clear()
        invalidateHistory()
    }

    override suspend fun account(): AccountInfo = api.accountInfo().toAccount()

    private fun OcAccount.toAccount(): AccountInfo {
        ocCheck(error)
        val premium = premiumNew ?: premiumOld
        return AccountInfo(
            displayName = email ?: (userIdNew ?: userIdOld)?.let { "User $it" } ?: "Offcloud user",
            premiumUntil = OffcloudParse.isoDate(expiresNew ?: expiresOld),
            plan = when (premium) { true -> "Premium"; false -> "Free"; null -> null }
        )
    }

    /** History is the whole cloud in one call; keep it for a moment so a screen's burst of calls shares one fetch. */
    private val historyLock = Mutex()
    /** One immutable snapshot: the list, when it was stored, the token it was fetched with and the generation it began under. */
    private class HistoryEntry(val list: List<OcRequest>, val at: Long, val token: String, val gen: Long)

    private val historyEntry = AtomicReference<HistoryEntry?>(null)
    /** Bumped by every invalidation; a fetch that began before a bump must not stay cached. */
    private val historyGen = AtomicLong()
    private val accountGen = AtomicLong()

    private suspend fun history(fresh: Boolean = false): List<OcRequest> = historyLock.withLock {
        // Read before the network call: the entry is stamped with the account it was fetched for.
        val token = currentToken()
        if (!fresh && token != null) {
            historyEntry.get()?.takeIf {
                it.token == token && it.gen == historyGen.get() && System.nanoTime() - it.at < HISTORY_TTL_NS
            }?.let { return it.list }
        }
        val gen = historyGen.get()
        val list = api.history().filter { it.requestId != null }
        if (token != null) {
            val entry = HistoryEntry(list, System.nanoTime(), token, gen)
            historyEntry.set(entry)
            // An invalidation may have landed before or after the store; either way drop what it meant to drop.
            if (historyGen.get() != gen) historyEntry.compareAndSet(entry, null)
        }
        list
    }

    private fun invalidateHistory() {
        historyGen.incrementAndGet()
        historyEntry.set(null)
    }

    /** Caps concurrent cloud/explore calls. */
    private val exploreGate = Semaphore(EXPLORE_PARALLELISM)

    /** [itemsOf] with one retry; cancellation is never swallowed. */
    private suspend fun itemsWithRetry(r: OcRequest): Result<List<DownloadItem>> {
        var last: Exception? = null
        repeat(2) {
            try {
                return Result.success(itemsOf(r))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A rejected key won't get better on a second try.
                if (e is HttpException && (e.code() == 401 || e.code() == 403)) return Result.failure(e)
                last = e
            }
        }
        return Result.failure(last!!)
    }

    // --- library -----------------------------------------------------------

    override suspend fun listFiles(page: Int, pageSize: Int): Page<DownloadItem> {
        // History is the whole cloud in one call; page its requests locally,
        // newest first, and expand folder requests into their files.
        // Sorted on the parsed time (ISO strings differ in precision); no date goes last.
        val done = history(fresh = page <= 1).filter { it.status == "downloaded" }
            .sortedWith(compareByDescending<OcRequest> { OffcloudParse.instant(it.createdOn) })
        val from = ((page - 1).coerceAtLeast(0) * pageSize).coerceAtMost(done.size)
        val slice = done.subList(from, (from + pageSize).coerceAtMost(done.size))
        val results = coroutineScope {
            slice.map { r -> async { exploreGate.withPermit { itemsWithRetry(r) } } }.awaitAll()
        }
        // Some folders failing still shows the rest; all failing is an error, not an empty library.
        if (results.isNotEmpty() && results.all { it.isFailure }) throw results.first().exceptionOrNull()!!
        val items = results.flatMap { it.getOrDefault(emptyList()) }
        return Page(items, hasMore = from + pageSize < done.size)
    }

    /** Every file of one finished request. */
    private suspend fun itemsOf(r: OcRequest): List<DownloadItem> {
        val id = r.requestId!!
        val name = r.fileName ?: "file"
        if (r.isDirectory == false) return listOfNotNull(singleItem(r))
        val files = folderFiles(id) ?: return listOfNotNull(singleItem(r))
        return files.map { f ->
            DownloadItem(
                id = "oc:$id:${f.path}",
                provider = ProviderId.OFFCLOUD,
                filename = f.path.substringAfterLast('/'),
                sourceRef = "$id|${f.path}",
                downloadUrl = f.url,
                host = "Cloud · $name",
                filesize = f.size ?: 0L,
                maxConnections = DEFAULT_CONNECTIONS,
                addedAt = OffcloudParse.isoDate(r.createdOn),
                parentRef = "req:$id",
                parentName = name,
                path = f.path
            )
        }
    }

    private fun singleItem(r: OcRequest): DownloadItem? {
        val id = r.requestId ?: return null
        val name = r.fileName ?: return null
        return DownloadItem(
            id = "oc:$id",
            provider = ProviderId.OFFCLOUD,
            filename = name,
            sourceRef = id,
            downloadUrl = r.server?.let { OffcloudParse.singleFileUrl(it, id, name) },
            host = "Cloud",
            filesize = r.fileSize ?: 0L,
            maxConnections = DEFAULT_CONNECTIONS,
            addedAt = OffcloudParse.isoDate(r.createdOn),
            parentRef = "req:$id",
            parentName = name
        )
    }

    /** Files of a folder request, or null if it turns out to be a single file. */
    private suspend fun folderFiles(requestId: String): List<OcFile>? {
        folderCache[requestId]?.let { return it }
        val gen = accountGen.get()
        val files = try {
            OffcloudParse.exploreFiles(api.explore(requestId).string())
        } catch (e: ProviderException) {
            if (e.message?.contains("Bad archive", ignoreCase = true) == true) return null
            throw e
        }
        if (files.isNotEmpty() && gen == accountGen.get()) folderCache[requestId] = files
        return files
    }

    override suspend fun resolveLink(sourceRef: String): ResolvedLink {
        val requestId = sourceRef.substringBefore('|')
        val path = sourceRef.substringAfter('|', "")
        if (path.isNotEmpty()) {
            folderCache.remove(requestId)
            val f = folderFiles(requestId)?.firstOrNull { it.path == path }
                ?: throw ProviderException("Offcloud: this file is no longer in your cloud")
            return ResolvedLink(url = f.url, maxConnections = null, filesize = f.size)
        }
        val r = history().firstOrNull { it.requestId == requestId }
            ?: throw ProviderException("Offcloud: this file is no longer in your cloud")
        val server = r.server ?: throw ProviderException("Offcloud: no download link for this file yet")
        val name = r.fileName ?: throw ProviderException("Offcloud: no download link for this file yet")
        return ResolvedLink(OffcloudParse.singleFileUrl(server, requestId, name), null, r.fileSize?.takeIf { it > 0 })
    }

    // --- adding ------------------------------------------------------------

    override val addCapabilities = setOf(AddKind.MAGNET, AddKind.TORRENT_FILE, AddKind.LINK)

    override suspend fun add(request: AddRequest): AddResult {
        val url = when (request) {
            is AddRequest.NzbFile, is AddRequest.NzbLink -> throw ProviderException("Offcloud doesn't support Usenet")
            is AddRequest.Magnet -> request.uri
            is AddRequest.Link -> request.url
            // Offcloud has no .torrent upload; send the torrent as a magnet.
            is AddRequest.TorrentFile -> {
                val hash = TorrentHash.fromTorrentFile(request.bytes)
                    ?: throw ProviderException("Couldn't read that .torrent file")
                // Keep the trackers: a bare hash leaves peer discovery to DHT alone.
                val trackers = TorrentHash.trackersFromTorrentFile(request.bytes).take(MAX_TRACKERS)
                "magnet:?xt=urn:btih:$hash&dn=" +
                    URLEncoder.encode(request.name.removeSuffix(".torrent"), "UTF-8") +
                    trackers.joinToString("") { "&tr=" + URLEncoder.encode(it, "UTF-8") }
            }
        }
        val r = try {
            api.addCloud(OcUrlBody(url))
        } finally {
            invalidateHistory()
        }
        r.notAvailable?.let { throw ProviderException(OffcloudParse.notAvailableMessage(it)) }
        ocCheck(r.error)
        val id = r.requestId ?: throw ProviderException("Offcloud didn't accept that")
        val name = r.fileName ?: "to Offcloud"
        return when (r.status) {
            "downloaded" -> AddResult(message = "Cached — ready now: $name", jobRef = "req:$id")
            "error" -> throw ProviderException("Offcloud couldn't fetch $name")
            else -> AddResult(message = "Added $name", jobRef = "req:$id")
        }
    }

    // --- jobs --------------------------------------------------------------

    override suspend fun listProcessing(): List<RemoteJob> =
        history().filter { it.status !in DONE }.map {
            RemoteJob(
                ref = "req:${it.requestId}",
                provider = ProviderId.OFFCLOUD,
                name = it.fileName ?: "Cloud download",
                progress = null,
                status = it.status ?: "processing",
                sizeBytes = it.fileSize ?: 0L,
                etaSeconds = null,
                bytesPerSecond = null
            )
        }

    override suspend fun filesForJob(jobRef: String): List<DownloadItem>? {
        val id = jobRef.removePrefix("req:")
        val r = history().firstOrNull { it.requestId == id }
            ?: throw ProviderException("Offcloud: that download is gone")
        return when (r.status) {
            in FAILED -> throw JobFailedException("Offcloud couldn't fetch ${r.fileName ?: "it"} (${r.status})")
            // Null would make ReadyWatcher poll forever; a plain error lets its give-up apply.
            in DONE -> itemsOf(r).ifEmpty {
                throw ProviderException("Offcloud: no files listed for ${r.fileName ?: "it"} yet")
            }
            else -> null
        }
    }

    // --- removing ----------------------------------------------------------

    /** Every file of a request shares its key ("oc:<id>" or "oc:<id>:<path>" -> "req:<id>"). */
    override fun removalKey(itemId: String): String? {
        if (!itemId.startsWith("oc:")) return null
        val id = itemId.removePrefix("oc:").substringBefore(':')
        return id.takeIf { it.isNotBlank() }?.let { "req:$it" }
    }

    override suspend fun remove(key: String) {
        if (!key.startsWith("req:")) throw ProviderException("Corrupt Offcloud reference: $key")
        val id = key.removePrefix("req:")
        try {
            // Current servers take a JSON list; older ones a GET per request.
            val post = try {
                OffcloudParse.removeOutcome(api.removeMany(OcRemoveBody(listOf(id))).string())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (post != null && post.recognised && post.error == null) return
            val get = OffcloudParse.removeOutcome(api.removeOne(id).string())
            get.error?.let { ocCheck(it) }
            if (get.recognised) return
            // Neither answer was understood: believe only a fresh history.
            if (history(fresh = true).any { it.requestId == id }) {
                throw ProviderException("Offcloud: couldn't remove it")
            }
        } finally {
            folderCache.remove(id)
            invalidateHistory()
        }
    }

    override suspend fun itemsIn(key: String): List<DownloadItem>? {
        val id = key.removePrefix("req:")
        val r = history().firstOrNull { it.requestId == id } ?: return null
        return if (r.status in DONE) itemsOf(r) else null
    }

    override suspend fun itemIdsIn(key: String): List<String>? = itemsIn(key)?.map { it.id }

    // --- cache check ---------------------------------------------------------

    override val supportsCacheCheck = true

    override suspend fun cachedHashes(infoHashes: List<String>): Set<String> {
        val hashes = infoHashes.distinct()
        if (hashes.isEmpty()) return emptySet()
        return hashes.chunked(100).flatMap { batch ->
            val r = api.cache(OcCacheBody(batch))
            ocCheck(r.error)
            r.cachedItems.orEmpty()
        }.map { it.lowercase() }.toSet()
    }

    private companion object {
        const val DEFAULT_CONNECTIONS = 8
        const val EXPLORE_PARALLELISM = 6
        const val MAX_TRACKERS = 20
        const val HISTORY_TTL_NS = 5_000_000_000L
        val DONE = setOf("downloaded")
        val FAILED = setOf("error", "canceled", "cancelled")
    }
}
