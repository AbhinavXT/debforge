package com.abhinavxt.debforge.data.remote

import com.abhinavxt.debforge.data.remote.dto.DownloadDto
import com.abhinavxt.debforge.data.remote.dto.UnrestrictDto
import com.abhinavxt.debforge.data.remote.dto.UserDto
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query

/**
 * Real-Debrid REST API (base https://api.real-debrid.com/rest/1.0/).
 * Auth is handled transparently by [AuthInterceptor] which attaches the
 * Bearer token to every request, so no @Header here.
 *
 * Rate limit: 250 req/min globally; refused requests return HTTP 429 and
 * still count. Callers must paginate conservatively.
 */
interface RealDebridApi {

    /** Validates the token and returns account info. */
    @GET("user")
    suspend fun getUser(): UserDto

    /**
     * Same endpoint as [getUser] but with an explicit Authorization header, used
     * during sign-in to validate a candidate token BEFORE we persist it. The
     * [AuthInterceptor] sees the pre-set header and leaves it alone.
     */
    @GET("user")
    suspend fun validateUser(@Header("Authorization") authorization: String): UserDto

    /**
     * Paginated downloads list.
     * NOTE: never send both [offset] and [page] — RD prioritizes page and the
     * combination is undefined. We use page-based pagination only.
     *
     * Returns nullable because RD answers HTTP 204 No Content when the list is
     * empty (zero downloads on the account). Retrofit converts an absent body
     * to null only if the declared type allows it; making this non-null would
     * throw KotlinNullPointerException on 204.
     */
    @GET("downloads")
    suspend fun getDownloads(
        @Query("page") page: Int,
        @Query("limit") limit: Int
    ): List<DownloadDto>?

    /**
     * Re-unrestrict an original hoster link to obtain a fresh direct download URL.
     * Used as the dead-link fallback.
     */
    @FormUrlEncoded
    @POST("unrestrict/link")
    suspend fun unrestrictLink(
        @Field("link") link: String
    ): UnrestrictDto

    companion object {
        const val BASE_URL = "https://api.real-debrid.com/rest/1.0/"
    }
}
