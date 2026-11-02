package com.abhinavxt.debforge.data.provider.offcloud

import com.abhinavxt.debforge.data.provider.AccountInfo
import com.abhinavxt.debforge.data.provider.AddResult
import com.abhinavxt.debforge.data.provider.DebridProvider
import com.abhinavxt.debforge.data.provider.ProviderException
import com.abhinavxt.debforge.data.provider.ProviderInfo
import com.abhinavxt.debforge.data.provider.RemoteJob
import com.abhinavxt.debforge.data.provider.ResolvedLink
import com.abhinavxt.debforge.domain.AddKind
import com.abhinavxt.debforge.domain.AddRequest
import com.abhinavxt.debforge.domain.DownloadItem
import com.abhinavxt.debforge.domain.Page
import com.abhinavxt.debforge.domain.ProviderId
import com.abhinavxt.debforge.domain.TorrentHash
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.ResponseBody
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
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Offcloud API (https://github.com/offcloud/offcloud-api, plus the newer
 * endpoints current clients use: account/info, cloud/history, cloud/remove).
 * Auth: `Authorization: Bearer <API key>` (added by AuthInterceptor). Bodies
 * are JSON. Errors usually come back as HTTP 200 with `{"error": "..."}`, and
 * a missing add-on as `{"not_available": "premium" | "cloud" | ...}`.
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
    fun isoDate(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        runCatching { return Instant.parse(raw).toString() }
        runCatching { return OffsetDateTime.parse(raw).toInstant().toString() }
        val n = raw.toLongOrNull() ?: return null
        return Instant.ofEpochMilli(if (n < 100_000_000_000L) n * 1000 else n).toString()
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
class OffcloudProvider @Inject constructor(
    private val api: OffcloudApi
) : DebridProvider {

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

    override suspend fun validateToken(token: String): AccountInfo = api.validate("Bearer $token").toAccount()

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

    private suspend fun history(): List<OcRequest> = api.history().filter { it.requestId != null }

    // --- library -----------------------------------------------------------

    override suspend fun listFiles(page: Int, pageSize: Int): Page<DownloadItem> {
        // History is the whole cloud in one call; page its requests locally,
        // newest first, and expand folder requests into their files.
        val done = history().filter { it.status == "downloaded" }
            .sortedByDescending { OffcloudParse.isoDate(it.createdOn) ?: "" }
        val from = ((page - 1).coerceAtLeast(0) * pageSize).coerceAtMost(done.size)
        val slice = done.subList(from, (from + pageSize).coerceAtMost(done.size))
        val items = coroutineScope {
            slice.map { r -> async { runCatching { itemsOf(r) }.getOrDefault(emptyList()) } }.awaitAll()
        }.flatten()
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
        val files = try {
            OffcloudParse.exploreFiles(api.explore(requestId).string())
        } catch (e: ProviderException) {
            if (e.message?.contains("Bad archive", ignoreCase = true) == true) return null
            throw e
        }
        if (files.isNotEmpty()) folderCache[requestId] = files
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
            is AddRequest.Magnet -> request.uri
            is AddRequest.Link -> request.url
            // Offcloud has no .torrent upload; send the torrent as a magnet.
            is AddRequest.TorrentFile -> {
                val hash = TorrentHash.fromTorrentFile(request.bytes)
                    ?: throw ProviderException("Couldn't read that .torrent file")
                "magnet:?xt=urn:btih:$hash&dn=" +
                    URLEncoder.encode(request.name.removeSuffix(".torrent"), "UTF-8")
            }
        }
        val r = api.addCloud(OcUrlBody(url))
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
        history().filter { it.status !in DONE && it.status !in FAILED }.map {
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
            in FAILED -> throw ProviderException("Offcloud couldn't fetch ${r.fileName ?: "it"} (${r.status})")
            in DONE -> itemsOf(r)
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
        // Current servers take a JSON list; older ones a GET per request.
        val err = runCatching { OffcloudParse.errorOf(api.removeMany(OcRemoveBody(listOf(id))).string()) }
            .getOrElse { "failed" }
        if (err != null) {
            OffcloudParse.errorOf(api.removeOne(id).string())?.let { ocCheck(it) }
        }
        folderCache.remove(id)
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
        val DONE = setOf("downloaded")
        val FAILED = setOf("error", "canceled", "cancelled")
    }
}
