package com.abhinavxt.debforge.data.search

import android.content.Context
import com.abhinavxt.debforge.R
import com.abhinavxt.debforge.data.repository.AuthRepository
import com.abhinavxt.debforge.data.repository.DownloadsRepository
import com.abhinavxt.debforge.di.ApiHttpClient
import com.abhinavxt.debforge.data.metadata.MetadataRepository
import com.abhinavxt.debforge.data.prefs.SettingsStore
import com.abhinavxt.debforge.domain.DataResult
import com.abhinavxt.debforge.domain.MediaKind
import com.abhinavxt.debforge.domain.SearchKind
import com.abhinavxt.debforge.domain.SearchResult
import com.abhinavxt.debforge.domain.SearchResults
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Searches every source the user added (Jackett / Prowlarr, over Torznab)
 * at once, then asks the signed-in services that can tell (TorBox,
 * Premiumize) which results they already have cached. The user's release
 * preferences apply here, so Search and followed shows both honour them.
 *
 * Uses the auth-free API client: these are the user's own servers, so no
 * service token ever goes to them.
 */
@Singleton
class SearchRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApiHttpClient api: OkHttpClient,
    private val store: SearchSourceStore,
    private val auth: AuthRepository,
    private val downloads: DownloadsRepository,
    private val metadata: MetadataRepository,
    private val settings: SettingsStore
) {
    /** Jackett's "all indexers" waits for its slowest tracker: give it longer than a service call. */
    private val http: OkHttpClient = api.newBuilder().readTimeout(90, java.util.concurrent.TimeUnit.SECONDS).build()

    /** Results from the sources that answered, and a message for each that didn't. */
    data class Outcome(val results: List<SearchResult>, val failures: List<String>)

    /** What to look for. [season] / [episode] only count for [SearchKind.SHOW]. */
    data class Query(
        val text: String,
        val kind: SearchKind = SearchKind.ALL,
        val season: Int? = null,
        val episode: Int? = null
    )

    suspend fun search(query: Query): Outcome = coroutineScope {
        val q = if (query.kind == SearchKind.SHOW) query.copy(text = query.text.trim())
        else query.copy(text = query.text.trim(), season = null, episode = null)
        // An IMDb id makes movie / TV searches exact on indexers that support it.
        val imdb = when (q.kind) {
            SearchKind.MOVIE -> metadata.imdbId(q.text, MediaKind.MOVIE)
            SearchKind.SHOW -> metadata.imdbId(q.text, MediaKind.SHOW)
            SearchKind.ALL -> null
        }
        val replies = store.all().map { source -> async { searchOne(source, q, imdb) } }.awaitAll()
        val prefs = settings.releasePrefs()
        val found = replies.flatMap { (it as? DataResult.Success)?.data.orEmpty() }
            .filterNot { prefs.avoids(it.title) }
            .map { it.copy(preferred = prefs.score(it.title)) }
        Outcome(
            results = SearchResults.rank(SearchResults.forEpisode(found, q.season, q.episode)),
            failures = replies.mapNotNull { (it as? DataResult.Error)?.message }
        )
    }

    /**
     * Torznab's movie / TV search where the source has it; a plain search
     * ("The Bear S02E05") where it says it doesn't (Torznab errors 200-203:
     * unknown function or parameter).
     */
    private suspend fun searchOne(source: SearchSource, q: Query, imdb: String?): DataResult<List<SearchResult>> {
        val specific = Torznab.searchParams(q.text, q.kind, q.season, q.episode, imdb)
        val first = fetch(source, specific) { Torznab.parseResults(it) }
        val unsupported = (first as? DataResult.Error)?.cause.let { it is Torznab.TorznabError && it.code in 200..203 }
        if (!unsupported || specific["t"] == "search") return first
        return fetch(source, Torznab.searchParams(Torznab.plainQuery(q.text, q.season, q.episode), SearchKind.ALL, null, null, null)) {
            Torznab.parseResults(it)
        }
    }

    /** Checks a source before it's saved: the feed answers and accepts the key. Null = fine, else why not. */
    suspend fun test(source: SearchSource): String? =
        (fetch(source, mapOf("t" to "caps")) { Torznab.checkCaps(it) } as? DataResult.Error)?.message

    /**
     * Which of [hashes] are cached, on any signed-in service that can check;
     * null when none can (the results then show no badge rather than "not cached").
     */
    suspend fun cachedAmong(hashes: List<String>): Set<String>? = coroutineScope {
        val checkers = auth.signedInProviders.first().filter { downloads.supportsCacheCheck(it) }
        if (checkers.isEmpty() || hashes.isEmpty()) return@coroutineScope null
        val answers = checkers.map { p -> async { downloads.cachedHashes(p, hashes) } }.awaitAll()
        val ok = answers.filterIsInstance<DataResult.Success<Set<String>>>()
        if (ok.isEmpty()) null else ok.flatMapTo(HashSet()) { it.data }
    }

    private suspend fun <T> fetch(source: SearchSource, params: Map<String, String>, read: (String) -> T): DataResult<T> =
        withContext(Dispatchers.IO) {
            val name = source.name
            val url = Torznab.apiUrl(source.url, source.apiKey, params)
                ?: return@withContext DataResult.Error(context.getString(R.string.search_err_bad_url))
            try {
                http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    when {
                        response.code == 401 || response.code == 403 ->
                            DataResult.Error(context.getString(R.string.search_err_key, name))
                        !response.isSuccessful ->
                            DataResult.Error(context.getString(R.string.err_http, name, response.code))
                        else -> DataResult.Success(read(response.body?.string().orEmpty()))
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Torznab.TorznabError) {
                DataResult.Error(
                    if (e.code == 100) context.getString(R.string.search_err_key, name)
                    else context.getString(R.string.search_err_said, name, e.message.orEmpty()),
                    e
                )
            } catch (e: IOException) {
                DataResult.Error(context.getString(R.string.search_err_unreachable, name), e)
            } catch (e: Exception) {
                // Not XML, or XML that isn't a feed (a login page, a proxy's error page).
                DataResult.Error(context.getString(R.string.search_err_not_torznab, name), e)
            }
        }
}
