package com.abhinavxt.debforge.data.provider.alldebrid

import com.abhinavxt.debforge.data.provider.AccountInfo
import com.abhinavxt.debforge.data.provider.AddResult
import com.abhinavxt.debforge.data.provider.DebridProvider
import com.abhinavxt.debforge.data.provider.DeviceCode
import com.abhinavxt.debforge.data.provider.DeviceGrant
import com.abhinavxt.debforge.data.provider.DeviceLogin
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
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Query
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AllDebrid API (https://docs.alldebrid.com). Auth: "Authorization: Bearer
 * <apikey>" (the old agent/apikey query params are no longer needed).
 * Envelope: { "status": "success"|"error", "data": {...}, "error": {code, message} }.
 * Rate limit: 12 req/s, 600 req/min.
 */
interface AllDebridApi {

    @POST("v4/user")
    suspend fun user(): AdEnvelope<AdUserData>

    @POST("v4/user")
    suspend fun validateUser(@Header("Authorization") authorization: String): AdEnvelope<AdUserData>

    /** All magnets, or one when [id] is set (v4.1: files moved to magnet/files). */
    @FormUrlEncoded
    @POST("v4.1/magnet/status")
    suspend fun magnetStatus(@Field("id") id: Long? = null): AdEnvelope<AdMagnetsData>

    @FormUrlEncoded
    @POST("v4/magnet/files")
    suspend fun magnetFiles(@Field("id[]") ids: List<Long>): AdEnvelope<AdMagnetFilesData>

    @FormUrlEncoded
    @POST("v4/link/unlock")
    suspend fun unlock(@Field("link") link: String): AdEnvelope<AdUnlockData>

    @FormUrlEncoded
    @POST("v4/magnet/upload")
    suspend fun uploadMagnets(@Field("magnets[]") magnets: List<String>): AdEnvelope<AdUploadData>

    @Multipart
    @POST("v4/magnet/upload/file")
    suspend fun uploadFile(@Part file: MultipartBody.Part): AdEnvelope<AdUploadFileData>

    // --- PIN sign-in (no auth needed) ----------------------------------------

    @GET("v4.1/pin/get")
    suspend fun pinGet(): AdEnvelope<AdPinData>

    @FormUrlEncoded
    @POST("v4/pin/check")
    suspend fun pinCheck(@Field("pin") pin: String, @Field("check") check: String): AdEnvelope<AdPinCheckData>

    companion object {
        const val BASE_URL = "https://api.alldebrid.com/"
    }
}

@JsonClass(generateAdapter = true)
data class AdEnvelope<T>(
    @Json(name = "status") val status: String?,
    @Json(name = "data") val data: T?,
    @Json(name = "error") val error: AdError?
)

@JsonClass(generateAdapter = true)
data class AdError(@Json(name = "code") val code: String?, @Json(name = "message") val message: String?)

@JsonClass(generateAdapter = true)
data class AdUserData(@Json(name = "user") val user: AdUser?)

@JsonClass(generateAdapter = true)
data class AdUser(
    @Json(name = "username") val username: String?,
    @Json(name = "email") val email: String?,
    @Json(name = "isPremium") val isPremium: Boolean?,
    /** Unix seconds. */
    @Json(name = "premiumUntil") val premiumUntil: Long?
)

@JsonClass(generateAdapter = true)
data class AdMagnetsData(@Json(name = "magnets") val magnets: List<AdMagnet>?)

@JsonClass(generateAdapter = true)
data class AdMagnet(
    @Json(name = "id") val id: Long,
    @Json(name = "filename") val filename: String?,
    @Json(name = "size") val size: Long?,
    @Json(name = "status") val status: String?,
    /** 0 queued, 1 downloading, 2 compressing/moving, 3 uploading, 4 ready, 5+ error. */
    @Json(name = "statusCode") val statusCode: Int?,
    @Json(name = "downloaded") val downloaded: Long?,
    @Json(name = "downloadSpeed") val downloadSpeed: Long?,
    /** Unix seconds. */
    @Json(name = "uploadDate") val uploadDate: Long?
)

@JsonClass(generateAdapter = true)
data class AdMagnetFilesData(@Json(name = "magnets") val magnets: List<AdMagnetFiles>?)

@JsonClass(generateAdapter = true)
data class AdMagnetFiles(
    @Json(name = "id") val id: Long,
    @Json(name = "files") val files: List<AdFileNode>?
)

/** Tree node: a file has [link]; a folder has [children] ("e"). */
@JsonClass(generateAdapter = true)
data class AdFileNode(
    @Json(name = "n") val name: String?,
    @Json(name = "s") val size: Long?,
    @Json(name = "l") val link: String?,
    @Json(name = "e") val children: List<AdFileNode>?
)

@JsonClass(generateAdapter = true)
data class AdUnlockData(
    @Json(name = "id") val id: String?,
    @Json(name = "link") val link: String?,
    @Json(name = "filename") val filename: String?,
    @Json(name = "filesize") val filesize: Long?,
    @Json(name = "host") val host: String?
)

@JsonClass(generateAdapter = true)
data class AdUploadData(@Json(name = "magnets") val magnets: List<AdUploaded>?)

@JsonClass(generateAdapter = true)
data class AdUploadFileData(@Json(name = "files") val files: List<AdUploaded>?)

@JsonClass(generateAdapter = true)
data class AdUploaded(
    @Json(name = "id") val id: Long?,
    @Json(name = "name") val name: String?,
    @Json(name = "ready") val ready: Boolean?,
    @Json(name = "error") val error: AdError?
)

@JsonClass(generateAdapter = true)
data class AdPinData(
    @Json(name = "pin") val pin: String,
    @Json(name = "check") val check: String,
    @Json(name = "expires_in") val expiresIn: Int?,
    @Json(name = "user_url") val userUrl: String?,
    @Json(name = "base_url") val baseUrl: String?
)

@JsonClass(generateAdapter = true)
data class AdPinCheckData(
    @Json(name = "activated") val activated: Boolean?,
    @Json(name = "apikey") val apikey: String?
)

private fun <T> AdEnvelope<T>.unwrap(): T {
    if (status != "success") throw ProviderException("AllDebrid: ${error?.message ?: error?.code ?: "request failed"}")
    return data ?: throw ProviderException("AllDebrid: empty response")
}

@Singleton
class AllDebridProvider @Inject constructor(
    private val api: AllDebridApi
) : DebridProvider {

    override val info = ProviderInfo(
        id = ProviderId.ALL_DEBRID,
        displayName = "AllDebrid",
        tokenUrl = "https://alldebrid.com/apikeys/",
        tokenUrlLabel = "alldebrid.com/apikeys",
        tokenLabel = "API key",
        emptyListHint = "Add a magnet or torrent on alldebrid.com to populate this list.",
        websiteUrl = "https://alldebrid.com/"
    )

    override suspend fun validateToken(token: String): AccountInfo =
        api.validateUser("Bearer $token").unwrap().user.toAccount()

    override suspend fun account(): AccountInfo = api.user().unwrap().user.toAccount()

    private fun AdUser?.toAccount() = AccountInfo(
        displayName = this?.username ?: this?.email ?: "AllDebrid user",
        premiumUntil = this?.premiumUntil?.takeIf { it > 0 }?.let { Instant.ofEpochSecond(it).toString() },
        plan = if (this?.isPremium == true) "Premium" else "Free"
    )

    // --- library -----------------------------------------------------------

    override suspend fun listFiles(page: Int, pageSize: Int): Page<DownloadItem> {
        // AllDebrid returns every magnet at once; page over the ready ones and
        // fetch the file trees only for that slice.
        val ready = api.magnetStatus().unwrap().magnets.orEmpty()
            .filter { it.statusCode == STATUS_READY }
            .sortedByDescending { it.uploadDate ?: 0 }
        val from = ((page - 1).coerceAtLeast(0) * pageSize).coerceAtMost(ready.size)
        val slice = ready.subList(from, (from + pageSize).coerceAtMost(ready.size))
        if (slice.isEmpty()) return Page(emptyList(), hasMore = false)
        return Page(filesFor(slice), hasMore = from + pageSize < ready.size)
    }

    private suspend fun filesFor(magnets: List<AdMagnet>): List<DownloadItem> {
        val byId = magnets.associateBy { it.id }
        return api.magnetFiles(magnets.map { it.id }).unwrap().magnets.orEmpty().flatMap { m ->
            val magnet = byId[m.id]
            flatten(m.files.orEmpty()).map { (node, _) ->
                val name = node.name ?: "file"
                DownloadItem(
                    id = "ad:${m.id}:${node.link.hashCode()}",
                    provider = ProviderId.ALL_DEBRID,
                    filename = name,
                    sourceRef = node.link!!,
                    downloadUrl = null, // AllDebrid file links must be unlocked first
                    host = magnet?.filename?.takeIf { it != name }?.let { "Torrent · $it" } ?: "Torrent",
                    filesize = node.size ?: 0L,
                    maxConnections = DEFAULT_CONNECTIONS,
                    addedAt = magnet?.uploadDate?.let { Instant.ofEpochSecond(it).toString() }
                )
            }
        }
    }

    /** Files (with a link) from a nested tree, with their folder path. */
    private fun flatten(nodes: List<AdFileNode>, path: String = ""): List<Pair<AdFileNode, String>> =
        nodes.flatMap { n ->
            when {
                n.link != null -> listOf(n to path)
                n.children != null -> flatten(n.children, path + (n.name ?: "") + "/")
                else -> emptyList()
            }
        }

    override suspend fun resolveLink(sourceRef: String): ResolvedLink {
        val u = api.unlock(sourceRef).unwrap()
        val link = u.link?.takeIf { it.isNotBlank() }
            ?: throw ProviderException("AllDebrid couldn't unlock this file right now")
        return ResolvedLink(url = link, maxConnections = null, filesize = u.filesize?.takeIf { it > 0 })
    }

    // --- adding ------------------------------------------------------------

    override val addCapabilities = setOf(AddKind.MAGNET, AddKind.TORRENT_FILE, AddKind.LINK)

    override suspend fun add(request: AddRequest): AddResult = when (request) {
        is AddRequest.Magnet -> {
            val m = api.uploadMagnets(listOf(request.uri)).unwrap().magnets.orEmpty().firstOrNull()
                ?: throw ProviderException("AllDebrid didn't accept the magnet")
            m.error?.let { throw ProviderException("AllDebrid: ${it.message ?: it.code}") }
            AddResult(
                message = if (m.ready == true) "Cached — ready now" else "Added to AllDebrid",
                jobRef = m.id?.let { "magnet:$it" }
            )
        }
        is AddRequest.TorrentFile -> {
            val part = MultipartBody.Part.createFormData(
                "files[]", request.name, request.bytes.toRequestBody("application/x-bittorrent".toMediaType())
            )
            val f = api.uploadFile(part).unwrap().files.orEmpty().firstOrNull()
                ?: throw ProviderException("AllDebrid didn't accept the torrent")
            f.error?.let { throw ProviderException("AllDebrid: ${it.message ?: it.code}") }
            AddResult(
                message = if (f.ready == true) "Cached — ready now" else "Added to AllDebrid",
                jobRef = f.id?.let { "magnet:$it" }
            )
        }
        is AddRequest.Link -> {
            // Unlocking a hoster link gives a direct download straight away.
            val u = api.unlock(request.url).unwrap()
            val link = u.link ?: throw ProviderException("AllDebrid couldn't unlock that link")
            val name = u.filename ?: "download"
            AddResult(
                message = "Unlocked $name",
                readyFiles = listOf(
                    DownloadItem(
                        id = "ad:link:${request.url.hashCode()}",
                        provider = ProviderId.ALL_DEBRID,
                        filename = name,
                        sourceRef = request.url,
                        downloadUrl = link,
                        host = u.host ?: "link",
                        filesize = u.filesize ?: 0L,
                        maxConnections = DEFAULT_CONNECTIONS,
                        addedAt = null
                    )
                )
            )
        }
    }

    // --- jobs --------------------------------------------------------------

    override suspend fun listProcessing(): List<RemoteJob> =
        api.magnetStatus().unwrap().magnets.orEmpty()
            .filter { (it.statusCode ?: 0) < STATUS_READY }
            .map { m ->
                RemoteJob(
                    ref = "magnet:${m.id}",
                    provider = ProviderId.ALL_DEBRID,
                    name = m.filename ?: "Magnet ${m.id}",
                    progress = if ((m.size ?: 0) > 0) ((m.downloaded ?: 0).toFloat() / m.size!!).coerceIn(0f, 1f) else null,
                    status = m.status ?: "processing",
                    sizeBytes = m.size ?: 0L,
                    etaSeconds = null,
                    bytesPerSecond = m.downloadSpeed?.takeIf { it > 0 }
                )
            }

    override suspend fun filesForJob(jobRef: String): List<DownloadItem>? {
        val id = jobRef.removePrefix("magnet:").toLongOrNull()
            ?: throw ProviderException("Corrupt AllDebrid job reference: $jobRef")
        val m = api.magnetStatus(id).unwrap().magnets.orEmpty().firstOrNull { it.id == id }
            ?: throw ProviderException("AllDebrid: magnet $id is gone")
        val code = m.statusCode ?: 0
        return when {
            code == STATUS_READY -> filesFor(listOf(m))
            code > STATUS_READY -> throw ProviderException("AllDebrid: ${m.status ?: "magnet failed"}")
            else -> null
        }
    }

    // --- PIN sign-in -------------------------------------------------------

    override val deviceLogin: DeviceLogin = object : DeviceLogin {
        override suspend fun start(): DeviceCode {
            val p = api.pinGet().unwrap()
            return DeviceCode(
                userCode = p.pin,
                verificationUrl = p.baseUrl ?: "https://alldebrid.com/pin/",
                directUrl = p.userUrl,
                intervalSeconds = 5,
                expiresAtMillis = System.currentTimeMillis() + (p.expiresIn ?: 600) * 1000L,
                handle = p.pin + "|" + p.check
            )
        }

        override suspend fun poll(code: DeviceCode): DeviceGrant? {
            val (pin, check) = code.handle.split('|', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            val r = api.pinCheck(pin, check).unwrap()
            val key = r.apikey?.takeIf { r.activated == true && it.isNotBlank() } ?: return null
            // AllDebrid API keys don't expire, so there's nothing to refresh.
            return DeviceGrant(accessToken = key, refresh = null)
        }
    }

    private companion object {
        const val STATUS_READY = 4
        const val DEFAULT_CONNECTIONS = 8
    }
}
