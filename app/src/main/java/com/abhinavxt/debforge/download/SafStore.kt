package com.abhinavxt.debforge.download

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * File operations on [SafPaths] paths through the Storage Access Framework.
 * Blocking; call on Dispatchers.IO.
 *
 * Folders are looked up once and cached (they're walked on every chunk open
 * otherwise); files are always looked up fresh, since they're created,
 * renamed and deleted all the time.
 */
internal object SafStore {

    private val dirCache = ConcurrentHashMap<String, Uri>()

    private fun rootDoc(tree: Uri): Uri =
        DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))

    private fun findChild(context: Context, tree: Uri, dir: Uri, name: String): Uri? {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(dir))
        context.contentResolver.query(
            children,
            arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                if (c.getString(1) == name) return DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
            }
        }
        return null
    }

    /** The folder holding [p], created (with parents) if [create]; null if missing and not creating. */
    private fun folder(context: Context, p: SafPaths.Parsed, create: Boolean): Uri? {
        val tree = Uri.parse(p.treeUri)
        var current = rootDoc(tree)
        var key = p.treeUri + "#"
        for (segment in p.dirs) {
            key += "$segment/"
            val cached = dirCache[key]
            current = if (cached != null) {
                cached
            } else {
                val found = findChild(context, tree, current, segment)
                    ?: if (create) {
                        DocumentsContract.createDocument(context.contentResolver, current, Document.MIME_TYPE_DIR, segment)
                            ?: throw IOException("Could not create folder \"$segment\"")
                    } else {
                        return null
                    }
                dirCache[key] = found
                found
            }
        }
        return current
    }

    private fun parsed(path: String): SafPaths.Parsed =
        SafPaths.parse(path) ?: throw IOException("Not a folder-picker path: $path")

    /** Document URI of [path], or null if it doesn't exist (or access was revoked). */
    fun find(context: Context, path: String): Uri? = try {
        val p = parsed(path)
        folder(context, p, create = false)?.let { findChild(context, Uri.parse(p.treeUri), it, p.name) }
    } catch (e: SecurityException) {
        null
    } catch (e: FileNotFoundException) {
        dirCache.clear() // a cached folder was removed behind our back
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    fun exists(context: Context, path: String): Boolean = find(context, path) != null

    /** Finds or creates [path] (and its folders). */
    fun ensureFile(context: Context, path: String): Uri {
        find(context, path)?.let { return it }
        val p = parsed(path)
        // octet-stream so the provider never appends an extension to "x.mkv.part".
        fun attempt(): Uri? = DocumentsContract.createDocument(
            context.contentResolver, folder(context, p, create = true)!!, "application/octet-stream", p.name
        )
        val created = try {
            attempt()
        } catch (e: SecurityException) {
            throw e
        } catch (e: Exception) {
            // A cached folder was deleted behind our back (providers report that
            // as FileNotFound or IllegalArgument): forget the cache, walk again.
            dirCache.clear()
            try {
                attempt()
            } catch (e2: IllegalArgumentException) {
                throw IOException(e2.message, e2)
            }
        }
        return created ?: throw IOException("Could not create \"${p.name}\" in the chosen folder")
    }

    fun delete(context: Context, path: String) {
        val uri = find(context, path) ?: return
        runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
    }

    /** Renames [path] in place to [newName]; returns the new document URI. */
    fun rename(context: Context, path: String, newName: String): Uri {
        val uri = find(context, path) ?: throw IOException("File vanished before rename: $path")
        return DocumentsContract.renameDocument(context.contentResolver, uri, newName)
            ?: throw IOException("Rename failed: ${SafPaths.parse(path)?.name} -> $newName")
    }

    /** True if the app still holds read+write access to the picked folder [treeUri]. */
    fun hasAccess(context: Context, treeUri: String): Boolean {
        val uri = Uri.parse(treeUri)
        return context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission && it.isWritePermission
        }
    }
}
