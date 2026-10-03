package com.abhinavxt.debforge.data.trakt

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Trakt v2 (https://trakt.docs.apiary.io): device sign-in, scrobbling and the
 * user's watched history. The `trakt-api-key` / `trakt-api-version` headers
 * and the Bearer token are added by [TraktAuthInterceptor].
 */
interface TraktApi {

    @POST("oauth/device/code")
    suspend fun deviceCode(@Body body: TraktDeviceCodeRequest): TraktDeviceCode

    /** 200 = approved; 400 pending, 404 bad code, 409 used, 410 expired, 418 denied, 429 slow down. */
    @POST("oauth/device/token")
    suspend fun deviceToken(@Body body: TraktDeviceTokenRequest): Response<TraktToken>

    @POST("oauth/token")
    suspend fun refresh(@Body body: TraktRefreshRequest): Response<TraktToken>

    @POST("oauth/revoke")
    suspend fun revoke(@Body body: TraktRevokeRequest): Response<Unit>

    @GET("users/settings")
    suspend fun userSettings(): TraktUserSettings

    @GET("search/{type}")
    suspend fun search(
        @Path("type") type: String,
        @Query("query") query: String,
        @Query("years") year: Int? = null,
        @Query("limit") limit: Int = 5
    ): List<TraktSearchResult>

    /** start / pause / stop. Stop at 80 %+ marks the item watched. */
    @POST("scrobble/{action}")
    suspend fun scrobble(@Path("action") action: String, @Body body: TraktScrobble): Response<Unit>

    /** Adds plays now (Library → select → Mark watched). */
    @POST("sync/history")
    suspend fun addHistory(@Body body: TraktHistory): Response<Unit>

    /** Removes every play of these (Mark unwatched). */
    @POST("sync/history/remove")
    suspend fun removeHistory(@Body body: TraktHistory): Response<Unit>

    @GET("sync/last_activities")
    suspend fun lastActivities(): TraktLastActivities

    @GET("sync/watched/shows")
    suspend fun watchedShows(): List<TraktWatchedShow>

    @GET("sync/watched/movies")
    suspend fun watchedMovies(): List<TraktWatchedMovie>

    companion object {
        const val BASE_URL = "https://api.trakt.tv/"
        /** Where users create the app whose Client ID and Secret DebForge asks for. */
        const val NEW_APP_URL = "https://trakt.tv/oauth/applications/new"
        /** The redirect URI Trakt's form wants for an app without a web page. */
        const val OOB_REDIRECT = "urn:ietf:wg:oauth:2.0:oob"
    }
}

@JsonClass(generateAdapter = true)
data class TraktDeviceCodeRequest(@Json(name = "client_id") val clientId: String)

@JsonClass(generateAdapter = true)
data class TraktDeviceCode(
    @Json(name = "device_code") val deviceCode: String,
    @Json(name = "user_code") val userCode: String,
    @Json(name = "verification_url") val verificationUrl: String,
    @Json(name = "expires_in") val expiresIn: Int?,
    @Json(name = "interval") val interval: Int?
)

@JsonClass(generateAdapter = true)
data class TraktDeviceTokenRequest(
    @Json(name = "code") val code: String,
    @Json(name = "client_id") val clientId: String,
    @Json(name = "client_secret") val clientSecret: String
)

@JsonClass(generateAdapter = true)
data class TraktRefreshRequest(
    @Json(name = "refresh_token") val refreshToken: String,
    @Json(name = "client_id") val clientId: String,
    @Json(name = "client_secret") val clientSecret: String,
    @Json(name = "redirect_uri") val redirectUri: String = TraktApi.OOB_REDIRECT,
    @Json(name = "grant_type") val grantType: String = "refresh_token"
)

@JsonClass(generateAdapter = true)
data class TraktRevokeRequest(
    @Json(name = "token") val token: String,
    @Json(name = "client_id") val clientId: String,
    @Json(name = "client_secret") val clientSecret: String
)

@JsonClass(generateAdapter = true)
data class TraktToken(
    @Json(name = "access_token") val accessToken: String,
    @Json(name = "refresh_token") val refreshToken: String?,
    @Json(name = "expires_in") val expiresIn: Long?,
    @Json(name = "created_at") val createdAt: Long?
)

@JsonClass(generateAdapter = true)
data class TraktUserSettings(@Json(name = "user") val user: TraktUser?)

@JsonClass(generateAdapter = true)
data class TraktUser(
    @Json(name = "username") val username: String?,
    @Json(name = "name") val name: String?
)

@JsonClass(generateAdapter = true)
data class TraktIds(
    @Json(name = "trakt") val trakt: Long?,
    @Json(name = "slug") val slug: String? = null,
    @Json(name = "imdb") val imdb: String? = null,
    @Json(name = "tmdb") val tmdb: Long? = null,
    @Json(name = "tvdb") val tvdb: Long? = null
)

@JsonClass(generateAdapter = true)
data class TraktMedia(
    @Json(name = "title") val title: String?,
    @Json(name = "year") val year: Int?,
    @Json(name = "ids") val ids: TraktIds?
)

@JsonClass(generateAdapter = true)
data class TraktSearchResult(
    @Json(name = "type") val type: String?,
    @Json(name = "show") val show: TraktMedia?,
    @Json(name = "movie") val movie: TraktMedia?
)

@JsonClass(generateAdapter = true)
data class TraktEpisodeRef(
    @Json(name = "season") val season: Int,
    @Json(name = "number") val number: Int
)

/** Body for scrobble/start, pause and stop: a movie, or a show plus season/episode numbers. */
@JsonClass(generateAdapter = true)
data class TraktScrobble(
    @Json(name = "movie") val movie: TraktMediaRef? = null,
    @Json(name = "show") val show: TraktMediaRef? = null,
    @Json(name = "episode") val episode: TraktEpisodeRef? = null,
    @Json(name = "progress") val progress: Double,
    @Json(name = "app_version") val appVersion: String? = null
)

@JsonClass(generateAdapter = true)
data class TraktMediaRef(@Json(name = "ids") val ids: TraktIds)

@JsonClass(generateAdapter = true)
data class TraktHistory(
    @Json(name = "shows") val shows: List<TraktHistoryShow>,
    @Json(name = "movies") val movies: List<TraktMediaRef>
)

@JsonClass(generateAdapter = true)
data class TraktHistoryShow(
    @Json(name = "ids") val ids: TraktIds,
    @Json(name = "seasons") val seasons: List<TraktHistorySeason>
)

@JsonClass(generateAdapter = true)
data class TraktHistorySeason(
    @Json(name = "number") val number: Int,
    @Json(name = "episodes") val episodes: List<TraktWatchedEpisode>
)

@JsonClass(generateAdapter = true)
data class TraktLastActivities(@Json(name = "all") val all: String?)

@JsonClass(generateAdapter = true)
data class TraktWatchedShow(
    @Json(name = "show") val show: TraktMedia?,
    @Json(name = "seasons") val seasons: List<TraktWatchedSeason>?
)

@JsonClass(generateAdapter = true)
data class TraktWatchedSeason(
    @Json(name = "number") val number: Int,
    @Json(name = "episodes") val episodes: List<TraktWatchedEpisode>?
)

@JsonClass(generateAdapter = true)
data class TraktWatchedEpisode(@Json(name = "number") val number: Int)

@JsonClass(generateAdapter = true)
data class TraktWatchedMovie(@Json(name = "movie") val movie: TraktMedia?)
