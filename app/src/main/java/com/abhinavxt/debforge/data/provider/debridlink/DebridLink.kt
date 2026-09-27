package com.abhinavxt.debforge.data.provider.debridlink

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
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.http.DELETE
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Debrid-Link API v2 (https://debrid-link.com/api_doc). Auth: "Authorization:
 * Bearer <api key>". Envelope: { "success": bool, "value": ..., "error": code,
 * "pagination": {page, pages, next, previous} }.
 *
 * Two sources of files: the seedbox (torrents) and the downloader (hoster
 * links). Both expose direct `downloadUrl`s.
 */
interface DebridLinkApi {

    @GET("account/infos")
    suspend fun account(): DlEnvelope<DlAccount>

    @GET("account/infos")
    suspend fun validate(@Header("Authorization") authorization: String): DlEnvelope<DlAccount>

    @GET("seedbox/list")
    suspend fun seedbox(@Query("page") page: Int, @Query("perPage") perPage: Int): DlEnvelope<List<DlTorrent>>

    @GET("seedbox/list")
    suspend fun seedboxById(@Query("ids") ids: String): DlEnvelope<List<DlTorrent>>

    @GET("downloader/list")
    suspend fun downloader(@Query("page") page: Int, @Query("perPage") perPage: Int): DlEnvelope<List<DlLink>>

    @FormUrlEncoded
    @POST("seedbox/add")
    suspend fun addTorrent(@Field("url") url: String, @Field("async") async: Boolean = true): DlEnvelope<DlTorrent>

    @Multipart
    @POST("seedbox/add")
    suspend fun addTorrentFile(@Part file: MultipartBody.Part): DlEnvelope<DlTorrent>

    @FormUrlEncoded
    @POST("downloader/add")
    suspend fun addLink(@Field("url") url: String): DlEnvelope<DlLink>

    /** Comma-separated ids; `value` lists the removed ones. */
    @DELETE("seedbox/{ids}/remove")
    suspend fun removeTorrents(@Path("ids") ids: String): DlEnvelope<List<String>>

    @DELETE("downloader/{ids}/remove")
    suspend fun removeLinks(@Path("ids") ids: String): DlEnvelope<List<String>>

    companion object {
        const val BASE_URL = "https://debrid-link.com/api/v2/"
    }
}

@JsonClass(generateAdapter = true)
data class DlEnvelope<T>(
    @Json(name = "success") val success: Boolean?,
    @Json(name = "value") val value: T?,
    @Json(name = "error") val error: String?,
    @Json(name = "error_description") val errorDescription: String?,
    @Json(name = "pagination") val pagination: DlPagination?
)

@JsonClass(generateAdapter = true)
data class DlPagination(
    @Json(name = "page") val page: Int?,
    @Json(name = "pages") val pages: Int?,
    @Json(name = "next") val next: Int?
)

@JsonClass(generateAdapter = true)
data class DlAccount(
    @Json(name = "username") val username: String?,
    @Json(name = "email") val email: String?,
    /** 0 = free, 1 = premium, 2 = premium (other). */
    @Json(name = "accountType") val accountType: Int?,
    /** Seconds of premium left. */
    @Json(name = "premiumLeft") val premiumLeft: Long?
)

@JsonClass(generateAdapter = true)
data class DlTorrent(
    @Json(name = "id") val id: String,
    @Json(name = "name") val name: String?,
    @Json(name = "status") val status: Int?,
    @Json(name = "totalSize") val totalSize: Long?,
    @Json(name = "downloadPercent") val downloadPercent: Double?,
    @Json(name = "downloadSpeed") val downloadSpeed: Long?,
    @Json(name = "files") val files: List<DlTorrentFile>?,
    /** Unix seconds. */
    @Json(name = "created") val created: Long?
)

@JsonClass(generateAdapter = true)
data class DlTorrentFile(
    @Json(name = "id") val id: String,
    @Json(name = "name") val name: String?,
    @Json(name = "downloadUrl") val downloadUrl: String?,
    @Json(name = "size") val size: Long?,
    @Json(name = "downloadPercent") val downloadPercent: Double?
)

@JsonClass(generateAdapter = true)
data class DlLink(
    @Json(name = "id") val id: String,
    @Json(name = "name") val name: String?,
    @Json(name = "url") val url: String?,
    @Json(name = "downloadUrl") val downloadUrl: String?,
    @Json(name = "host") val host: String?,
    @Json(name = "size") val size: Long?,
    @Json(name = "chunk") val chunk: Int?,
    @Json(name = "created") val created: Long?,
    @Json(name = "expired") val expired: Boolean?
)

private fun <T> DlEnvelope<T>.unwrap(): T {
    if (success != true) throw ProviderException("Debrid-Link: ${errorDescription ?: error ?: "request failed"}")
    return value ?: throw ProviderException("Debrid-Link: empty response")
}

@Singleton
class DebridLinkProvider @Inject constructor(
    private val api: DebridLinkApi
) : DebridProvider {

    override val info = ProviderInfo(
        id = ProviderId.DEBRID_LINK,
        displayName = "Debrid-Link",
        tokenUrl = "https://debrid-link.com/webapp/apikey",
        tokenUrlLabel = "debrid-link.com/webapp/apikey",
        tokenLabel = "API key",
        emptyListHint = "Add a torrent or link on debrid-link.com to populate this list.",
        websiteUrl = "https://debrid-link.com/"
    )

    override suspend fun validateToken(token: String): AccountInfo =
        api.validate("Bearer $token").unwrap().toAccount()

    override suspend fun account(): AccountInfo = api.account().unwrap().toAccount()

    private fun DlAccount.toAccount(): AccountInfo {
        val left = premiumLeft?.takeIf { it > 0 }
        return AccountInfo(
            displayName = username ?: email ?: "Debrid-Link user",
            premiumUntil = left?.let { Instant.now().plusSeconds(it).toString() },
            plan = if ((accountType ?: 0) > 0) "Premium" else "Free"
        )
    }

    // --- library -----------------------------------------------------------

    override suspend fun listFiles(page: Int, pageSize: Int): Page<DownloadItem> = coroutineScope {
        val perPage = pageSize.coerceIn(1, 50)
        val torrents = async { api.seedbox(page - 1, perPage) }
        val links = async { api.downloader(page - 1, perPage) }
        val t = torrents.await()
        val l = links.await()
        val items = t.unwrap().filter { isReady(it) }.flatMap { it.toItems() } +
            l.unwrap().filter { it.expired != true && !it.downloadUrl.isNullOrBlank() }.map { it.toItem() }
        Page(items, hasMore = hasNext(t.pagination) || hasNext(l.pagination))
    }

    // Debrid-Link pages are 0-based; `next` is -1 on the last page.
    private fun hasNext(p: DlPagination?): Boolean = (p?.next ?: -1) >= 0

    private fun isReady(t: DlTorrent) = (t.downloadPercent ?: 0.0) >= 100.0

    private fun DlTorrent.toItems(): List<DownloadItem> = files.orEmpty()
        .filter { !it.downloadUrl.isNullOrBlank() }
        .map { f ->
            val fname = f.name?.substringAfterLast('/') ?: "file"
            DownloadItem(
                id = "dl:sb:$id:${f.id}",
                provider = ProviderId.DEBRID_LINK,
                filename = fname,
                sourceRef = "sb:$id:${f.id}",
                downloadUrl = f.downloadUrl,
                host = name?.takeIf { it != fname }?.let { "Torrent · $it" } ?: "Torrent",
                filesize = f.size ?: 0L,
                maxConnections = DEFAULT_CONNECTIONS,
                addedAt = created?.let { Instant.ofEpochSecond(it).toString() },
                // Same ref as the processing strip and removalKey().
                parentRef = "sb:$id",
                parentName = name,
                path = f.name?.takeIf { '/' in it }
            )
        }

    private fun DlLink.toItem() = DownloadItem(
        id = "dl:dl:$id",
        provider = ProviderId.DEBRID_LINK,
        filename = name ?: "download",
        sourceRef = "dl:" + (url ?: downloadUrl.orEmpty()),
        downloadUrl = downloadUrl,
        host = host ?: "link",
        filesize = size ?: 0L,
        maxConnections = (chunk ?: DEFAULT_CONNECTIONS).coerceAtLeast(1),
        addedAt = created?.let { Instant.ofEpochSecond(it).toString() }
    )

    override suspend fun resolveLink(sourceRef: String): ResolvedLink = when {
        sourceRef.startsWith("sb:") -> {
            val (_, torrentId, fileId) = sourceRef.split(':', limit = 3).let { Triple(it[0], it.getOrElse(1) { "" }, it.getOrElse(2) { "" }) }
            val file = api.seedboxById(torrentId).unwrap().firstOrNull()?.files.orEmpty()
                .firstOrNull { it.id == fileId }
                ?: throw ProviderException("Debrid-Link: file is no longer in the seedbox")
            ResolvedLink(
                url = file.downloadUrl ?: throw ProviderException("Debrid-Link: no link for this file"),
                maxConnections = null,
                filesize = file.size
            )
        }
        sourceRef.startsWith("dl:") -> {
            // Re-adding a hoster link mints a fresh direct URL.
            val l = api.addLink(sourceRef.removePrefix("dl:")).unwrap()
            ResolvedLink(
                url = l.downloadUrl ?: throw ProviderException("Debrid-Link: no link returned"),
                maxConnections = l.chunk,
                filesize = l.size
            )
        }
        else -> throw ProviderException("Corrupt Debrid-Link reference: $sourceRef")
    }

    // --- adding ------------------------------------------------------------

    override val addCapabilities = setOf(AddKind.MAGNET, AddKind.TORRENT_FILE, AddKind.LINK)

    override suspend fun add(request: AddRequest): AddResult = when (request) {
        is AddRequest.Magnet -> api.addTorrent(request.uri).unwrap().let {
            AddResult("Added ${it.name ?: "to Debrid-Link"}", jobRef = "sb:${it.id}")
        }
        is AddRequest.TorrentFile -> api.addTorrentFile(
            MultipartBody.Part.createFormData(
                "file", request.name, request.bytes.toRequestBody("application/x-bittorrent".toMediaType())
            )
        ).unwrap().let { AddResult("Added ${it.name ?: request.name}", jobRef = "sb:${it.id}") }
        is AddRequest.Link -> api.addLink(request.url).unwrap().let { link ->
            AddResult(
                message = "Added ${link.name ?: "link"}",
                readyFiles = if (link.downloadUrl.isNullOrBlank()) emptyList() else listOf(link.toItem())
            )
        }
    }

    // --- jobs --------------------------------------------------------------

    override suspend fun listProcessing(): List<RemoteJob> =
        api.seedbox(0, 50).unwrap().filter { !isReady(it) }.map { t ->
            RemoteJob(
                ref = "sb:${t.id}",
                provider = ProviderId.DEBRID_LINK,
                name = t.name ?: "Torrent",
                progress = t.downloadPercent?.let { (it / 100.0).toFloat().coerceIn(0f, 1f) },
                status = "downloading",
                sizeBytes = t.totalSize ?: 0L,
                etaSeconds = null,
                bytesPerSecond = t.downloadSpeed?.takeIf { it > 0 }
            )
        }

    override suspend fun filesForJob(jobRef: String): List<DownloadItem>? {
        val id = jobRef.removePrefix("sb:")
        val t = api.seedboxById(id).unwrap().firstOrNull()
            ?: throw ProviderException("Debrid-Link: torrent is gone")
        return if (isReady(t)) t.toItems() else null
    }

    // --- removing ----------------------------------------------------------

    /**
     * Seedbox files "dl:sb:<torrent>:<file>" share the torrent's key
     * "sb:<torrent>" (also the processing ref); downloader links
     * "dl:dl:<id>" are removed one by one as "dl:<id>".
     */
    override fun removalKey(itemId: String): String? {
        val parts = itemId.split(':')
        return when {
            parts.size == 4 && parts[0] == "dl" && parts[1] == "sb" -> "sb:${parts[2]}"
            parts.size == 3 && parts[0] == "dl" && parts[1] == "dl" -> "dl:${parts[2]}"
            else -> null
        }
    }

    override suspend fun remove(key: String) {
        when {
            key.startsWith("sb:") -> api.removeTorrents(key.removePrefix("sb:")).unwrap()
            key.startsWith("dl:") -> api.removeLinks(key.removePrefix("dl:")).unwrap()
            else -> throw ProviderException("Corrupt Debrid-Link reference: $key")
        }
    }

    override suspend fun itemIdsIn(key: String): List<String>? = when {
        key.startsWith("sb:") -> filesForJob(key)?.map { it.id }
        key.startsWith("dl:") -> listOf("dl:dl:" + key.removePrefix("dl:"))
        else -> null
    }

    override suspend fun itemsIn(key: String): List<DownloadItem>? =
        if (key.startsWith("sb:")) filesForJob(key) else null

    private companion object {
        const val DEFAULT_CONNECTIONS = 8
    }
}
