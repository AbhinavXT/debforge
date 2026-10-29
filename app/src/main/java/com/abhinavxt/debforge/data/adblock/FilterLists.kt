package com.abhinavxt.debforge.data.adblock

import android.content.Context
import com.abhinavxt.debforge.BuildConfig
import com.abhinavxt.debforge.di.ApiHttpClient
import com.abhinavxt.debforge.domain.adblock.AdFilters
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The filter lists uBlock Origin turns on by default, downloaded into the
 * app's storage the first time the Browser tab opens and refreshed every few
 * days after, then parsed into [filters]. Nothing is bundled: the APK stays
 * the same size and the lists stay current.
 */
@Singleton
class FilterLists @Inject constructor(
    @ApplicationContext context: Context,
    @ApiHttpClient private val client: OkHttpClient
) {
    data class Status(
        /** When the oldest list on the phone was fetched; 0 = none yet. */
        val updatedAt: Long = 0,
        val filterCount: Int = 0,
        val updating: Boolean = false,
        /** The last update failed (offline, a server down); the lists on the phone stay in use. */
        val failed: Boolean = false
    )

    private class FilterList(val id: String, val url: String)

    private val dir = File(context.filesDir, "adblock")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val updating = AtomicBoolean(false)

    private val _filters = MutableStateFlow<AdFilters?>(null)
    /** Null until the lists are on the phone and parsed. */
    val filters: StateFlow<AdFilters?> = _filters.asStateFlow()

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    /** Loads the lists on the phone, then fetches them if they're missing or old. Returns at once. */
    fun prepare() {
        scope.launch {
            mutex.withLock { if (_filters.value == null) load() }
            if (System.currentTimeMillis() - oldest() > MAX_AGE_MS) update()
        }
    }

    /** Fetches every list again ("Update now"). Returns at once. */
    fun refresh() {
        scope.launch { update() }
    }

    private suspend fun update() {
        if (!updating.compareAndSet(false, true)) return
        try {
            _status.update { it.copy(updating = true, failed = false) }
            val fetched = LISTS.map { list -> scope.async { runCatching { fetch(list) }.isSuccess } }.awaitAll()
            mutex.withLock { if (fetched.any { it }) load() }
            _status.update { it.copy(failed = fetched.any { ok -> !ok }, updatedAt = oldest()) }
        } finally {
            _status.update { it.copy(updating = false) }
            updating.set(false)
        }
    }

    private fun fetch(list: FilterList) {
        val req = Request.Builder().url(list.url).header("User-Agent", "DebForge/${BuildConfig.VERSION_NAME}").build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val body = resp.body?.string() ?: throw IOException("empty response")
            // A captive portal or error page is HTML, not a filter list.
            if (body.length < MIN_LIST_BYTES || body.trimStart().startsWith("<")) throw IOException("not a filter list")
            dir.mkdirs()
            val tmp = File(dir, "${list.id}.tmp")
            tmp.writeText(body)
            if (!tmp.renameTo(File(dir, "${list.id}.txt"))) throw IOException("can't save ${list.id}")
        }
    }

    private fun load() {
        val files = LISTS.map { File(dir, "${it.id}.txt") }.filter { it.isFile }
        if (files.isEmpty()) return
        val parsed = AdFilters.parse(files.asSequence().map { it.readLines().asSequence() })
        _filters.value = parsed
        _status.update { it.copy(filterCount = parsed.size, updatedAt = oldest()) }
    }

    /** 0 when any list is missing. */
    private fun oldest(): Long = LISTS.minOf { File(dir, "${it.id}.txt").lastModified() }

    private companion object {
        /** uBO's own default: lists say "Expires: 4 days" or 5. */
        const val MAX_AGE_MS = 4L * 24 * 60 * 60 * 1000
        const val MIN_LIST_BYTES = 1000

        val LISTS = listOf(
            FilterList("ublock-filters", "https://ublockorigin.github.io/uAssets/filters/filters.min.txt"),
            FilterList("ublock-badware", "https://ublockorigin.github.io/uAssets/filters/badware.min.txt"),
            FilterList("ublock-privacy", "https://ublockorigin.github.io/uAssets/filters/privacy.min.txt"),
            FilterList("ublock-quick-fixes", "https://ublockorigin.github.io/uAssets/filters/quick-fixes.min.txt"),
            FilterList("ublock-unbreak", "https://ublockorigin.github.io/uAssets/filters/unbreak.min.txt"),
            FilterList("easylist", "https://easylist.to/easylist/easylist.txt"),
            FilterList("easyprivacy", "https://easylist.to/easylist/easyprivacy.txt"),
            FilterList("peter-lowe", "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=adblockplus&showintro=0&mimetype=plaintext")
        )
    }
}
