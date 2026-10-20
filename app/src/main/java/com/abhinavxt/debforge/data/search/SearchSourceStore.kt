package com.abhinavxt.debforge.data.search

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.abhinavxt.debforge.data.prefs.TokenCipher
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.searchSourcesDataStore by preferencesDataStore(name = "debforge_search_sources")

/** A Jackett or Prowlarr the user runs, searched through its Torznab feed. */
@JsonClass(generateAdapter = true)
data class SearchSource(
    @Json(name = "id") val id: String,
    @Json(name = "name") val name: String,
    @Json(name = "url") val url: String,
    @Json(name = "apiKey") val apiKey: String
)

/**
 * The user's search sources. Stored as one JSON list encrypted with the
 * Keystore key like service tokens (it holds API keys). Not part of backups.
 */
@Singleton
class SearchSourceStore @Inject constructor(
    @ApplicationContext private val context: Context,
    moshi: Moshi
) {
    private val key = stringPreferencesKey("sources")
    private val adapter = moshi.adapter<List<SearchSource>>(Types.newParameterizedType(List::class.java, SearchSource::class.java))

    val sources: Flow<List<SearchSource>> = context.searchSourcesDataStore.data
        .map { prefs -> prefs[key]?.let(TokenCipher::decrypt)?.let { runCatching { adapter.fromJson(it) }.getOrNull() }.orEmpty() }
        .distinctUntilChanged()

    suspend fun all(): List<SearchSource> = sources.first()

    /** Adds [source], or replaces the one with its id. */
    suspend fun save(source: SearchSource) = write { list -> list.filterNot { it.id == source.id } + source }

    suspend fun remove(id: String) = write { list -> list.filterNot { it.id == id } }

    private suspend fun write(change: (List<SearchSource>) -> List<SearchSource>) {
        val next = change(all())
        context.searchSourcesDataStore.edit { it[key] = TokenCipher.encrypt(adapter.toJson(next)) }
    }
}
