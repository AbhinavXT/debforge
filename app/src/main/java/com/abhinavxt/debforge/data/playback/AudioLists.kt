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

data class AudioListsState(
    val favourites: Set<String> = emptySet(),
    val playlists: List<Playlist> = emptyList(),
    /** YouTube item metadata by video id; only ids some playlist references are kept. */
    val ytMeta: Map<String, YouTubeMeta> = emptyMap()
) {
    /** Playlists as typed entries; malformed tokens are dropped. */
    fun typedPlaylists(): List<TypedPlaylist> =
        playlists.map { p -> TypedPlaylist(p.id, p.name, p.trackIds.mapNotNull { PlaylistEntry.parse(it) }) }
}

const val MAX_YOUTUBE_ITEMS = 2000

/** Favourites stay download-only: YouTube tokens are ignored. */
internal fun AudioListsState.toggleFavourite(id: String) =
    if (PlaylistEntry.isYouTubeToken(id)) this
    else copy(favourites = if (id in favourites) favourites - id else favourites + id)

private fun AudioListsState.youTubeCount() = playlists.sumOf { p -> p.trackIds.count { PlaylistEntry.isYouTubeToken(it) } }

/** Drops metadata for video ids no playlist references. */
internal fun AudioListsState.pruneMeta(): AudioListsState {
    if (ytMeta.isEmpty()) return this
    val used = playlists.flatMapTo(HashSet()) { p -> p.trackIds.mapNotNull { PlaylistEntry.parseYouTube(it)?.videoId } }
    return if (used.containsAll(ytMeta.keys)) this else copy(ytMeta = ytMeta.filterKeys { it in used })
}

internal fun AudioListsState.addYouTube(
    listId: String, videoId: String, mode: YouTubeMode, meta: YouTubeMeta
): Pair<AudioListsState, YouTubeAddResult> {
    if (!PlaylistEntry.isValidVideoId(videoId)) return this to YouTubeAddResult.INVALID_ID
    val list = playlists.firstOrNull { it.id == listId } ?: return this to YouTubeAddResult.NO_SUCH_LIST
    val token = PlaylistEntry.format(PlaylistEntry.YouTube(videoId, mode))
    if (token in list.trackIds) return this to YouTubeAddResult.ALREADY_IN_LIST
    // The video is already here in the other mode: switch it in place (same position) rather than hold two entries.
    val other = PlaylistEntry.format(PlaylistEntry.YouTube(videoId, if (mode == YouTubeMode.AUDIO) YouTubeMode.VIDEO else YouTubeMode.AUDIO))
    if (other in list.trackIds) {
        val switched = mapList(listId) { l -> l.copy(trackIds = l.trackIds.map { if (it == other) token else it }) }
        return switched.copy(ytMeta = switched.ytMeta + (videoId to meta)) to YouTubeAddResult.MODE_CHANGED
    }
    if (youTubeCount() >= MAX_YOUTUBE_ITEMS) return this to YouTubeAddResult.LIMIT_REACHED
    val next = mapList(listId) { it.copy(trackIds = it.trackIds + token) }
    return next.copy(ytMeta = next.ytMeta + (videoId to meta)) to YouTubeAddResult.ADDED
}

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
).pruneMeta()

internal fun AudioListsState.delete(listId: String) = copy(playlists = playlists.filter { it.id != listId }).pruneMeta()

internal fun AudioListsState.add(listId: String, ids: Collection<String>) =
    mapList(listId) { it.copy(trackIds = (it.trackIds + ids).distinct()) }

internal fun AudioListsState.remove(listId: String, id: String) =
    mapList(listId) { it.copy(trackIds = it.trackIds - id) }.pruneMeta()

internal fun AudioListsState.removeEntry(listId: String, entry: PlaylistEntry) = remove(listId, PlaylistEntry.format(entry))

private fun AudioListsState.mapList(listId: String, f: (Playlist) -> Playlist) =
    copy(playlists = playlists.map { if (it.id == listId) f(it) else it })

internal fun encodeFavourites(s: AudioListsState): String = JSONArray(s.favourites.toList()).toString()

internal fun encodePlaylists(s: AudioListsState): String = JSONArray().also { a ->
    s.playlists.forEach { a.put(JSONObject().put("i", it.id).put("n", it.name).put("t", JSONArray(it.trackIds))) }
}.toString()

internal fun encodeYouTubeMeta(s: AudioListsState): String =
    JSONObject().also { o -> s.ytMeta.forEach { (k, v) -> o.put(k, v.toJson()) } }.toString()

private fun decodeYouTubeMeta(json: String?): Map<String, YouTubeMeta> = runCatching {
    val o = JSONObject(json)
    o.keys().asSequence().mapNotNull { k ->
        runCatching {
            val m = o.getJSONObject(k)
            val t = m.getString("t").takeIf { it.isNotBlank() } ?: return@runCatching null
            k to YouTubeMeta(t, m.optString("c").takeIf { m.has("c") && it.isNotEmpty() },
                m.optInt("d", 0).takeIf { it > 0 })
        }.getOrNull()
    }.toMap()
}.getOrDefault(emptyMap())

/** Corrupt or missing JSON gives an empty state, never throws; one bad playlist or id is skipped, the rest kept. */
internal fun decodeAudioLists(fav: String?, lists: String?, ym: String? = null): AudioListsState = AudioListsState(
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
    }.getOrDefault(emptyList()),
    ytMeta = decodeYouTubeMeta(ym)
)

/** Favourites and playlists of downloaded audio, by download id. Ids whose file is gone are filtered at read time. */
@Singleton
class AudioLists @Inject constructor(@ApplicationContext private val context: Context) {

    private val favKey = stringPreferencesKey("fav")
    private val listsKey = stringPreferencesKey("lists")
    private val ymKey = stringPreferencesKey("ym")

    private fun androidx.datastore.preferences.core.Preferences.decode() = decodeAudioLists(this[favKey], this[listsKey], this[ymKey])
    private fun androidx.datastore.preferences.core.MutablePreferences.write(s: AudioListsState) {
        this[favKey] = encodeFavourites(s)
        this[listsKey] = encodePlaylists(s)
        this[ymKey] = encodeYouTubeMeta(s)
    }

    val state: Flow<AudioListsState> = context.audioListsStore.data.map { it.decode() }

    suspend fun snapshot(): AudioListsState {
        return context.audioListsStore.data.first().decode()
    }

    /** Backup restore: union with [incoming], nothing removed. Returns what was added. */
    suspend fun mergeFromBackup(incoming: AudioListsState): AudioListsBackup.Merged {
        var result = AudioListsBackup.Merged(AudioListsState(), 0, 0)
        context.audioListsStore.edit { prefs ->
            result = AudioListsBackup.merge(prefs.decode(), incoming)
            prefs.write(result.state)
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

    /** Adds a YouTube item with its metadata; a duplicate token is not added twice, and at [MAX_YOUTUBE_ITEMS] adds are refused. */
    suspend fun addYouTube(listId: String, videoId: String, mode: YouTubeMode, meta: YouTubeMeta): YouTubeAddResult {
        var result = YouTubeAddResult.NO_SUCH_LIST
        runCatching {
            context.audioListsStore.edit { prefs ->
                val (next, r) = prefs.decode().addYouTube(listId, videoId, mode, meta)
                result = r
                if (r == YouTubeAddResult.ADDED || r == YouTubeAddResult.MODE_CHANGED) prefs.write(next)
            }
        }
        return result
    }

    suspend fun removeEntry(listId: String, entry: PlaylistEntry) = update { it.removeEntry(listId, entry) }

    /** Playlists as typed entries, with the YouTube metadata by video id. */
    suspend fun typedSnapshot(): Pair<List<TypedPlaylist>, Map<String, YouTubeMeta>> =
        snapshot().let { it.typedPlaylists() to it.ytMeta }

    private suspend fun update(change: (AudioListsState) -> AudioListsState) {
        runCatching {
            context.audioListsStore.edit { prefs ->
                prefs.write(change(prefs.decode()))
            }
        }
    }
}
