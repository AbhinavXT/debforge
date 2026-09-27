package com.abhinavxt.debforge.download

import java.net.URLDecoder

/**
 * Download paths inside a folder the user picked with the system folder
 * picker (Storage Access Framework), stored in the same `String` columns as
 * ordinary file paths:
 *
 *     saf:<tree uri>#<relative path>
 *     saf:content://com.android.externalstorage.documents/tree/1A2B-3C4D%3AMovies#Shows/The Bear/Season 02/ep.mkv
 *
 * The tree URI is percent-encoded so it never contains '#'; everything after
 * the first '#' is the path below the picked folder ('/'-separated, may
 * itself contain '#'). Pure string logic, no Android types.
 */
object SafPaths {
    const val PREFIX = "saf:"

    /** Settings stores a picked folder as its tree URI. */
    fun isTreeUri(dir: String): Boolean = dir.startsWith("content://")

    fun isSaf(path: String?): Boolean = path != null && path.startsWith(PREFIX)

    fun join(treeUri: String, relativePath: String): String =
        PREFIX + treeUri + "#" + relativePath.trim('/')

    data class Parsed(val treeUri: String, val dirs: List<String>, val name: String) {
        val relativePath: String get() = (dirs + name).joinToString("/")
    }

    fun parse(path: String): Parsed? {
        if (!isSaf(path)) return null
        val body = path.removePrefix(PREFIX)
        val hash = body.indexOf('#')
        if (hash <= 0) return null
        val tree = body.substring(0, hash)
        val segments = body.substring(hash + 1).split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) return null
        return Parsed(tree, segments.dropLast(1), segments.last())
    }

    /** Same folder, different file name (e.g. the .part -> final rename). */
    fun withName(path: String, name: String): String? =
        parse(path)?.let { join(it.treeUri, (it.dirs + name).joinToString("/")) }

    /**
     * The storage volume a picked folder lives on, from its tree URI's
     * document id: "primary" (internal storage) or a volume UUID such as
     * "1A2B-3C4D" (SD card, USB drive). Null for other document providers
     * (cloud storage), whose free space can't be measured.
     */
    fun volumeId(treeUri: String): String? {
        if (!treeUri.startsWith("content://$EXTERNAL_STORAGE_AUTHORITY/")) return null
        val encodedId = treeUri.substringAfter("/tree/", "").substringBefore('/')
        if (encodedId.isEmpty()) return null
        val id = runCatching { URLDecoder.decode(encodedId, "UTF-8") }.getOrDefault(encodedId)
        return id.substringBefore(':').takeIf { it.isNotEmpty() && ':' in id }
    }

    const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

    /**
     * Human label for a picked folder, e.g. "Internal storage/Movies" or
     * "SD card (1A2B-3C4D)/Movies", from the tree URI's document id
     * ("primary:Movies", "1A2B-3C4D:Movies").
     */
    fun displayName(treeUri: String, internalLabel: String = "Internal storage", sdLabel: String = "SD card"): String {
        val encodedId = treeUri.substringAfter("/tree/", "").substringBefore('/')
        if (encodedId.isEmpty()) return treeUri
        val id = runCatching { URLDecoder.decode(encodedId, "UTF-8") }.getOrDefault(encodedId)
        val volume = id.substringBefore(':')
        val sub = id.substringAfter(':', "")
        val root = when {
            volume.equals("primary", ignoreCase = true) -> internalLabel
            volume.isNotEmpty() -> "$sdLabel ($volume)"
            else -> "?"
        }
        return if (sub.isEmpty()) root else "$root/$sub"
    }
}
