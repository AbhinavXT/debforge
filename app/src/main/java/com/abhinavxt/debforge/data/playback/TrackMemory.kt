package com.abhinavxt.debforge.data.playback

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.abhinavxt.debforge.player.tracks.TrackPicker.Remembered
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

private val Context.trackMemoryStore by preferencesDataStore(name = "debforge_track_memory")

/**
 * The audio track, subtitles and speed last chosen, per show (so every
 * episode follows) or per file for movies. One small JSON object in its own
 * DataStore, capped at [MAX_ENTRIES] (oldest dropped).
 */
@Singleton
class TrackMemory @Inject constructor(@ApplicationContext private val context: Context) {

    private val key = stringPreferencesKey("entries")

    suspend fun get(id: String): Remembered? {
        val all = parse(context.trackMemoryStore.data.first()[key])
        return all.optJSONObject(id)?.let(::decode)
    }

    suspend fun update(id: String, change: (Remembered) -> Remembered) {
        context.trackMemoryStore.edit { prefs ->
            val all = parse(prefs[key])
            val current = all.optJSONObject(id)?.let(::decode) ?: Remembered()
            all.put(id, encode(change(current)))
            prune(all)
            prefs[key] = all.toString()
        }
    }

    private fun parse(raw: String?): JSONObject =
        raw?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()

    private fun prune(all: JSONObject) {
        if (all.length() <= MAX_ENTRIES) return
        val byAge = all.keys().asSequence().toList().sortedBy { all.optJSONObject(it)?.optLong("t") ?: 0L }
        byAge.take(all.length() - MAX_ENTRIES).forEach { all.remove(it) }
    }

    private fun encode(r: Remembered) = JSONObject().apply {
        r.audioLanguage?.let { put("al", it) }
        r.audioLabel?.let { put("ab", it) }
        if (r.textOff) put("to", true)
        r.textLanguage?.let { put("tl", it) }
        r.textLabel?.let { put("tb", it) }
        r.speed?.let { put("sp", it.toDouble()) }
        put("t", System.currentTimeMillis())
    }

    private fun decode(o: JSONObject) = Remembered(
        audioLanguage = o.optString("al").ifEmpty { null },
        audioLabel = o.optString("ab").ifEmpty { null },
        textOff = o.optBoolean("to", false),
        textLanguage = o.optString("tl").ifEmpty { null },
        textLabel = o.optString("tb").ifEmpty { null },
        speed = if (o.has("sp")) o.optDouble("sp").toFloat().takeIf { it in 0.1f..8f } else null
    )

    private companion object {
        const val MAX_ENTRIES = 300
    }
}
