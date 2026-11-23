package com.abhinavxt.debforge.data.lyrics

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

sealed interface LyricsResult {
    data class Synced(val lines: List<LrcLine>) : LyricsResult
    data class Plain(val text: String) : LyricsResult
    data object Instrumental : LyricsResult
    data object NotFound : LyricsResult
    /** The network failed; not cached, so a retry asks again. */
    data object Failed : LyricsResult
}

/**
 * Lyrics from LRCLIB, cached as small files in cacheDir/lyrics (found or not) so a reopened track is instant
 * and works offline. Uses /api/search: /api/get needs the album name, which tracks here do not carry.
 */
@Singleton
class LyricsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: LrcLibApi
) {
    private val dir get() = File(context.cacheDir, "lyrics")

    suspend fun load(title: String, artist: String?, durationMs: Long): LyricsResult = withContext(Dispatchers.IO) {
        val track = cleanTitle(title)
        val by = cleanArtist(artist)
        val sec = durationMs / 1000
        val file = File(dir, sha1("$track|${by.orEmpty()}|$sec"))
        // Without a known length the pick is a guess: neither read nor write the cache.
        if (sec > 0) readCache(file)?.let { return@withContext it }
        try {
            var items = api.search(track = track, artist = by)
            // "Artist - Song" titles from a channel that is not the artist: search the whole title instead.
            if (items.none { it.hasLyrics } && " - " in track) items = api.search(q = track)
            val item = pickLyrics(items, sec)
            val synced = item?.syncedLyrics?.takeIf { it.isNotBlank() }?.let { parseLrc(it) }.orEmpty()
            val result = when {
                item == null -> LyricsResult.NotFound
                synced.isNotEmpty() -> LyricsResult.Synced(synced)
                !item.plainLyrics.isNullOrBlank() -> LyricsResult.Plain(item.plainLyrics)
                item.instrumental == true -> LyricsResult.Instrumental
                else -> LyricsResult.NotFound
            }
            if (sec > 0) writeCache(file, result, item)
            result
        } catch (e: IOException) {
            LyricsResult.Failed
        } catch (e: retrofit2.HttpException) {
            if (e.code() == 404) LyricsResult.NotFound else LyricsResult.Failed
        } catch (e: com.squareup.moshi.JsonDataException) {
            LyricsResult.Failed
        }
    }

    // File: first line is the kind (S, P, I, N), the rest the raw LRC or plain text.
    private fun writeCache(file: File, r: LyricsResult, item: LrcLibItem?) {
        val body = when (r) {
            is LyricsResult.Synced -> "S\n" + item?.syncedLyrics.orEmpty()
            is LyricsResult.Plain -> "P\n" + r.text
            LyricsResult.Instrumental -> "I\n"
            LyricsResult.NotFound -> "N\n"
            LyricsResult.Failed -> return
        }
        runCatching { dir.mkdirs(); file.writeText(body) }
    }

    private fun readCache(file: File): LyricsResult? {
        return runCatching { decodeCache(file.readText()) }.getOrNull()
    }

    private fun decodeCache(text: String): LyricsResult? {
        val body = text.substringAfter('\n', "")
        return when (text.firstOrNull()) {
            'S' -> parseLrc(body).takeIf { it.isNotEmpty() }?.let { LyricsResult.Synced(it) }
            'P' -> LyricsResult.Plain(body)
            'I' -> LyricsResult.Instrumental
            'N' -> LyricsResult.NotFound
            else -> null
        }
    }

    private fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
