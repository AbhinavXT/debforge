package com.abhinavxt.debforge.player

import android.content.Context
import android.net.Uri
import com.abhinavxt.debforge.di.DownloadHttpClient
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads pieces of what the player is playing without downloading it: HTTP
 * range requests for a stream, positioned reads for a downloaded file.
 * Blocking; call from a background thread.
 */
@Singleton
class RangeReader @Inject constructor(
    @ApplicationContext private val context: Context,
    @DownloadHttpClient private val http: OkHttpClient
) {
    /** Up to [length] bytes from [offset] (fewer at the end), plus the file's total size if known. */
    data class Piece(val bytes: ByteArray, val totalSize: Long?)

    fun read(uri: Uri, offset: Long, length: Int): Piece? = runCatching {
        when (uri.scheme) {
            "http", "https" -> remote(uri.toString(), offset, length)
            "content", "file" -> local(uri, offset, length)
            else -> null
        }
    }.getOrNull()

    private fun remote(url: String, offset: Long, length: Int): Piece? {
        val req = Request.Builder().url(url).header("Range", "bytes=$offset-${offset + length - 1}").build()
        http.newCall(req).execute().use { resp ->
            // 200 = the server ignored the range: don't read a whole movie to get 64 KiB.
            if (resp.code != 206) return null
            val total = resp.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
            val body = resp.body ?: return null
            return Piece(body.byteStream().readUpTo(length), total)
        }
    }

    private fun local(uri: Uri, offset: Long, length: Int): Piece? {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            val size = pfd.statSize
            java.io.FileInputStream(pfd.fileDescriptor).channel.use { ch ->
                val want = if (size >= 0) minOf(length.toLong(), (size - offset).coerceAtLeast(0)).toInt() else length
                val buf = java.nio.ByteBuffer.allocate(want)
                while (buf.hasRemaining() && ch.read(buf, offset + buf.position()) > 0) Unit
                return Piece(buf.array().copyOf(buf.position()), size.takeIf { it >= 0 })
            }
        }
        return null
    }

    private fun java.io.InputStream.readUpTo(n: Int): ByteArray {
        val out = ByteArray(n)
        var read = 0
        while (read < n) {
            val r = read(out, read, n - read)
            if (r < 0) break
            read += r
        }
        return if (read == n) out else out.copyOf(read)
    }
}
