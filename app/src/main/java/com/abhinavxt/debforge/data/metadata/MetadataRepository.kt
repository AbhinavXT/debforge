package com.abhinavxt.debforge.data.metadata

import com.abhinavxt.debforge.BuildConfig
import com.abhinavxt.debforge.data.prefs.SettingsStore
import com.abhinavxt.debforge.domain.MediaKind
import com.abhinavxt.debforge.domain.ReleaseInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Artwork + blurb for one title, ready for the UI. */
data class MediaMeta(
    val title: String,
    val year: Int?,
    val posterUrl: String?,
    val backdropUrl: String?,
    val overview: String?
)

/**
 * Finds posters for parsed titles. Three layers: in-memory map -> Room cache
 * -> TMDB. At most [MAX_PARALLEL] requests run at once and concurrent requests
 * for the same title share one network call.
 *
 * Without a TMDB key this is a no-op that returns null (the UI then shows a
 * typographic placeholder), and nothing is cached so adding a key later works
 * immediately.
 */
@Singleton
class MetadataRepository @Inject constructor(
    private val api: TmdbApi,
    private val dao: MediaMetaDao,
    private val settings: SettingsStore
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val memory = ConcurrentHashMap<String, Cached>()
    private val inflight = ConcurrentHashMap<String, Deferred<MediaMeta?>>()
    private val gate = Semaphore(MAX_PARALLEL)

    /** Wrapper so a cached "not found" is distinguishable from "not looked up". */
    private data class Cached(val meta: MediaMeta?)

    val enabled: Flow<Boolean> = settings.tmdbKeyFlow.map { effectiveKey(it).isNotBlank() }

    /** Synchronous peek at the memory cache (for first composition). */
    fun peek(groupKey: String): MediaMeta? = memory[groupKey]?.meta

    suspend fun lookup(info: ReleaseInfo): MediaMeta? {
        if (info.kind == MediaKind.OTHER) return null
        val key = info.groupKey
        memory[key]?.let { return it.meta }
        if (effectiveKey(settings.tmdbKeyFlow.first()).isBlank()) return null

        // LAZY + putIfAbsent: exactly one load per key even if many cards ask
        // at the same moment; losers await the winner's result.
        val fresh = scope.async(start = CoroutineStart.LAZY) { load(key, info) }
        val job = inflight.putIfAbsent(key, fresh) ?: fresh
        if (job !== fresh) fresh.cancel()
        return try {
            job.await()
        } finally {
            inflight.remove(key, job)
        }
    }

    /** Forget everything (e.g. after the user changes the TMDB key). */
    suspend fun clearCache() {
        memory.clear()
        dao.clear()
    }

    private suspend fun load(key: String, info: ReleaseInfo): MediaMeta? {
        val now = System.currentTimeMillis()
        dao.get(key)?.let { row ->
            val maxAge = if (row.found) FOUND_TTL_MS else MISS_TTL_MS
            if (now - row.fetchedAt < maxAge) {
                val meta = row.toMeta()
                memory[key] = Cached(meta)
                return meta
            }
        }

        val meta = try {
            gate.withPermit { search(info) }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            // Network / auth / parse failure: don't cache, try again later.
            return null
        }
        dao.put(
            MediaMetaEntity(
                cacheKey = key,
                found = meta != null,
                title = meta?.title,
                year = meta?.year,
                posterPath = meta?.posterUrl,
                backdropPath = meta?.backdropUrl,
                overview = meta?.overview,
                fetchedAt = now
            )
        )
        memory[key] = Cached(meta)
        return meta
    }

    private suspend fun search(info: ReleaseInfo): MediaMeta? = when (info.kind) {
        MediaKind.SHOW -> {
            // Retry without the year: "Doctor Who 2005" is tagged with the
            // revival year, which TMDB's first_air_date_year may not match.
            val hits = api.searchTv(info.title, info.year).results.orEmpty()
                .ifEmpty { if (info.year != null) api.searchTv(info.title).results.orEmpty() else emptyList() }
            hits.firstOrNull()?.let {
                MediaMeta(
                    title = it.name ?: info.title,
                    year = it.firstAirDate?.take(4)?.toIntOrNull(),
                    posterUrl = TmdbApi.posterUrl(it.posterPath),
                    backdropUrl = TmdbApi.posterUrl(it.backdropPath, "w780"),
                    overview = it.overview?.takeIf { o -> o.isNotBlank() }
                )
            }
        }
        MediaKind.MOVIE -> {
            val hits = api.searchMovie(info.title, info.year).results.orEmpty()
                .ifEmpty { if (info.year != null) api.searchMovie(info.title).results.orEmpty() else emptyList() }
            hits.firstOrNull()?.let {
                MediaMeta(
                    title = it.title ?: info.title,
                    year = it.releaseDate?.take(4)?.toIntOrNull() ?: info.year,
                    posterUrl = TmdbApi.posterUrl(it.posterPath),
                    backdropUrl = TmdbApi.posterUrl(it.backdropPath, "w780"),
                    overview = it.overview?.takeIf { o -> o.isNotBlank() }
                )
            }
        }
        MediaKind.OTHER -> null
    }

    // The entity stores full URLs (not TMDB paths) so the UI never needs to
    // know about TMDB's image CDN.
    private fun MediaMetaEntity.toMeta(): MediaMeta? =
        if (!found) null else MediaMeta(title.orEmpty(), year, posterPath, backdropPath, overview)

    companion object {
        private const val MAX_PARALLEL = 4
        private const val FOUND_TTL_MS = 30L * 24 * 60 * 60 * 1000
        private const val MISS_TTL_MS = 7L * 24 * 60 * 60 * 1000

        /** User key wins; otherwise the key baked in at build time (may be empty). */
        fun effectiveKey(userKey: String?): String =
            userKey?.trim()?.takeIf { it.isNotEmpty() } ?: BuildConfig.TMDB_API_KEY
    }
}

/**
 * Adds TMDB auth. Accepts either credential TMDB issues: a v4 "API Read
 * Access Token" (a JWT, starts with "eyJ") goes in a Bearer header; a v3
 * "API Key" (32 hex chars) goes in the `api_key` query parameter.
 */
class TmdbAuthInterceptor(private val settings: SettingsStore) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val key = MetadataRepository.effectiveKey(runBlocking { settings.tmdbKeyFlow.first() })
        val req = chain.request()
        if (key.isBlank()) return chain.proceed(req)
        val authed = if (key.startsWith("eyJ")) {
            req.newBuilder().header("Authorization", "Bearer $key").build()
        } else {
            req.newBuilder().url(req.url.newBuilder().addQueryParameter("api_key", key).build()).build()
        }
        return chain.proceed(authed)
    }
}
