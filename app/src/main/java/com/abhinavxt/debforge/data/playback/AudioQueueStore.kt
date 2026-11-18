package com.abhinavxt.debforge.data.playback

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.abhinavxt.debforge.player.AudioRepeat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

private val Context.audioQueueStore by preferencesDataStore(
    name = "debforge_audio_queue",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }
)

/** The current spot lives in its own small file: it is rewritten every few seconds, the queue rarely. */
private val Context.audioPositionStore by preferencesDataStore(
    name = "debforge_audio_position",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }
)

/**
 * One queued track as saved. A local download ([local]) needs only its id: the uri and artwork are rebuilt
 * from the download row at restore. A streamed YouTube track keeps its video, itag and last (stale) url.
 */
data class SavedTrack(
    val id: String,
    val title: String,
    val artist: String? = null,
    val local: Boolean = true,
    val videoId: String? = null,
    val itag: Int? = null,
    val streamUrl: String? = null,
    val artworkUrl: String? = null,
    val artistUrl: String? = null
)

data class QueueSnapshot(
    val tracks: List<SavedTrack>,
    val shuffle: Boolean = false,
    val repeat: AudioRepeat = AudioRepeat.OFF,
    val speed: Float = 1f
)

/** Where in the queue playback stopped. */
data class SavedPosition(val curId: String?, val positionMs: Long, val durationMs: Long)

data class Snapshot(val queue: QueueSnapshot, val position: SavedPosition)

const val MAX_SAVED_TRACKS = 2000

/** At most [max] tracks, a window around the track [curId] when the list is longer. */
fun capTracks(tracks: List<SavedTrack>, curId: String?, max: Int = MAX_SAVED_TRACKS): List<SavedTrack> {
    if (tracks.size <= max) return tracks
    val at = tracks.indexOfFirst { it.id == curId }.coerceAtLeast(0)
    val from = (at - max / 2).coerceIn(0, tracks.size - max)
    return tracks.subList(from, from + max)
}

/** JSON for the queue (the `queue` key). Needs a real org.json (Robolectric in unit tests). */
object AudioQueueCodec {
    const val VERSION = 1

    fun encode(q: QueueSnapshot, curId: String? = null): String {
        val arr = JSONArray()
        capTracks(q.tracks, curId).forEach { t ->
            arr.put(JSONObject().apply {
                put("i", t.id)
                put("n", t.title)
                t.artist?.let { put("a", it) }
                put("k", if (t.local) "l" else "y")
                if (!t.local) {
                    t.videoId?.let { put("y", it) }
                    t.itag?.let { put("q", it) }
                    t.streamUrl?.let { put("u", it) }
                    t.artworkUrl?.let { put("w", it) }
                    t.artistUrl?.let { put("c", it) }
                }
            })
        }
        return JSONObject().put("v", VERSION).put("t", arr).put("s", q.shuffle)
            .put("r", q.repeat.name).put("p", q.speed.toDouble()).toString()
    }

    /** Null for garbage, a newer version or a missing track list; unknown fields are ignored. */
    fun decode(raw: String?): QueueSnapshot? {
        if (raw == null) return null
        return runCatching {
            val o = JSONObject(raw)
            if (o.optInt("v", 0) !in 1..VERSION) return null
            val arr = o.optJSONArray("t") ?: return null
            val tracks = (0 until arr.length()).mapNotNull { n ->
                val t = arr.optJSONObject(n) ?: return@mapNotNull null
                val id = t.optString("i").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val local = t.optString("k", "l") != "y"
                SavedTrack(
                    id = id,
                    title = t.optString("n"),
                    artist = t.optString("a").takeIf { it.isNotEmpty() },
                    local = local,
                    videoId = t.optString("y").takeIf { it.isNotEmpty() },
                    itag = if (t.has("q")) t.optInt("q") else null,
                    streamUrl = t.optString("u").takeIf { it.isNotEmpty() },
                    artworkUrl = t.optString("w").takeIf { it.isNotEmpty() },
                    artistUrl = t.optString("c").takeIf { it.isNotEmpty() }
                ).takeIf { local || (it.videoId != null && it.itag != null) }
            }
            QueueSnapshot(
                tracks = tracks,
                shuffle = o.optBoolean("s", false),
                repeat = runCatching { AudioRepeat.valueOf(o.optString("r", "OFF")) }.getOrDefault(AudioRepeat.OFF),
                speed = o.optDouble("p", 1.0).toFloat().takeIf { it > 0f } ?: 1f
            )
        }.getOrNull()
    }
}

/** What to rebuild: the surviving [tracks], the current one's [index] and where in it to start. */
data class RestorePlan(val tracks: List<SavedTrack>, val index: Int, val posMs: Long)

/**
 * Drops local tracks whose file is gone ([existsLocal]); YouTube tracks always survive. When the current track
 * went, the next surviving one starts at 0 (the last one when none follows). Null when nothing is left.
 */
fun restorePlan(snapshot: Snapshot, existsLocal: (String) -> Boolean): RestorePlan? {
    val all = snapshot.queue.tracks
    val kept = all.filter { !it.local || existsLocal(it.id) }
    if (kept.isEmpty()) return null
    val cur = snapshot.position.curId
    val same = kept.indexOfFirst { it.id == cur }
    if (same >= 0) return RestorePlan(kept, same, snapshot.position.positionMs.coerceAtLeast(0))
    val was = all.indexOfFirst { it.id == cur }
    val next = if (was < 0) 0 else {
        val keptIds = kept.map { it.id }.toSet()
        val after = all.drop(was + 1).firstOrNull { it.id in keptIds }
        if (after != null) kept.indexOfFirst { it.id == after.id } else kept.lastIndex
    }
    return RestorePlan(kept, next, 0)
}

/** The queue and the current spot, so the mini player can come back after the app was closed. */
@Singleton
class AudioQueueStore @Inject constructor(@ApplicationContext private val context: Context) {

    private val queueKey = stringPreferencesKey("queue")
    private val curKey = stringPreferencesKey("cur")
    private val posKey = longPreferencesKey("pos")
    private val durKey = longPreferencesKey("dur")

    /** Null when nothing is saved; a corrupt or too-new snapshot is forgotten. */
    suspend fun load(): Snapshot? {
        val prefs = runCatching { context.audioQueueStore.data.first() }.getOrNull() ?: return null
        val raw = prefs[queueKey] ?: return null
        val q = AudioQueueCodec.decode(raw)
        if (q == null) { forget(); return null }
        val pos = runCatching { context.audioPositionStore.data.first() }.getOrNull()
        return Snapshot(q, SavedPosition(pos?.get(curKey), pos?.get(posKey) ?: 0L, pos?.get(durKey) ?: 0L))
    }

    suspend fun saveQueue(s: Snapshot) {
        runCatching { context.audioQueueStore.edit { it[queueKey] = AudioQueueCodec.encode(s.queue, s.position.curId) } }
        savePosition(s.position.curId, s.position.positionMs, s.position.durationMs)
    }

    suspend fun savePosition(curId: String?, positionMs: Long, durationMs: Long) {
        runCatching {
            context.audioPositionStore.edit { p ->
                if (curId != null) p[curKey] = curId else p.remove(curKey)
                p[posKey] = positionMs
                p[durKey] = durationMs
            }
        }
    }

    suspend fun forget() {
        runCatching { context.audioQueueStore.edit { it.clear() } }
        runCatching { context.audioPositionStore.edit { it.clear() } }
    }
}
