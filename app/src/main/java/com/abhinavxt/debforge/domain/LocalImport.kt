package com.abhinavxt.debforge.domain

import com.abhinavxt.debforge.data.local.DownloadEntity
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
    val lastModified: Long = 0L,
)

data class ScanDiff(
    val inserts: List<ScannedFile>,
    val removes: List<String>,
    val moves: Map<String, ScannedFile>,
    /** Existing rows (id) whose file changed size or modification time. */
    val updates: Map<String, ScannedFile> = emptyMap(),
    /** Row id for each insert, by path; differs from [localId] when that id is still taken by another row. */
    val insertIds: Map<String, String> = emptyMap(),
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
    val base = if (root.endsWith("/") || root.endsWith("#")) root else "$root/"
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
        // A rename keeps size, extension and modification time.
        val key = Triple(row.size, row.extension.lowercase(Locale.ROOT), row.lastModified)
        val sameRows = vanished.filter { Triple(it.size, it.extension.lowercase(Locale.ROOT), it.lastModified) == key }
        val sameFiles = fresh.filter { Triple(it.size, extensionOf(it.name), it.lastModified) == key }
        if (sameRows.size == 1 && sameFiles.size == 1) {
            moves[row.id] = sameFiles[0]
            movedFiles += sameFiles[0].path
            movedRows += row.id
        }
    }
    vanished.filter { it.id !in movedRows }.forEach { removes += it.id }

    val byPath = wanted.associateBy { it.path }
    val updates = LinkedHashMap<String, ScannedFile>()
    for (row in localRows) {
        val file = byPath[row.path] ?: continue
        if (row.id in removes) continue
        if (file.size != row.size || file.lastModified != row.lastModified) updates[row.id] = file
    }

    val inserts = fresh.filter { it.path !in movedFiles }
    // Ids still held by surviving rows (a moved row keeps its old id) can't be reused.
    val removed = removes.toHashSet()
    val taken = localRows.filter { it.id !in removed }.mapTo(HashSet()) { it.id }
    val insertIds = LinkedHashMap<String, String>()
    for (file in inserts) {
        var id = localId(file.path)
        var n = 1
        while (id in taken) id = localId("${file.path}\u0000${n++}")
        taken += id
        insertIds[file.path] = id
    }
    return ScanDiff(inserts, removes, moves, updates, insertIds)
}

/** The finished-download row for an imported file; [path] is also the row's source reference. */
fun ScannedFile.toLocalEntity(id: String = localId(path)): DownloadEntity =
    DownloadEntity(
        id = id,
        provider = ProviderId.LOCAL,
        filename = name,
        sourceRef = path,
        downloadUrl = "",
        host = "",
        filesize = size,
        chunksAllowed = 1,
        state = DownloadState.COMPLETED,
        bytesDownloaded = size,
        finalFilePath = path,
        title = "",
        createdAt = lastModified,
        updatedAt = lastModified,
    )
