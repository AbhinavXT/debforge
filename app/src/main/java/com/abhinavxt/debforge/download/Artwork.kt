package com.abhinavxt.debforge.download

import android.content.Context
import java.io.File
import java.security.MessageDigest

/** Where a download's thumbnail lives; the path is derived from the id, so no column is needed. */
fun artworkFile(context: Context, id: String): File {
    val sha1 = MessageDigest.getInstance("SHA-1").digest(id.toByteArray()).joinToString("") { "%02x".format(it) }
    return File(context.filesDir, "artwork/$sha1.jpg")
}

/**
 * Disk checks for artwork, done off the main thread. The UI reads [cachedFile] / [cachedKey] synchronously
 * (memory only) for its first frame and fills them with [existing] / [key] from a background dispatcher.
 */
object ArtworkProbe {
    /** An existing artwork file and its image-cache key (set explicitly so Coil never stats the file on Main). */
    class Art(val file: File, val key: String)

    private val files = java.util.concurrent.ConcurrentHashMap<String, Art>()
    private val keys = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun cachedFile(id: String): Art? = files[id]

    /** The artwork file for [id] if it exists; touches the disk, so it runs on IO. */
    suspend fun existing(context: Context, id: String): Art? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        artworkFile(context, id).takeIf { it.exists() }?.let { Art(it, "art:${it.path}:${it.lastModified()}") }?.also { files[id] = it }
    }

    fun cachedKey(file: File): String? = keys[file.path]

    /** Image-cache key for [file] (changes when the file does), or null if it is missing; runs on IO. */
    suspend fun key(file: File): String? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        file.takeIf { it.exists() }?.let { "art:${it.path}:${it.lastModified()}" }?.also { keys[file.path] = it }
    }
}
