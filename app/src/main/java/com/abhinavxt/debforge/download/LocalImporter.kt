package com.abhinavxt.debforge.download

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.util.Log
import com.abhinavxt.debforge.data.local.DownloadDao
import com.abhinavxt.debforge.data.prefs.SettingsStore
import com.abhinavxt.debforge.domain.LocalRow
import com.abhinavxt.debforge.domain.MAX_DEPTH
import com.abhinavxt.debforge.domain.MAX_FILES
import com.abhinavxt.debforge.domain.ScannedFile
import com.abhinavxt.debforge.domain.classifyScanned
import com.abhinavxt.debforge.domain.diffScan
import com.abhinavxt.debforge.domain.toLocalEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lists audio and video in the download folder that DebForge did not download
 * and links them in place as ProviderId.LOCAL rows (never copied). Rows whose
 * file is gone are dropped; files are never deleted here.
 *
 * Paths use the same format as downloads' finalFilePath: an absolute file path
 * for an ordinary folder, "saf:<tree uri>#relative/path" for a picked folder
 * (see [SafPaths]), so claimed-path matching and playback work unchanged.
 */
@Singleton
class LocalImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsStore,
    private val dao: DownloadDao,
    private val metadata: LocalMetadata
) {
    private val lock = Mutex()
    @Volatile private var lastScanAt = 0L

    /** [scan], unless one finished less than [minIntervalMs] ago. */
    suspend fun scanIfStale(minIntervalMs: Long = 30_000L) {
        val last = lastScanAt
        if (last != 0L && android.os.SystemClock.elapsedRealtime() - last < minIntervalMs) return
        scan()
    }

    /** Scans the current download folder and updates the LOCAL rows. Never throws except on cancellation. */
    suspend fun scan() = lock.withLock {
        try {
            withContext(Dispatchers.IO) { scanLocked() }
            lastScanAt = android.os.SystemClock.elapsedRealtime()
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Throwable) {
            Log.w(TAG, "Scan of the download folder failed", e)
        }
    }

    private suspend fun scanLocked() {
        if (!settings.importLocalFilesFlow.first()) {
            dao.deleteLocal()
            return
        }
        val dir = settings.downloadDirFlow.first()
        if (dir.isBlank()) {
            dao.deleteLocal()
            return
        }
        val saf = SafPaths.isTreeUri(dir)
        if (saf && !SafStore.hasAccess(context, dir)) return // access revoked: keep what we have
        val root = if (saf) "${SafPaths.PREFIX}$dir#" else File(dir).absolutePath
        val found = if (saf) walkSaf(dir) else walkFiles(File(dir)) ?: return

        val claimed = HashSet<String>().apply {
            addAll(dao.claimedFinalPaths())
            addAll(dao.claimedPartPaths())
        }
        val rows = dao.localRows()
        val diff = diffScan(
            scanned = found,
            localRows = rows.map { r ->
                val path = r.finalFilePath ?: r.sourceRef
                LocalRow(r.id, path, r.filesize, r.filename.substringAfterLast('.', "").lowercase(Locale.ROOT))
            },
            claimedPaths = claimed,
            root = root
        )
        if (diff.removes.isNotEmpty()) dao.deleteByIds(diff.removes)
        for ((id, file) in diff.moves) dao.moveLocal(id, file.path, file.name, file.lastModified)
        if (diff.inserts.isNotEmpty()) dao.insertAll(diff.inserts.map { it.toLocalEntity() })
        metadata.kick() // reads tags in the background
    }

    /** Null when the folder can't be read, so nothing is removed on a transient failure. */
    private suspend fun walkFiles(dir: File): List<ScannedFile>? {
        if (!dir.isDirectory) return null
        val out = ArrayList<ScannedFile>()
        var visited = 0
        val walk = dir.walk().maxDepth(MAX_DEPTH).onEnter { !it.name.startsWith(".") || it == dir }
        for (f in walk) {
            kotlin.coroutines.coroutineContext.ensureActive()
            if (!f.isFile) continue
            if (++visited > MAX_FILES * VISIT_FACTOR) break
            val size = f.length()
            if (classifyScanned(f.name, size) == null) continue
            out += ScannedFile(f.absolutePath, f.name, size, f.lastModified())
            if (out.size >= MAX_FILES) break
        }
        return out
    }

    private suspend fun walkSaf(tree: String): List<ScannedFile> {
        val treeUri = Uri.parse(tree)
        val out = ArrayList<ScannedFile>()
        var visited = 0

        suspend fun visit(docId: String, relative: String, depth: Int) {
            kotlin.coroutines.coroutineContext.ensureActive()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
            val dirs = ArrayList<Pair<String, String>>()
            context.contentResolver.query(
                children,
                arrayOf(
                    Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
                    Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED
                ),
                null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    kotlin.coroutines.coroutineContext.ensureActive()
                    val name = c.getString(1) ?: continue
                    val path = if (relative.isEmpty()) name else "$relative/$name"
                    if (c.getString(2) == Document.MIME_TYPE_DIR) {
                        if (depth + 1 < MAX_DEPTH && !name.startsWith(".")) dirs += c.getString(0) to path
                        continue
                    }
                    if (++visited > MAX_FILES * VISIT_FACTOR || out.size >= MAX_FILES) return@use
                    val size = if (c.isNull(3)) 0L else c.getLong(3)
                    if (classifyScanned(name, size) == null) continue
                    out += ScannedFile(
                        SafPaths.join(tree, path), name, size,
                        if (c.isNull(4)) 0L else c.getLong(4)
                    )
                }
            }
            for ((id, path) in dirs) {
                if (out.size >= MAX_FILES || visited > MAX_FILES * VISIT_FACTOR) return
                visit(id, path, depth + 1)
            }
        }

        visit(DocumentsContract.getTreeDocumentId(treeUri), "", 0)
        return out
    }

    private companion object {
        const val TAG = "LocalImporter"
        /** Bounds the walk when a folder holds mostly other kinds of files. */
        const val VISIT_FACTOR = 4
    }
}
