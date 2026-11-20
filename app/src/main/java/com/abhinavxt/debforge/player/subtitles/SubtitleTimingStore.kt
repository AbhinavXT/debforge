package com.abhinavxt.debforge.player.subtitles

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private val Context.subtitleTimingDataStore by preferencesDataStore(name = "debforge_subtitle_timing")

/** Remembered [SubtitleTiming] per subtitle key; the 500 most recently changed are kept. */
@Singleton
class SubtitleTimingStore internal constructor(
    private val context: Context,
    /** Outlives any player session: a save started when the video closes still finishes. */
    private val scope: CoroutineScope
) {
    @Inject constructor(@ApplicationContext context: Context) : this(context, CoroutineScope(SupervisorJob() + Dispatchers.IO))

    private val entries = stringPreferencesKey("entries")

    /** In-memory copy for the parser, which needs an answer without suspending. */
    private val cache = ConcurrentHashMap<String, SubtitleTiming>()

    /** Keys changed in this run; the first load must not bring back what they replaced or removed. */
    private val touched: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val loaded = CountDownLatch(1)

    init {
        scope.launch {
            try {
                val stored = runCatching { all() }.getOrNull().orEmpty()
                synchronized(touched) {
                    stored.forEach { (k, v) -> if (k !in touched) cache.putIfAbsent(k, v) }
                }
            } finally {
                loaded.countDown()
            }
        }
    }

    /** The saved timing for [key], waiting (bounded) for the first load. For the loader thread, never Main. */
    fun cached(key: String): SubtitleTiming? {
        if (loaded.count > 0) runCatching { loaded.await(LOAD_WAIT_MS, TimeUnit.MILLISECONDS) }
        return cache[key]
    }

    /** [cached] for [key], else for the key older versions used for the same file. */
    fun lookup(key: String): SubtitleTiming? = cached(key) ?: legacyTimingKey(key)?.let(::cached)

    /** Makes [timing] visible to the parser at once; [set] then saves it. */
    fun updateCache(key: String, timing: SubtitleTiming) {
        synchronized(touched) {
            touched.add(key)
            if (timing.isIdentity) cache.remove(key) else cache[key] = timing
        }
    }

    suspend fun all(): Map<String, SubtitleTiming> = decode(context.subtitleTimingDataStore.data.first()[entries])

    /** Saves [timing] for [key] (identity removes it) and marks it most recently changed. Finishes even if the caller is cancelled. */
    suspend fun set(key: String, timing: SubtitleTiming) {
        updateCache(key, timing)
        withContext(NonCancellable) { persist(key, timing) }
    }

    /** [set] from a scope of the app, for callers that may be gone before the write ends. */
    fun save(key: String, timing: SubtitleTiming) {
        updateCache(key, timing)
        scope.launch { runCatching { persist(key, timing) } }
    }

    private suspend fun persist(key: String, timing: SubtitleTiming) {
        context.subtitleTimingDataStore.edit { prefs ->
            prefs[entries] = encode(withEntry(decode(prefs[entries]), key, timing))
        }
    }

    companion object {
        const val MAX_ENTRIES = 500
        private const val LOAD_WAIT_MS = 3_000L

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

        /** Lines with a rate that is not a positive finite number (it would divide by zero when inverted) or a non-finite offset are dropped. */
        fun decode(text: String?): LinkedHashMap<String, SubtitleTiming> {
            val out = LinkedHashMap<String, SubtitleTiming>()
            text.orEmpty().lineSequence().forEach { line ->
                val p = line.split('\t')
                if (p.size != 3) return@forEach
                val a = p[0].toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 } ?: return@forEach
                val b = p[1].toDoubleOrNull()?.takeIf { it.isFinite() } ?: return@forEach
                out[URLDecoder.decode(p[2], "UTF-8")] = SubtitleTiming(a, b)
            }
            return out
        }
    }
}
