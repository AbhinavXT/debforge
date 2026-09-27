package com.abhinavxt.debforge.data.follow

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.abhinavxt.debforge.domain.Follow
import com.abhinavxt.debforge.domain.ProviderId
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

private val Context.followDataStore by preferencesDataStore(name = "debforge_follows")

/**
 * Followed shows, as one small JSON list in their own DataStore (a handful
 * of entries; a Room table would need a migration for no gain). Included in
 * Android's own backup like the settings; nothing secret in it.
 */
@Singleton
class FollowStore @Inject constructor(@ApplicationContext private val context: Context) {

    private val key = stringPreferencesKey("follows")

    val follows: Flow<List<Follow>> = context.followDataStore.data
        .map { prefs -> decode(prefs[key]) }
        .distinctUntilChanged()

    suspend fun all(): List<Follow> = follows.first()

    /** Adds or replaces the follow for the same service and show. */
    suspend fun follow(f: Follow) = update { list ->
        list.filterNot { it.provider == f.provider && it.showKey == f.showKey } + f
    }

    suspend fun unfollow(provider: ProviderId, showKey: String) = update { list ->
        list.filterNot { it.provider == provider && it.showKey == showKey }
    }

    /** Adds follows from a backup (see [FollowBackup.merge]). Returns how many shows were new. */
    suspend fun mergeFromBackup(incoming: List<Follow>): Int {
        var added = 0
        update { list ->
            val (merged, n) = FollowBackup.merge(list, incoming)
            added = n
            merged
        }
        return added
    }

    /** Records episodes as handled so they're never queued again. */
    suspend fun markSeen(provider: ProviderId, showKey: String, episodes: Collection<String>) = update { list ->
        list.map { if (it.provider == provider && it.showKey == showKey) it.copy(seen = it.seen + episodes) else it }
    }

    private suspend fun update(change: (List<Follow>) -> List<Follow>) {
        context.followDataStore.edit { prefs -> prefs[key] = encode(change(decode(prefs[key]))) }
    }

    private fun encode(list: List<Follow>): String = JSONArray().apply {
        list.forEach { f ->
            put(
                JSONObject()
                    .put("provider", f.provider.name)
                    .put("showKey", f.showKey)
                    .put("title", f.title)
                    .put("since", f.since)
                    .put("resolution", f.resolution ?: JSONObject.NULL)
                    .put("seen", JSONArray(f.seen.sorted()))
            )
        }
    }.toString()

    /** Unknown providers or broken entries are dropped, never crash. */
    private fun decode(json: String?): List<Follow> {
        if (json.isNullOrBlank()) return emptyList()
        val arr = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            runCatching {
                val o = arr.getJSONObject(i)
                val seen = o.optJSONArray("seen")
                Follow(
                    provider = ProviderId.fromName(o.getString("provider")) ?: return@runCatching null,
                    showKey = o.getString("showKey"),
                    title = o.getString("title"),
                    since = o.getLong("since"),
                    resolution = if (o.isNull("resolution")) null else o.optString("resolution"),
                    seen = if (seen == null) emptySet() else (0 until seen.length()).mapTo(HashSet()) { seen.getString(it) }
                )
            }.getOrNull()
        }
    }
}
