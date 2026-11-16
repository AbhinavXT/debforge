package com.abhinavxt.debforge.data.playback

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

private val Context.audioResumeStore by preferencesDataStore(name = "debforge_audio_resume")

/**
 * Where long audio tracks (talks, audiobooks) were left, per file id (the
 * same id the Media library uses). One small JSON object in its own
 * DataStore, capped at [MAX_ENTRIES] (oldest dropped). Video positions live
 * in the Room table instead, which also feeds "Continue watching".
 */
@Singleton
class AudioResume @Inject constructor(@ApplicationContext private val context: Context) {

    data class Saved(val positionMs: Long, val durationMs: Long)

    private val key = stringPreferencesKey("entries")

    suspend fun get(id: String): Saved? =
        parse(context.audioResumeStore.data.first()[key]).optJSONObject(id)
            ?.let { Saved(it.optLong("p"), it.optLong("d")) }

    suspend fun save(id: String, positionMs: Long, durationMs: Long) = update { all ->
        all.put(id, JSONObject().put("p", positionMs).put("d", durationMs).put("t", System.currentTimeMillis()))
        if (all.length() > MAX_ENTRIES) {
            val byAge = all.keys().asSequence().sortedBy { all.optJSONObject(it)?.optLong("t") ?: 0L }.toList()
            byAge.take(all.length() - MAX_ENTRIES).forEach { all.remove(it) }
        }
    }

    suspend fun forget(id: String) = update { it.remove(id) }

    suspend fun forgetAll(ids: Collection<String>) = update { all -> ids.forEach { all.remove(it) } }

    private suspend fun update(change: (JSONObject) -> Unit) {
        runCatching {
            context.audioResumeStore.edit { prefs ->
                val all = parse(prefs[key])
                change(all)
                prefs[key] = all.toString()
            }
        }
    }

    private fun parse(raw: String?): JSONObject =
        raw?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()

    private companion object {
        const val MAX_ENTRIES = 200
    }
}
