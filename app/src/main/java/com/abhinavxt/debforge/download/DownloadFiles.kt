package com.abhinavxt.debforge.download

import android.content.Context
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import java.io.Closeable
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.FileChannel

/**
 * All disk-side operations for a download, isolated so the corruption-critical
 * logic lives in one place. No coroutines here — callers invoke these on
 * Dispatchers.IO.
 *
 * Paths come in two kinds, and every function here handles both:
 *  - ordinary absolute paths, written with java.io: app storage, or (read
 *    only) shared folders that older versions saved to with All files access;
 *  - [SafPaths] ("saf:<tree>#dir/x.mkv") inside a folder the user picked with
 *    the system folder picker — SD cards, USB drives, any folder, no special
 *    permission. Written through file descriptors from the Storage Access
 *    Framework, which for local storage are real files: seeking, parallel
 *    writers and ftruncate all work the same.
 *
 * Design notes:
 *  - We download to "<final>.part" and only rename to the real name after the
 *    whole file is complete and size-verified. A half-written file never wears
 *    the final name.
 *  - Each chunk opens its OWN handle and seeks to its absolute offset.
 *    Multiple handles writing to disjoint regions of the same file is safe and
 *    avoids a global write lock — this is what makes parallelism fast.
 *  - We pre-size the .part file up front so every chunk's offset is valid
 *    immediately and we fail fast (ENOSPC) if the disk can't hold the file.
 */
object DownloadFiles {

    @Volatile private var appContext: Context? = null

    /** Called once from Application.onCreate (folder-picker paths need a Context). */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private val context: Context
        get() = appContext ?: throw IllegalStateException("DownloadFiles.init() was not called")

    /** The temp path we stream bytes into. */
    fun partPathFor(finalPath: String): String = "$finalPath.part"

    /** App-private working file for download [id] (not in the user's folder), see [AudioFinish.needsPrivatePart]. */
    fun pendingPathFor(id: String): String = File(File(context.filesDir, "pending"), AudioFinish.pendingName(id)).path

    /** Removes the private working file of [id]. Safe if absent. */
    fun deletePending(id: String) {
        runCatching { deletePart(pendingPathFor(id)) } // with the .mux an audio extraction leaves beside it
    }

    /**
     * Copies the plain file [source] to [destPath] (any kind of path) in one buffered stream, replacing
     * what is there, and returns the bytes copied. [check] runs per buffer (cancellation). Throws
     * IOException when the copy is short or fails; the caller removes the partial destination.
     */
    @Throws(IOException::class)
    fun copyTo(source: File, destPath: String, check: () -> Unit = {}): Long {
        val total = source.length()
        openFd(destPath).use { pfd ->
            ioErrno { Os.ftruncate(pfd.fileDescriptor, 0) }
            FileOutputStream(pfd.fileDescriptor).use { out ->
                source.inputStream().use { copyStream(it, out, total, check) }
                out.fd.sync()
            }
            if (pfd.statSize != total) throw copyFailed("Copied file has the wrong size: ${pfd.statSize} of $total bytes")
        }
        return total
    }

    /** What the user sees when saving into the chosen folder fails; the technical [detail] rides along as the cause, for the log. */
    internal fun copyFailed(detail: String) =
        IOException("Couldn't save the file to the chosen folder. Try again.", IOException(detail))

    /** Copies [input] to [out] through a 1 MiB buffer; throws unless exactly [total] bytes went across. */
    @Throws(IOException::class)
    internal fun copyStream(input: java.io.InputStream, out: java.io.OutputStream, total: Long, check: () -> Unit = {}) {
        val buf = ByteArray(1 shl 20)
        var copied = 0L
        while (true) {
            check()
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            copied += n
        }
        out.flush()
        if (copied != total) throw copyFailed("Copy was short ($copied of $total bytes)")
    }

    /** An open, writable channel positioned where the caller asked. Close with `use {}`. */
    class WriteHandle internal constructor(
        val channel: FileChannel,
        private val fd: FileDescriptor,
        private val closeables: List<Closeable>
    ) : Closeable {
        /** Force contents to physical storage. */
        fun sync() = fd.sync()
        override fun close() = closeables.forEach { runCatching { it.close() } }
    }

    /** Free bytes where [path] lives; null if unknown (see [StorageSpace]). */
    fun availableBytes(path: String): Long? = StorageSpace.availableBytes(context, path)

    fun exists(path: String): Boolean =
        if (SafPaths.isSaf(path)) SafStore.exists(context, path) else File(path).exists()

    /**
     * Ensures the parent directory exists and the .part file is exactly
     * [filesize] bytes (sparse where the FS supports it). Idempotent: if the
     * file already exists at the right length (a resume), it is left untouched.
     */
    @Throws(IOException::class)
    fun preallocate(partPath: String, filesize: Long) {
        if (SafPaths.isSaf(partPath)) {
            openSaf(partPath, create = true).use { pfd ->
                ioErrno {
                    if (Os.fstat(pfd.fileDescriptor).st_size != filesize) Os.ftruncate(pfd.fileDescriptor, filesize)
                }
            }
            return
        }
        val part = File(partPath)
        ensureParent(part)
        RandomAccessFile(part, "rw").use { raf ->
            if (raf.length() != filesize) {
                raf.setLength(filesize)
            }
        }
    }

    /** Writable handle positioned at [offset] of an existing (preallocated) .part file. */
    @Throws(IOException::class)
    fun openAt(partPath: String, offset: Long): WriteHandle {
        if (SafPaths.isSaf(partPath)) {
            val pfd = openSaf(partPath, create = false)
            val out = FileOutputStream(pfd.fileDescriptor)
            val channel = out.channel.position(offset)
            return WriteHandle(channel, pfd.fileDescriptor, listOf(channel, out, pfd))
        }
        val raf = RandomAccessFile(partPath, "rw")
        raf.seek(offset)
        return WriteHandle(raf.channel, raf.fd, listOf(raf))
    }

    /** Fresh, empty .part file for a single stream of unknown length. */
    @Throws(IOException::class)
    fun openTruncated(partPath: String): WriteHandle {
        if (SafPaths.isSaf(partPath)) {
            val pfd = openSaf(partPath, create = true)
            ioErrno { Os.ftruncate(pfd.fileDescriptor, 0) }
            val out = FileOutputStream(pfd.fileDescriptor)
            return WriteHandle(out.channel.position(0), pfd.fileDescriptor, listOf(out.channel, out, pfd))
        }
        val part = File(partPath)
        ensureParent(part)
        val out = FileOutputStream(part, false)
        return WriteHandle(out.channel, out.fd, listOf(out))
    }

    /**
     * Final step: assert the .part file is exactly the expected size, force it
     * to disk, then rename to the final name. Returns false if the size check
     * fails (truncated/corrupt) — caller should treat as failure and NOT rename.
     */
    @Throws(IOException::class)
    fun verifyAndPromote(partPath: String, finalPath: String, expectedSize: Long): Boolean {
        if (SafPaths.isSaf(partPath)) {
            val partUri = SafStore.find(context, partPath) ?: return false
            val ok = context.contentResolver.openFileDescriptor(partUri, "rw")?.use { pfd ->
                if (pfd.statSize != expectedSize) return@use false
                pfd.fileDescriptor.sync()
                true
            } ?: false
            if (!ok) return false
            val finalName = SafPaths.parse(finalPath)?.name ?: throw IOException("Bad destination: $finalPath")
            SafStore.delete(context, finalPath)
            SafStore.rename(context, partPath, finalName)
            return true
        }
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
        // A stream with separate audio leaves these beside the .part (see HlsDownloader).
        listOf(partPath, "$partPath.audio", "$partPath.mux").forEach(::delete)
    }

    /** Read/write descriptor for [path], created if missing: for the stream muxer, which needs one. */
    @Throws(IOException::class)
    fun openFd(path: String): ParcelFileDescriptor {
        if (SafPaths.isSaf(path)) return openSaf(path, create = true)
        val file = File(path)
        ensureParent(file)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE)
    }

    /**
     * Remove the promoted final file. Used when a cancel arrives after the
     * downloader had already renamed .part -> final; without this, the user's
     * explicit cancel would leave the completed file on disk with no DB
     * record. Safe if absent.
     */
    fun deleteFinal(finalPath: String?) = delete(finalPath)

    /** Removes the file or folder (with everything in it) at [path]. Safe if absent. */
    fun deleteTree(path: String) {
        if (SafPaths.isSaf(path)) delete(path) else File(path).deleteRecursively()
    }

    private fun delete(path: String?) {
        if (path == null) return
        runCatching {
            if (SafPaths.isSaf(path)) SafStore.delete(context, path)
            else File(path).takeIf { it.exists() }?.delete()
        }
    }

    // --- helpers -----------------------------------------------------------------

    private fun ensureParent(file: File) {
        file.parentFile?.let { dir ->
            if (!dir.exists() && !dir.mkdirs()) {
                throw IOException("Could not create directory: ${dir.absolutePath}")
            }
        }
    }

    private fun openSaf(path: String, create: Boolean): ParcelFileDescriptor {
        val uri = try {
            if (create) SafStore.ensureFile(context, path) else SafStore.find(context, path)
        } catch (e: SecurityException) {
            throw IOException("No access to the download folder any more. Choose it again in Settings.", e)
        } catch (e: IllegalArgumentException) {
            throw IOException(e.message ?: "Storage error", e)
        } ?: throw IOException("Partial download file is missing")
        return try {
            context.contentResolver.openFileDescriptor(uri, "rw")
        } catch (e: SecurityException) {
            throw IOException("No access to the download folder any more. Choose it again in Settings.", e)
        } ?: throw IOException("Could not open the download file")
    }

    internal inline fun <T> ioErrno(block: () -> T): T = try {
        block()
    } catch (e: ErrnoException) {
        throw IOException(e.message, e)
    }
}
