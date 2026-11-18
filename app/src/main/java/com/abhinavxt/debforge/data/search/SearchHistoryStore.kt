package com.abhinavxt.debforge.data.search

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import javax.inject.Inject
import javax.inject.Singleton

private val Context.searchHistoryDataStore by preferencesDataStore(name = "debforge_search_history")

/**
 * The last few searches, newest first, shown under the Search tab's search bar. Included in backups (see SearchesBackup).
 * Other screens keep their own list in the same file under another [keyName] (see [YouTubeSearchHistoryStore]).
 */
@Singleton
open class SearchHistoryStore internal constructor(private val context: Context, keyName: String) {

    @Inject constructor(@ApplicationContext context: Context) : this(context, "queries")

    private val key = stringPreferencesKey(keyName)

    val recent: Flow<List<String>> = context.searchHistoryDataStore.data
        .map { decode(it[key]) }
        .distinctUntilChanged()

    /** Moves [query] to the top (the same words in other case count as the same search). */
    suspend fun add(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        context.searchHistoryDataStore.edit { prefs ->
            prefs[key] = encode(remember(decode(prefs[key]), q))
        }
    }

    suspend fun remove(query: String) = context.searchHistoryDataStore.edit { prefs ->
        prefs[key] = encode(decode(prefs[key]).filterNot { it.equals(query, ignoreCase = true) })
    }

    suspend fun all(): List<String> = decode(context.searchHistoryDataStore.data.first()[key])

    /** Backup restore: what is here first, then the new ones from [incoming], de-duplicated, capped. Returns how many were new. */
    suspend fun mergeFromBackup(incoming: List<String>): Int {
        var added = 0
        context.searchHistoryDataStore.edit { prefs ->
            val now = decode(prefs[key])
            val merged = com.abhinavxt.debforge.data.backup.SearchesBackup.merge(now, incoming)
            added = com.abhinavxt.debforge.data.backup.SearchesBackup.added(now, merged)
            prefs[key] = encode(merged)
        }
        return added
    }

    suspend fun clear() = context.searchHistoryDataStore.edit { it.remove(key) }

    private fun encode(list: List<String>): String = JSONArray(list).toString()

    private fun decode(json: String?): List<String> {
        val arr = json?.let { runCatching { JSONArray(it) }.getOrNull() } ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
    }

    companion object {
        const val MAX = 10

        /** [list] with [query] on top, without other copies of it, at most [MAX] long. */
        fun remember(list: List<String>, query: String): List<String> =
            (listOf(query) + list.filterNot { it.equals(query, ignoreCase = true) }).take(MAX)
    }
}

/** The YouTube tab's own recent searches: same rules and file as [SearchHistoryStore], kept under "youtube_queries". */
@Singleton
class YouTubeSearchHistoryStore @Inject constructor(@ApplicationContext context: Context) :
    SearchHistoryStore(context, "youtube_queries")
