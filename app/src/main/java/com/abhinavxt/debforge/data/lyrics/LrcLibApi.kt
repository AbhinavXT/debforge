package com.abhinavxt.debforge.data.lyrics

import retrofit2.http.GET
import retrofit2.http.Query

/** LRCLIB (https://lrclib.net/docs): free lyrics, no key. */
interface LrcLibApi {
    @GET("api/search")
    suspend fun search(
        @Query("track_name") track: String? = null,
        @Query("artist_name") artist: String? = null,
        @Query("q") q: String? = null
    ): List<LrcLibItem>

    companion object {
        const val BASE_URL = "https://lrclib.net/"
    }
}
