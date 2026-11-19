package com.abhinavxt.debforge.data.sponsor

import com.abhinavxt.debforge.di.ApiHttpClient
import com.abhinavxt.debforge.domain.SponsorSegment
import com.squareup.moshi.JsonClass
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.JsonEncodingException
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

interface SponsorBlockApi {
    /** Privacy-preserving lookup: only the first 4 hex chars of the video id's SHA-256 leave the device. */
    @GET("api/skipSegments/{hashPrefix}")
    suspend fun skipSegments(
        @Path("hashPrefix") hashPrefix: String,
        @Query("categories") categories: String,
        @Query("actionType") actionType: String = "skip"
    ): Response<List<SponsorVideoDto>>

    companion object { const val BASE_URL = "https://sponsor.ajay.app/" }
}

@JsonClass(generateAdapter = true)
data class SponsorVideoDto(val videoID: String = "", val segments: List<SponsorSegmentDto> = emptyList())

@JsonClass(generateAdapter = true)
data class SponsorSegmentDto(
    val category: String = "",
    val segment: List<Double> = emptyList(),
    val UUID: String = "",
    val actionType: String = "skip"
)

/** SponsorBlock lookups. Throws IOException on network or server errors; 404 is "no segments". */
@Singleton
class SponsorBlockClient @Inject constructor(
    @ApiHttpClient base: OkHttpClient,
    private val moshi: Moshi
) {
    private val api: SponsorBlockApi = Retrofit.Builder()
        .baseUrl(SponsorBlockApi.BASE_URL)
        .client(base)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()
        .create(SponsorBlockApi::class.java)

    suspend fun segments(videoId: String, categories: Collection<String>): List<SponsorSegment> {
        if (videoId.isBlank() || categories.isEmpty()) return emptyList()
        val res = try {
            api.skipSegments(hashPrefix(videoId), categoriesParam(categories))
        } catch (e: JsonDataException) {
            throw IOException("SponsorBlock sent an unreadable response", e)
        } catch (e: JsonEncodingException) {
            throw IOException("SponsorBlock sent an unreadable response", e)
        }
        if (res.code() == 404) return emptyList()
        if (!res.isSuccessful) throw IOException("SponsorBlock HTTP ${res.code()}")
        return filterFor(videoId, res.body().orEmpty(), categories)
    }

    private val listAdapter by lazy {
        moshi.adapter<List<SponsorSegment>>(Types.newParameterizedType(List::class.java, SponsorSegment::class.java))
    }

    /** For caching a lookup result. */
    fun toJson(segments: List<SponsorSegment>): String = listAdapter.toJson(segments)

    fun fromJson(json: String): List<SponsorSegment> = runCatching { listAdapter.fromJson(json) }.getOrNull().orEmpty()

    companion object {
        /** First 4 hex chars of sha256(videoId). */
        fun hashPrefix(videoId: String): String =
            MessageDigest.getInstance("SHA-256").digest(videoId.toByteArray())
                .take(2).joinToString("") { "%02x".format(it) }

        /** JSON array as SponsorBlock expects: ["sponsor","selfpromo"]. */
        fun categoriesParam(categories: Collection<String>): String =
            categories.joinToString(",", "[", "]") { "\"$it\"" }

        /** Keeps only [videoId]'s skip segments in the requested [categories] (the hash prefix matches other videos too). */
        fun filterFor(videoId: String, videos: List<SponsorVideoDto>, categories: Collection<String>): List<SponsorSegment> =
            videos.filter { it.videoID == videoId }
                .flatMap { it.segments }
                .filter { it.actionType == "skip" && it.category in categories && it.segment.size == 2 }
                .map { SponsorSegment(it.category, (it.segment[0] * 1000).toLong(), (it.segment[1] * 1000).toLong(), it.UUID) }
                .sortedBy { it.startMs }
    }
}
