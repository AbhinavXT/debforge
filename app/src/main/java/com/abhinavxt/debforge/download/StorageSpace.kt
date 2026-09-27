package com.abhinavxt.debforge.download

import android.content.Context
import android.os.StatFs
import android.os.storage.StorageManager
import java.io.File

/**
 * Free space where downloads go. Works for every kind of download location:
 *
 *  - a plain path (app storage, or shared storage with All files access):
 *    the nearest folder that exists, measured with StatFs;
 *  - a folder picked with the system picker (tree URI, or a "saf:" path
 *    inside one): its storage volume ("primary" = internal storage, else an
 *    SD card or USB drive by UUID), measured at the volume's root.
 *
 * Returns null when it can't tell (e.g. a cloud provider's folder); callers
 * then don't block anything. Never throws.
 */
object StorageSpace {

    fun availableBytes(context: Context, pathOrDir: String): Long? = runCatching {
        val dir: File? = when {
            SafPaths.isTreeUri(pathOrDir) -> volumeRoot(context, pathOrDir)
            SafPaths.isSaf(pathOrDir) -> SafPaths.parse(pathOrDir)?.let { volumeRoot(context, it.treeUri) }
            else -> nearestExisting(File(pathOrDir))
        }
        dir?.let { StatFs(it.path).availableBytes }
    }.getOrNull()

    private fun volumeRoot(context: Context, treeUri: String): File? {
        val id = SafPaths.volumeId(treeUri) ?: return null
        val sm = context.getSystemService(StorageManager::class.java) ?: return null
        return if (id.equals("primary", ignoreCase = true)) {
            sm.primaryStorageVolume.directory
        } else {
            sm.storageVolumes.firstOrNull { it.uuid.equals(id, ignoreCase = true) }?.directory
        }
    }

    /** A download path's folders may not exist yet (Shows/Name/Season 01). */
    private fun nearestExisting(file: File): File? {
        var f: File? = file
        while (f != null && !f.exists()) f = f.parentFile
        return f
    }
}
