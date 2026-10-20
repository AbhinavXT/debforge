package com.abhinavxt.debforge.data.search

import android.content.Context
import com.abhinavxt.debforge.R
import com.abhinavxt.debforge.data.repository.AuthRepository
import com.abhinavxt.debforge.data.repository.DownloadsRepository
import com.abhinavxt.debforge.di.ApiHttpClient
import com.abhinavxt.debforge.domain.DataResult
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
 * Premiumize) which results they already have cached.
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
    private val downloads: DownloadsRepository
) {
    /** Jackett's "all indexers" waits for its slowest tracker: give it longer than a service call. */
    private val http: OkHttpClient = api.newBuilder().readTimeout(90, java.util.concurrent.TimeUnit.SECONDS).build()

    /** Results from the sources that answered, and a message for each that didn't. */
    data class Outcome(val results: List<SearchResult>, val failures: List<String>)

    suspend fun search(query: String): Outcome = coroutineScope {
        val sources = store.all()
        val replies = sources.map { source ->
            async { source to fetch(source, mapOf("t" to "search", "q" to query.trim())) { Torznab.parseResults(it) } }
        }.awaitAll()
        Outcome(
            results = SearchResults.rank(replies.flatMap { (_, r) -> (r as? DataResult.Success)?.data.orEmpty() }),
            failures = replies.mapNotNull { (_, r) -> (r as? DataResult.Error)?.message }
        )
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
