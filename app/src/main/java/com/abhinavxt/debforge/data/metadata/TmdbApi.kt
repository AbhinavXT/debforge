package com.abhinavxt.debforge.data.metadata

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Query

/**
 * The Movie Database (TMDB) v3 search — used only to find a poster, year and
 * overview for titles parsed from filenames. Auth (v3 `api_key` param or v4
 * Bearer token) is added by [TmdbAuthInterceptor].
 */
interface TmdbApi {

    @GET("search/tv")
    suspend fun searchTv(
        @Query("query") query: String,
        @Query("first_air_date_year") year: Int? = null,
        @Query("include_adult") includeAdult: Boolean = false
    ): TmdbSearchResponse<TmdbTvResult>

    @GET("search/movie")
    suspend fun searchMovie(
        @Query("query") query: String,
        @Query("year") year: Int? = null,
        @Query("include_adult") includeAdult: Boolean = false
    ): TmdbSearchResponse<TmdbMovieResult>

    /**
     * Checks a key the user is about to save, before it's stored. The
     * credential is passed explicitly (exactly one of the two), so
     * [TmdbAuthInterceptor] leaves the request alone. 200 = valid, 401 = not.
     */
    @GET("authentication")
    suspend fun checkKey(
        @Header("Authorization") bearer: String? = null,
        @Query("api_key") apiKey: String? = null
    ): Response<TmdbAuthCheck>

    companion object {
        /** Where TMDB shows (and issues) both kinds of key. Free, needs an account. */
        const val KEY_PAGE_URL = "https://www.themoviedb.org/settings/api"
        const val BASE_URL = "https://api.themoviedb.org/3/"
        private const val IMAGE_BASE = "https://image.tmdb.org/t/p/"

        fun posterUrl(path: String?, size: String = "w342"): String? =
            path?.takeIf { it.isNotBlank() }?.let { IMAGE_BASE + size + it }
    }
}

@JsonClass(generateAdapter = true)
data class TmdbAuthCheck(@Json(name = "success") val success: Boolean?)

@JsonClass(generateAdapter = true)
data class TmdbSearchResponse<T>(
    @Json(name = "results") val results: List<T>?
)

@JsonClass(generateAdapter = true)
data class TmdbTvResult(
    @Json(name = "id") val id: Long,
    @Json(name = "name") val name: String?,
    @Json(name = "first_air_date") val firstAirDate: String?,
    @Json(name = "poster_path") val posterPath: String?,
    @Json(name = "backdrop_path") val backdropPath: String?,
    @Json(name = "overview") val overview: String?
)

@JsonClass(generateAdapter = true)
data class TmdbMovieResult(
    @Json(name = "id") val id: Long,
    @Json(name = "title") val title: String?,
    @Json(name = "release_date") val releaseDate: String?,
    @Json(name = "poster_path") val posterPath: String?,
    @Json(name = "backdrop_path") val backdropPath: String?,
    @Json(name = "overview") val overview: String?
)
