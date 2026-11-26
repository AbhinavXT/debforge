package com.abhinavxt.debforge.ui.youtube

import android.content.Context
import com.abhinavxt.debforge.download.artworkFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/** The thumbnail YouTube serves for any video id; used when only the id is known. */
fun youTubeThumbnailUrl(videoId: String) = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"

/** Saves [url] as the artwork of download [id]; best effort, a failure just leaves no artwork. */
suspend fun saveYouTubeThumbnail(context: Context, http: OkHttpClient, url: String?, id: String) {
    if (url == null) return
    withContext(Dispatchers.IO) {
        try {
            http.newCall(Request.Builder().url(url).build())
                // A slow image host must not hold up callers (podcast queueing waits on this).
                .apply { timeout().timeout(10, TimeUnit.SECONDS) }.execute().use { r ->
                if (!r.isSuccessful) return@use
                val file = artworkFile(context, id).also { it.parentFile?.mkdirs() }
                val tmp = File(file.path + ".tmp")
                try {
                    r.body?.byteStream()?.use { input -> tmp.outputStream().use { input.copyTo(it) } } ?: return@use
                    if (!tmp.renameTo(file)) tmp.delete()
                } finally {
                    tmp.delete() // no-op after a successful rename
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }
}
