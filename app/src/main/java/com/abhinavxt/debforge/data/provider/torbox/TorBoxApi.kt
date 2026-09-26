package com.abhinavxt.debforge.data.provider.torbox

import com.abhinavxt.debforge.data.provider.torbox.dto.TbEnvelope
import com.abhinavxt.debforge.data.provider.torbox.dto.TbItemDto
import com.abhinavxt.debforge.data.provider.torbox.dto.TbUserDto
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Query

/**
 * TorBox REST API (base https://api.torbox.app/v1/api/).
 *
 * Auth: "Authorization: Bearer <api key>" (added by AuthInterceptor), EXCEPT
 * the `requestdl` endpoints, which take the key as a `token` query parameter
 * because they are designed to also work as plain redirect links.
 *
 * Rate limit: 300 req/min per key.
 *
 * `mylist` supports offset/limit pagination. Its responses are cached
 * server-side for a few seconds; `bypass_cache=true` forces fresh data (we
 * only send it on the first page of a manual refresh).
 */
interface TorBoxApi {

    @GET("user/me")
    suspend fun validateUser(@Header("Authorization") authorization: String): TbEnvelope<TbUserDto>

    // --- listings ----------------------------------------------------------

    @GET("torrents/mylist")
    suspend fun torrents(
        @Query("offset") offset: Int,
        @Query("limit") limit: Int,
        @Query("bypass_cache") bypassCache: Boolean = false
    ): TbEnvelope<List<TbItemDto>>

    @GET("usenet/mylist")
    suspend fun usenet(
        @Query("offset") offset: Int,
        @Query("limit") limit: Int,
        @Query("bypass_cache") bypassCache: Boolean = false
    ): TbEnvelope<List<TbItemDto>>

    @GET("webdl/mylist")
    suspend fun webDownloads(
        @Query("offset") offset: Int,
        @Query("limit") limit: Int,
        @Query("bypass_cache") bypassCache: Boolean = false
    ): TbEnvelope<List<TbItemDto>>

    // --- direct links (valid ~3h to START; a started download can run long) --

    @GET("torrents/requestdl")
    suspend fun torrentLink(
        @Query("token") token: String,
        @Query("torrent_id") itemId: Long,
        @Query("file_id") fileId: Long
    ): TbEnvelope<String>

    @GET("usenet/requestdl")
    suspend fun usenetLink(
        @Query("token") token: String,
        @Query("usenet_id") itemId: Long,
        @Query("file_id") fileId: Long
    ): TbEnvelope<String>

    @GET("webdl/requestdl")
    suspend fun webDownloadLink(
        @Query("token") token: String,
        @Query("web_id") itemId: Long,
        @Query("file_id") fileId: Long
    ): TbEnvelope<String>

    // --- adding (multipart/form-data, per TorBox's SDKs) -----------------------
    //
    // Creation of UNCACHED items is limited to 60/hour; cached items are
    // effectively instant and count against the normal 300/min limit. The
    // `data` payload differs per endpoint (torrent_id / queued_id / ...), and
    // we only surface `detail`, so it's parsed loosely.

    @Multipart
    @POST("torrents/createtorrent")
    suspend fun createTorrentFromMagnet(@Part("magnet") magnet: RequestBody): TbEnvelope<Any>

    @Multipart
    @POST("torrents/createtorrent")
    suspend fun createTorrentFromFile(@Part file: MultipartBody.Part): TbEnvelope<Any>

    @Multipart
    @POST("webdl/createwebdownload")
    suspend fun createWebDownload(@Part("link") link: RequestBody): TbEnvelope<Any>

    companion object {
        const val BASE_URL = "https://api.torbox.app/v1/api/"
    }
}
