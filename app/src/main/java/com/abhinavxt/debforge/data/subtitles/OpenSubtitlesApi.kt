package com.abhinavxt.debforge.data.subtitles

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query

/**
 * OpenSubtitles.com REST API (https://opensubtitles.stoplight.io). The
 * user's own API key and the required User-Agent are added by
 * [OpenSubtitlesInterceptor]. Query parameters are declared in alphabetical
 * order, as the API asks (otherwise it answers with a redirect).
 */
interface OpenSubtitlesApi {

    @GET("subtitles")
    suspend fun search(
        @Query("episode_number") episode: Int? = null,
        @Query("languages") languages: String? = null,
        @Query("moviehash") movieHash: String? = null,
        @Query("query") query: String? = null,
        @Query("season_number") season: Int? = null,
        @Query("type") type: String? = null,
        @Query("year") year: Int? = null,
        @Header("Api-Key") apiKeyOverride: String? = null
    ): Response<OsSearchResponse>

    /** A temporary link to the file. Counts against the user's daily downloads; 406 when none are left. */
    @POST("download")
    suspend fun download(
        @Body body: OsDownloadRequest,
        @Header("Authorization") bearer: String? = null
    ): Response<OsDownloadResponse>

    @POST("login")
    suspend fun login(
        @Body body: OsLoginRequest,
        @Header("Api-Key") apiKeyOverride: String? = null
    ): Response<OsLoginResponse>

    companion object {
        const val BASE_URL = "https://api.opensubtitles.com/api/v1/"
        /** Where users create the free API consumer whose key DebForge asks for. */
        const val KEY_PAGE_URL = "https://www.opensubtitles.com/consumers"
    }
}

@JsonClass(generateAdapter = true)
data class OsSearchResponse(@Json(name = "data") val data: List<OsSubtitle>?)

@JsonClass(generateAdapter = true)
data class OsSubtitle(
    @Json(name = "id") val id: String?,
    @Json(name = "attributes") val attributes: OsAttributes?
)

@JsonClass(generateAdapter = true)
data class OsAttributes(
    @Json(name = "language") val language: String?,
    @Json(name = "download_count") val downloadCount: Int?,
    @Json(name = "hearing_impaired") val hearingImpaired: Boolean?,
    @Json(name = "foreign_parts_only") val foreignPartsOnly: Boolean?,
    @Json(name = "release") val release: String?,
    @Json(name = "moviehash_match") val movieHashMatch: Boolean?,
    @Json(name = "files") val files: List<OsFile>?
)

@JsonClass(generateAdapter = true)
data class OsFile(
    @Json(name = "file_id") val fileId: Long,
    @Json(name = "file_name") val fileName: String?
)

@JsonClass(generateAdapter = true)
data class OsDownloadRequest(
    @Json(name = "file_id") val fileId: Long,
    @Json(name = "sub_format") val subFormat: String = "srt"
)

@JsonClass(generateAdapter = true)
data class OsDownloadResponse(
    @Json(name = "link") val link: String?,
    @Json(name = "remaining") val remaining: Int?
)

@JsonClass(generateAdapter = true)
data class OsLoginRequest(
    @Json(name = "username") val username: String,
    @Json(name = "password") val password: String
)

@JsonClass(generateAdapter = true)
data class OsLoginResponse(@Json(name = "token") val token: String?)
