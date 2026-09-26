package com.abhinavxt.debforge.data.provider.premiumize

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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Query
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Premiumize.me API (https://www.premiumize.me/api). Auth: `apikey` query
 * parameter (added by QueryAuthInterceptor). Every response carries
 * "status": "success" | "error" plus "message" on errors.
 */
interface PremiumizeApi {

    @GET("account/info")
    suspend fun accountInfo(): PmAccount

    @GET("account/info")
    suspend fun validate(@Query("apikey") apiKey: String): PmAccount

    /** Every file in the cloud, flat. */
    @GET("item/listall")
    suspend fun listAll(): PmListAll

    @GET("item/details")
    suspend fun itemDetails(@Query("id") id: String): PmItem

    @GET("folder/list")
    suspend fun folderList(@Query("id") id: String): PmFolder

    @GET("transfer/list")
    suspend fun transfers(): PmTransfers

    @FormUrlEncoded
    @POST("transfer/create")
    suspend fun createTransfer(@Field("src") src: String): PmCreated

    @Multipart
    @POST("transfer/create")
    suspend fun createTransferFile(@Part file: MultipartBody.Part): PmCreated

    /** Cached content of a magnet/link as direct links (no transfer needed). */
    @FormUrlEncoded
    @POST("transfer/directdl")
    suspend fun directDl(@Field("src") src: String): PmDirectDl

    companion object {
        const val BASE_URL = "https://www.premiumize.me/api/"
    }
}

@JsonClass(generateAdapter = true)
data class PmAccount(
    @Json(name = "status") val status: String?,
    @Json(name = "message") val message: String?,
    @Json(name = "customer_id") val customerId: String?,
    /** Unix seconds; can be null/absent on free accounts. */
    @Json(name = "premium_until") val premiumUntil: Double?,
    /** Fair-use points used, 0..1. */
    @Json(name = "limit_used") val limitUsed: Double?
)

@JsonClass(generateAdapter = true)
data class PmListAll(
    @Json(name = "status") val status: String?,
    @Json(name = "message") val message: String?,
    @Json(name = "files") val files: List<PmItem>?
)

@JsonClass(generateAdapter = true)
data class PmItem(
    @Json(name = "status") val status: String? = null,
    @Json(name = "message") val message: String? = null,
    @Json(name = "id") val id: String?,
    @Json(name = "name") val name: String?,
    @Json(name = "type") val type: String? = null,
    @Json(name = "size") val size: Long?,
    @Json(name = "created_at") val createdAt: Long?,
    @Json(name = "path") val path: String? = null,
    @Json(name = "link") val link: String? = null,
    @Json(name = "stream_link") val streamLink: String? = null
)

@JsonClass(generateAdapter = true)
data class PmFolder(
    @Json(name = "status") val status: String?,
    @Json(name = "message") val message: String?,
    @Json(name = "name") val name: String?,
    @Json(name = "content") val content: List<PmItem>?
)

@JsonClass(generateAdapter = true)
data class PmTransfers(
    @Json(name = "status") val status: String?,
    @Json(name = "message") val message: String?,
    @Json(name = "transfers") val transfers: List<PmTransfer>?
)

@JsonClass(generateAdapter = true)
data class PmTransfer(
    @Json(name = "id") val id: String,
    @Json(name = "name") val name: String?,
    @Json(name = "message") val message: String?,
    /** waiting, queued, running, seeding, finished, deleted, banned, error, timeout */
    @Json(name = "status") val status: String?,
    @Json(name = "progress") val progress: Double?,
    @Json(name = "folder_id") val folderId: String?,
    @Json(name = "file_id") val fileId: String?
)

@JsonClass(generateAdapter = true)
data class PmCreated(
    @Json(name = "status") val status: String?,
    @Json(name = "message") val message: String?,
    @Json(name = "id") val id: String?,
    @Json(name = "name") val name: String?,
    @Json(name = "type") val type: String?
)

@JsonClass(generateAdapter = true)
data class PmDirectDl(
    @Json(name = "status") val status: String?,
    @Json(name = "message") val message: String?,
    @Json(name = "content") val content: List<PmDirectFile>?
)

@JsonClass(generateAdapter = true)
data class PmDirectFile(
    @Json(name = "path") val path: String?,
    @Json(name = "size") val size: Long?,
    @Json(name = "link") val link: String?
)

private fun pmCheck(status: String?, message: String?) {
    if (status != null && status != "success") throw ProviderException("Premiumize: ${message ?: "request failed"}")
}

@Singleton
class PremiumizeProvider @Inject constructor(
    private val api: PremiumizeApi
) : DebridProvider {

    override val info = ProviderInfo(
        id = ProviderId.PREMIUMIZE,
        displayName = "Premiumize",
        tokenUrl = "https://www.premiumize.me/account",
        tokenUrlLabel = "premiumize.me/account",
        tokenLabel = "API key",
        emptyListHint = "Add a magnet, torrent or link on premiumize.me to populate this list.",
        websiteUrl = "https://www.premiumize.me/"
    )

    override suspend fun validateToken(token: String): AccountInfo = api.validate(token).toAccount()

    override suspend fun account(): AccountInfo = api.accountInfo().toAccount()

    private fun PmAccount.toAccount(): AccountInfo {
        pmCheck(status, message)
        val until = premiumUntil?.toLong()?.takeIf { it > 0 }
        return AccountInfo(
            displayName = customerId?.let { "Customer $it" } ?: "Premiumize user",
            premiumUntil = until?.let { Instant.ofEpochSecond(it).toString() },
            plan = if (until != null) "Premium" else "Free"
        )
    }

    // --- library -----------------------------------------------------------

    override suspend fun listFiles(page: Int, pageSize: Int): Page<DownloadItem> {
        // listall returns the whole cloud; page it locally, newest first.
        val all = api.listAll().also { pmCheck(it.status, it.message) }.files.orEmpty()
            .filter { it.id != null && it.type != "folder" }
            .sortedByDescending { it.createdAt ?: 0 }
        val from = ((page - 1).coerceAtLeast(0) * pageSize).coerceAtMost(all.size)
        val slice = all.subList(from, (from + pageSize).coerceAtMost(all.size))
        return Page(slice.map { it.toItem() }, hasMore = from + pageSize < all.size)
    }

    private fun PmItem.toItem(): DownloadItem {
        val name = name ?: "file"
        val folder = path?.substringBeforeLast('/', "")?.substringAfterLast('/')?.takeIf { it.isNotBlank() && it != name }
        return DownloadItem(
            id = "pm:$id",
            provider = ProviderId.PREMIUMIZE,
            filename = name,
            sourceRef = id!!,
            downloadUrl = link?.takeIf { it.isNotBlank() },
            host = folder?.let { "Cloud · $it" } ?: "Cloud",
            filesize = size ?: 0L,
            maxConnections = DEFAULT_CONNECTIONS,
            addedAt = createdAt?.let { Instant.ofEpochSecond(it).toString() }
        )
    }

    override suspend fun resolveLink(sourceRef: String): ResolvedLink {
        val d = api.itemDetails(sourceRef)
        pmCheck(d.status, d.message)
        val link = d.link?.takeIf { it.isNotBlank() } ?: throw ProviderException("Premiumize: no download link for this file")
        return ResolvedLink(url = link, maxConnections = null, filesize = d.size?.takeIf { it > 0 })
    }

    // --- adding ------------------------------------------------------------

    override val addCapabilities = setOf(AddKind.MAGNET, AddKind.TORRENT_FILE, AddKind.LINK)

    override suspend fun add(request: AddRequest): AddResult {
        val created = when (request) {
            is AddRequest.Magnet -> api.createTransfer(request.uri)
            is AddRequest.Link -> api.createTransfer(request.url)
            is AddRequest.TorrentFile -> api.createTransferFile(
                MultipartBody.Part.createFormData(
                    "file", request.name, request.bytes.toRequestBody("application/x-bittorrent".toMediaType())
                )
            )
        }
        pmCheck(created.status, created.message)
        return AddResult(
            message = "Added ${created.name ?: "to Premiumize"}",
            jobRef = created.id?.let { "transfer:$it" }
        )
    }

    // --- jobs --------------------------------------------------------------

    override suspend fun listProcessing(): List<RemoteJob> {
        val t = api.transfers().also { pmCheck(it.status, it.message) }.transfers.orEmpty()
        return t.filter { it.status !in DONE && it.status !in FAILED }.map {
            RemoteJob(
                ref = "transfer:${it.id}",
                provider = ProviderId.PREMIUMIZE,
                name = it.name ?: "Transfer",
                progress = it.progress?.toFloat()?.coerceIn(0f, 1f),
                status = it.message?.takeIf { m -> m.isNotBlank() } ?: it.status ?: "processing",
                sizeBytes = 0L,
                etaSeconds = null,
                bytesPerSecond = null
            )
        }
    }

    override suspend fun filesForJob(jobRef: String): List<DownloadItem>? {
        val id = jobRef.removePrefix("transfer:")
        val t = api.transfers().also { pmCheck(it.status, it.message) }.transfers.orEmpty()
            .firstOrNull { it.id == id } ?: throw ProviderException("Premiumize: transfer is gone")
        return when (t.status) {
            in FAILED -> throw ProviderException("Premiumize: ${t.message ?: t.status}")
            in DONE -> when {
                t.fileId != null -> listOf(api.itemDetails(t.fileId).also { pmCheck(it.status, it.message) }.toItem())
                t.folderId != null -> collectFolder(t.folderId, depth = 0)
                else -> emptyList()
            }
            else -> null
        }
    }

    /** All files under a transfer's folder (a few levels deep is plenty). */
    private suspend fun collectFolder(folderId: String, depth: Int): List<DownloadItem> {
        if (depth > 4) return emptyList()
        val folder = api.folderList(folderId).also { pmCheck(it.status, it.message) }
        return folder.content.orEmpty().flatMap { item ->
            when {
                item.type == "folder" && item.id != null -> collectFolder(item.id, depth + 1)
                item.id != null -> listOf(item.toItem())
                else -> emptyList()
            }
        }
    }

    private companion object {
        const val DEFAULT_CONNECTIONS = 8
        val DONE = setOf("finished", "seeding")
        val FAILED = setOf("deleted", "banned", "error", "timeout")
    }
}
