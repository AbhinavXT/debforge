package com.abhinavxt.debforge.download

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipInputStream

/**
 * Unpacks a .zip download into a new folder beside it. Streams the archive (no random access), so it works
 * for plain paths and folder-picker ("saf:") paths alike. Entries that would land outside the target folder
 * are skipped, never written. Anything that goes wrong or is cancelled removes what was written.
 *
 * Stops with [NoSpaceException] once what was written would eat into free space less [SAFETY_MARGIN] (a zip bomb).
 * shortcut: empty folders in the archive are not recreated.
 */
object ZipExtractor {
    class NoSpaceException(cause: Throwable) : IOException("Not enough free space", cause)

    /** Where extracted files go; [open] gets a safe '/'-separated path below the target folder. */
    interface Sink {
        fun open(rel: String): OutputStream
        /** Removes everything written so far. */
        fun discard()
    }

    const val SAFETY_MARGIN = 50L * 1024 * 1024

    data class Result(val files: Int, val skipped: Int)

    /** [raw] cleaned to a relative '/'-path with no "." or empty parts, or null if it is absolute or climbs out ("..", drive letters). */
    fun safeName(raw: String): String? {
        val n = raw.replace('\\', '/')
        if (n.startsWith("/") || Regex("^[A-Za-z]:").containsMatchIn(n)) return null
        val parts = n.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty() || ".." in parts || parts.any { '\u0000' in it }) return null
        return parts.joinToString("/")
    }

    /** The folder to extract [archivePath] into: its name without ".zip", then "name (2)", "name (3)"... until [exists] says free. */
    fun targetFolder(archivePath: String, exists: (String) -> Boolean): String {
        val file = archivePath.substringAfterLast('/')
        val base = (if (file.endsWith(".zip", ignoreCase = true)) file.dropLast(4) else file).ifEmpty { "extracted" }
        var i = 1
        while (true) {
            val name = if (i == 1) base else "$base ($i)"
            val path = if (SafPaths.isSaf(archivePath)) SafPaths.withName(archivePath, name)!!
            else File(File(archivePath).parentFile, name).path
            if (!exists(path)) return path
            i++
        }
    }

    /**
     * Streams [input] into [sink]. [check] runs per buffer and may throw to cancel; [onProgress] gets the files written
     * so far. [freeBytes] is the free space at the target (null = unknown, no limit). A repeated name counts once (the later entry wins).
     */
    fun extract(
        input: InputStream, sink: Sink, check: () -> Unit = {}, onProgress: (Int) -> Unit = {}, freeBytes: () -> Long? = { null },
    ): Result {
        val names = HashSet<String>()
        var skipped = 0
        try {
            val budget = freeBytes()?.minus(SAFETY_MARGIN)
            if (budget != null && budget <= 0) throw NoSpaceException(IOException("less than the safety margin free"))
            var written = 0L
            val zip = ZipInputStream(input)
            val buf = ByteArray(64 * 1024)
            while (true) {
                check()
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val rel = safeName(entry.name)
                if (rel == null) { skipped++; continue }
                BufferedOutputStream(sink.open(rel), buf.size).use { out ->
                    while (true) {
                        check()
                        val n = zip.read(buf)
                        if (n < 0) break
                        written += n
                        if (budget != null && written > budget) throw NoSpaceException(IOException("out of free space"))
                        out.write(buf, 0, n)
                    }
                }
                names.add(rel)
                onProgress(names.size)
            }
        } catch (t: Throwable) {
            runCatching { sink.discard() }
            throw if (t is IOException && t !is NoSpaceException && t.isNoSpace()) NoSpaceException(t) else t
        }
        return Result(names.size, skipped)
    }

    private fun Throwable.isNoSpace(): Boolean =
        generateSequence(this) { it.cause }.take(6).any { it.message?.let { m -> "ENOSPC" in m || "No space left" in m } == true }

    /** Plain folder on disk. */
    class FileSink(private val root: File) : Sink {
        override fun open(rel: String): OutputStream {
            val f = File(root, rel)
            f.parentFile?.mkdirs()
            return FileOutputStream(f)
        }
        override fun discard() { root.deleteRecursively() }
    }

    /** Folder given as an app path string, plain or "saf:"; goes through [DownloadFiles]. */
    class DownloadSink(private val rootPath: String) : Sink {
        override fun open(rel: String): OutputStream {
            val path = SafPaths.parse(rootPath)?.let { SafPaths.join(it.treeUri, it.relativePath + "/" + rel) }
                ?: File(rootPath, rel).path
            val pfd = DownloadFiles.openFd(path)
            // A repeated name in the archive must not leave the earlier entry's tail behind.
            try { DownloadFiles.ioErrno { android.system.Os.ftruncate(pfd.fileDescriptor, 0) } } catch (e: IOException) { pfd.close(); throw e }
            return object : FilterOutputStream(FileOutputStream(pfd.fileDescriptor)) {
                override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
                override fun close() { try { super.close() } finally { pfd.close() } }
            }
        }
        override fun discard() = DownloadFiles.deleteTree(rootPath)
    }

    /** Extracts the archive at [archivePath] (plain or "saf:") into a fresh sibling folder. */
    @Throws(IOException::class)
    fun extractArchive(archivePath: String, check: () -> Unit = {}, onProgress: (Int) -> Unit = {}): Result {
        val target = targetFolder(archivePath, DownloadFiles::exists)
        val input = if (SafPaths.isSaf(archivePath)) android.os.ParcelFileDescriptor.AutoCloseInputStream(DownloadFiles.openFd(archivePath))
        else FileInputStream(archivePath)
        return input.use { extract(it, DownloadSink(target), check, onProgress) { DownloadFiles.availableBytes(target) } }
    }
}
