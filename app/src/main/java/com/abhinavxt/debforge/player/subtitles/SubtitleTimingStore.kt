package com.abhinavxt.debforge.player.subtitles

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private val Context.subtitleTimingDataStore by preferencesDataStore(name = "debforge_subtitle_timing")

/** Remembered [SubtitleTiming] per subtitle key; the 500 most recently used are kept. */
@Singleton
class SubtitleTimingStore @Inject constructor(@ApplicationContext private val context: Context) {
    private val entries = stringPreferencesKey("entries")

    /** In-memory copy for the parser, which needs an answer without suspending; filled shortly after start. */
    private val cache = ConcurrentHashMap<String, SubtitleTiming>()

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { all() }.getOrNull()?.forEach { (k, v) -> cache.putIfAbsent(k, v) }
        }
    }

    fun cached(key: String): SubtitleTiming? = cache[key]

    /** Makes [timing] visible to the parser at once; [set] then saves it. */
    fun updateCache(key: String, timing: SubtitleTiming) {
        if (timing.isIdentity) cache.remove(key) else cache[key] = timing
    }

    suspend fun all(): Map<String, SubtitleTiming> = decode(context.subtitleTimingDataStore.data.first()[entries])

    /** Saves [timing] for [key] (identity removes it) and marks it most recently used. */
    suspend fun set(key: String, timing: SubtitleTiming) {
        updateCache(key, timing)
        context.subtitleTimingDataStore.edit { prefs ->
            prefs[entries] = encode(withEntry(decode(prefs[entries]), key, timing))
        }
    }

    companion object {
        const val MAX_ENTRIES = 500

        /** [map] with [key] set and moved to the end (newest); oldest dropped beyond [max]. Pure. */
        fun withEntry(map: Map<String, SubtitleTiming>, key: String, timing: SubtitleTiming, max: Int = MAX_ENTRIES): LinkedHashMap<String, SubtitleTiming> {
            val out = LinkedHashMap(map)
            out.remove(key)
            if (!timing.isIdentity) out[key] = timing
            while (out.size > max) out.remove(out.keys.first())
            return out
        }

        /** One line per entry, oldest first: `a<TAB>b<TAB>url-encoded key`. */
        fun encode(map: Map<String, SubtitleTiming>): String =
            map.entries.joinToString("\n") { (k, t) -> "${t.a}\t${t.bMs}\t${URLEncoder.encode(k, "UTF-8")}" }

        fun decode(text: String?): LinkedHashMap<String, SubtitleTiming> {
            val out = LinkedHashMap<String, SubtitleTiming>()
            text.orEmpty().lineSequence().forEach { line ->
                val p = line.split('\t')
                if (p.size != 3) return@forEach
                val a = p[0].toDoubleOrNull() ?: return@forEach
                val b = p[1].toDoubleOrNull() ?: return@forEach
                out[URLDecoder.decode(p[2], "UTF-8")] = SubtitleTiming(a, b)
            }
            return out
        }
    }
}
