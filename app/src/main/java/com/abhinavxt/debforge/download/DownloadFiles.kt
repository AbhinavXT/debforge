package com.abhinavxt.debforge.download

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * All disk-side operations for a download, isolated so the corruption-critical
 * logic lives in one place. No coroutines here — callers invoke these on
 * Dispatchers.IO.
 *
 * Design notes:
 *  - We download to "<final>.part" and only rename to the real name after the
 *    whole file is complete and size-verified. A half-written file never wears
 *    the final name.
 *  - Each chunk opens its OWN RandomAccessFile handle and seeks to its absolute
 *    offset. Multiple handles writing to disjoint regions of the same file is
 *    safe and avoids a global write lock — this is what makes parallelism fast.
 *  - We pre-size the .part file up front so every chunk's offset is valid
 *    immediately and we fail fast (ENOSPC) if the disk can't hold the file.
 */
object DownloadFiles {

    /** The temp path we stream bytes into. */
    fun partPathFor(finalPath: String): String = "$finalPath.part"

    /**
     * Ensures the parent directory exists and the .part file is exactly
     * [filesize] bytes (sparse where the FS supports it). Idempotent: if the
     * file already exists at the right length (a resume), it is left untouched.
     */
    @Throws(IOException::class)
    fun preallocate(partPath: String, filesize: Long) {
        val part = File(partPath)
        part.parentFile?.let { dir ->
            if (!dir.exists() && !dir.mkdirs()) {
                throw IOException("Could not create directory: ${dir.absolutePath}")
            }
        }
        RandomAccessFile(part, "rw").use { raf ->
            if (raf.length() != filesize) {
                raf.setLength(filesize)
            }
        }
    }

    /**
     * Opens a writable handle positioned at [offset]. Caller writes its range
     * and is responsible for closing (use `.use { }`).
     */
    @Throws(IOException::class)
    fun openAt(partPath: String, offset: Long): RandomAccessFile {
        val raf = RandomAccessFile(partPath, "rw")
        raf.seek(offset)
        return raf
    }

    /** Force file contents + metadata to physical storage. */
    @Throws(IOException::class)
    fun fsync(raf: RandomAccessFile) {
        raf.fd.sync()
    }

    /**
     * Final step: assert the .part file is exactly the expected size, force it
     * to disk, then atomically rename to the final path. Returns false if the
     * size check fails (truncated/corrupt) — caller should treat as failure and
     * NOT rename.
     */
    @Throws(IOException::class)
    fun verifyAndPromote(partPath: String, finalPath: String, expectedSize: Long): Boolean {
        val part = File(partPath)
        if (!part.exists() || part.length() != expectedSize) {
            return false
        }
        // Force to disk before the rename so a crash can't leave a renamed but
        // unflushed file.
        RandomAccessFile(part, "rw").use { it.fd.sync() }

        val finalFile = File(finalPath)
        if (finalFile.exists()) finalFile.delete()
        if (!part.renameTo(finalFile)) {
            throw IOException("Rename failed: $partPath -> $finalPath")
        }
        return true
    }

    /** Remove a .part file (used on cancel). Safe if absent. */
    fun deletePart(partPath: String?) {
        if (partPath == null) return
        runCatching { File(partPath).takeIf { it.exists() }?.delete() }
    }
}
