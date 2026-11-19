package com.abhinavxt.debforge.domain

import java.security.MessageDigest
import java.util.Locale

/** Scan limits for the download-folder import. */
const val MAX_DEPTH = 4
const val MAX_FILES = 5000

/** A file found while scanning the download folder; [path] is a file path or a SAF document URI string. */
data class ScannedFile(
    val path: String,
    val name: String,
    val size: Long,
    val lastModified: Long,
)

/** An existing LOCAL row, reduced to what the diff needs. */
data class LocalRow(
    val id: String,
    val path: String,
    val size: Long,
    val extension: String,
)

data class ScanDiff(
    val inserts: List<ScannedFile>,
    val removes: List<String>,
    val moves: Map<String, ScannedFile>,
)

private val TEMP_SUFFIXES = listOf(".part", ".part.audio", ".part.mux", ".tmp")

/** Audio or Video when the file should be imported, Skip otherwise. */
fun classifyScanned(name: String, size: Long): FileKind? {
    if (size <= 0) return null
    if (name.startsWith(".") || name.startsWith(".trashed-")) return null
    val lower = name.lowercase(Locale.ROOT)
    if (TEMP_SUFFIXES.any { lower.endsWith(it) }) return null
    return when (kindOf(name)) {
        FileKind.File -> null
        else -> kindOf(name)
    }
}

/** Stable id for an imported file: "local:" + lowercase hex sha1 of its path. */
fun localId(path: String): String {
    val digest = MessageDigest.getInstance("SHA-1").digest(path.toByteArray(Charsets.UTF_8))
    return "local:" + digest.joinToString("") { "%02x".format(it) }
}

private fun extensionOf(name: String): String =
    name.substringAfterLast('.', "").lowercase(Locale.ROOT)

/** True when [path] is [root] or lies under it, matched on a path-segment boundary. */
fun isUnderRoot(path: String, root: String): Boolean {
    if (path == root) return true
    val base = if (root.endsWith("/")) root else "$root/"
    return path.startsWith(base)
}

fun diffScan(
    scanned: List<ScannedFile>,
    localRows: List<LocalRow>,
    claimedPaths: Set<String>,
    root: String,
): ScanDiff {
    val wanted = scanned.filter { it.path !in claimedPaths && classifyScanned(it.name, it.size) != null }
    val wantedPaths = wanted.mapTo(HashSet()) { it.path }
    val rowPaths = localRows.mapTo(HashSet()) { it.path }

    val removes = ArrayList<String>()
    val vanished = ArrayList<LocalRow>()
    for (row in localRows) {
        when {
            !isUnderRoot(row.path, root) -> removes += row.id
            row.path in wantedPaths -> Unit
            else -> vanished += row
        }
    }
    val fresh = wanted.filter { it.path !in rowPaths }

    val moves = LinkedHashMap<String, ScannedFile>()
    val movedFiles = HashSet<String>()
    val movedRows = HashSet<String>()
    for (row in vanished) {
        val key = row.size to row.extension.lowercase(Locale.ROOT)
        val sameRows = vanished.filter { it.size to it.extension.lowercase(Locale.ROOT) == key }
        val sameFiles = fresh.filter { it.size to extensionOf(it.name) == key }
        if (sameRows.size == 1 && sameFiles.size == 1) {
            moves[row.id] = sameFiles[0]
            movedFiles += sameFiles[0].path
            movedRows += row.id
        }
    }
    vanished.filter { it.id !in movedRows }.forEach { removes += it.id }
    return ScanDiff(fresh.filter { it.path !in movedFiles }, removes, moves)
}
