package com.abhinavxt.debforge.data.playback

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import com.abhinavxt.debforge.data.backup.AudioListsBackup
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private val Context.audioListsStore by preferencesDataStore(name = "debforge_audio_lists")

data class Playlist(val id: String, val name: String, val trackIds: List<String>)

data class AudioListsState(val favourites: Set<String> = emptySet(), val playlists: List<Playlist> = emptyList())

internal fun AudioListsState.toggleFavourite(id: String) =
    copy(favourites = if (id in favourites) favourites - id else favourites + id)

/** Blank names are ignored (state unchanged). */
internal fun AudioListsState.create(listId: String, name: String): AudioListsState {
    val n = name.trim()
    return if (n.isEmpty()) this else copy(playlists = playlists + Playlist(listId, uniqueName(n, null), emptyList()))
}

internal fun AudioListsState.rename(listId: String, name: String): AudioListsState {
    val n = name.trim()
    return if (n.isEmpty()) this else mapList(listId) { it.copy(name = uniqueName(n, listId)) }
}

/** [name] as is, or with " (2)", " (3)"... when another playlist (not [ownId]) has it, ignoring case and outer spaces. */
private fun AudioListsState.uniqueName(name: String, ownId: String?): String {
    val taken = playlists.filter { it.id != ownId }.map { it.name.trim().lowercase() }.toSet()
    if (name.lowercase() !in taken) return name
    var k = 2
    while ("$name ($k)".lowercase() in taken) k++
    return "$name ($k)"
}

/** Drops deleted tracks from the favourites and every playlist. */
internal fun AudioListsState.pruneTracks(ids: Set<String>) = copy(
    favourites = favourites - ids,
    playlists = playlists.map { it.copy(trackIds = it.trackIds.filter { t -> t !in ids }) }
)

internal fun AudioListsState.delete(listId: String) = copy(playlists = playlists.filter { it.id != listId })

internal fun AudioListsState.add(listId: String, ids: Collection<String>) =
    mapList(listId) { it.copy(trackIds = (it.trackIds + ids).distinct()) }

internal fun AudioListsState.remove(listId: String, id: String) =
    mapList(listId) { it.copy(trackIds = it.trackIds - id) }

private fun AudioListsState.mapList(listId: String, f: (Playlist) -> Playlist) =
    copy(playlists = playlists.map { if (it.id == listId) f(it) else it })

internal fun encodeFavourites(s: AudioListsState): String = JSONArray(s.favourites.toList()).toString()

internal fun encodePlaylists(s: AudioListsState): String = JSONArray().also { a ->
    s.playlists.forEach { a.put(JSONObject().put("i", it.id).put("n", it.name).put("t", JSONArray(it.trackIds))) }
}.toString()

/** Corrupt or missing JSON gives an empty state, never throws; one bad playlist or id is skipped, the rest kept. */
internal fun decodeAudioLists(fav: String?, lists: String?): AudioListsState = AudioListsState(
    favourites = runCatching {
        JSONArray(fav).let { a -> (0 until a.length()).mapNotNull { runCatching { a.get(it) as? String }.getOrNull() }.toSet() }
    }.getOrDefault(emptySet()),
    playlists = runCatching {
        JSONArray(lists).let { a ->
            (0 until a.length()).mapNotNull { i ->
                runCatching {
                    val o = a.getJSONObject(i)
                    val t = o.getJSONArray("t")
                    Playlist(o.getString("i"), o.getString("n"), (0 until t.length()).mapNotNull { runCatching { t.getString(it) }.getOrNull() })
                }.getOrNull()
            }
        }
    }.getOrDefault(emptyList())
)

/** Favourites and playlists of downloaded audio, by download id. Ids whose file is gone are filtered at read time. */
@Singleton
class AudioLists @Inject constructor(@ApplicationContext private val context: Context) {

    private val favKey = stringPreferencesKey("fav")
    private val listsKey = stringPreferencesKey("lists")

    val state: Flow<AudioListsState> = context.audioListsStore.data.map { decodeAudioLists(it[favKey], it[listsKey]) }

    suspend fun snapshot(): AudioListsState {
        val prefs = context.audioListsStore.data.first()
        return decodeAudioLists(prefs[favKey], prefs[listsKey])
    }

    /** Backup restore: union with [incoming], nothing removed. Returns what was added. */
    suspend fun mergeFromBackup(incoming: AudioListsState): AudioListsBackup.Merged {
        var result = AudioListsBackup.Merged(AudioListsState(), 0, 0)
        context.audioListsStore.edit { prefs ->
            result = AudioListsBackup.merge(decodeAudioLists(prefs[favKey], prefs[listsKey]), incoming)
            prefs[favKey] = encodeFavourites(result.state)
            prefs[listsKey] = encodePlaylists(result.state)
        }
        return result
    }

    /** Files were deleted: forget their ids everywhere. */
    suspend fun prune(ids: Set<String>) = update { it.pruneTracks(ids) }

    suspend fun toggleFavourite(id: String) = update { it.toggleFavourite(id) }

    /** Returns the new playlist id, or "" when the name is blank. */
    suspend fun create(name: String): String {
        if (name.isBlank()) return ""
        val id = UUID.randomUUID().toString()
        update { it.create(id, name) }
        return id
    }

    suspend fun rename(listId: String, name: String) = update { it.rename(listId, name) }
    suspend fun delete(listId: String) = update { it.delete(listId) }
    suspend fun add(listId: String, ids: Collection<String>) = update { it.add(listId, ids) }
    suspend fun remove(listId: String, id: String) = update { it.remove(listId, id) }

    private suspend fun update(change: (AudioListsState) -> AudioListsState) {
        runCatching {
            context.audioListsStore.edit { prefs ->
                val next = change(decodeAudioLists(prefs[favKey], prefs[listsKey]))
                prefs[favKey] = encodeFavourites(next)
                prefs[listsKey] = encodePlaylists(next)
            }
        }
    }
}
