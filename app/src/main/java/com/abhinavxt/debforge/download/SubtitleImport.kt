package com.abhinavxt.debforge.download

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.abhinavxt.debforge.R
import com.abhinavxt.debforge.data.local.DownloadDao
import com.abhinavxt.debforge.domain.Subtitles
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** The pure parts of adding a subtitle file the user picked: its type and the name it gets. Unit-tested. */
object SubtitleFiles {
    /** What the system picker is asked to show: many pickers don't know .srt, so the generic types are in too. */
    val PICKER_TYPES = arrayOf(
        "application/x-subrip", "text/x-ssa", "text/vtt", "application/x-ssa",
        "text/plain", "text/*", "application/octet-stream"
    )

    /** Larger than this isn't a subtitle. */
    const val MAX_BYTES = 10 * 1024 * 1024

    private val BY_MIME = mapOf(
        "application/x-subrip" to "srt", "text/srt" to "srt", "text/vtt" to "vtt",
        "text/x-ssa" to "ass", "application/x-ssa" to "ass", "text/x-ass" to "ass"
    )

    /** "srt"/"ass"/"ssa"/"vtt" from the picked file's name, else from its MIME type; null when it is neither. */
    fun extensionFor(displayName: String?, mime: String?): String? {
        val ext = displayName?.substringAfterLast('.', "")?.lowercase().orEmpty()
        if (ext in Subtitles.MIME_TYPES) return ext
        return BY_MIME[mime?.lowercase()]
    }

    /** The first [limit] bytes as text: UTF-8, or UTF-16 when it starts with that BOM or (no BOM) is mostly NULs on one parity. */
    fun decodeHead(bytes: ByteArray, limit: Int = 4096): String {
        val n = minOf(bytes.size, limit)
        fun b(i: Int) = bytes[i].toInt() and 0xFF
        val charset = when {
            n >= 2 && b(0) == 0xFF && b(1) == 0xFE -> Charsets.UTF_16LE
            n >= 2 && b(0) == 0xFE && b(1) == 0xFF -> Charsets.UTF_16BE
            n >= 4 -> {
                var even = 0
                var odd = 0
                for (i in 0 until n) if (bytes[i].toInt() == 0) { if (i % 2 == 0) even++ else odd++ }
                when {
                    odd * 4 >= n && even == 0 -> Charsets.UTF_16LE
                    even * 4 >= n && odd == 0 -> Charsets.UTF_16BE
                    else -> Charsets.UTF_8
                }
            }
            else -> Charsets.UTF_8
        }
        // Drop a trailing half code unit so the cut doesn't decode to a stray character.
        val len = if (charset == Charsets.UTF_8) n else n and 1.inv()
        return String(bytes, 0, len, charset)
    }

    /** True if [head] (the start of the file) reads like subtitles of type [ext]; keeps a renamed video or image out. */
    fun looksLike(ext: String, head: String): Boolean {
        val text = head.removePrefix("\uFEFF")
        return when (ext) {
            "vtt" -> text.trimStart().startsWith("WEBVTT")
            "ass", "ssa" -> text.contains("[Script Info]", ignoreCase = true) ||
                text.contains("[Events]", ignoreCase = true) || text.contains("Dialogue:")
            else -> text.contains("-->")
        }
    }

    /**
     * The name for the copy: "<base>.<ext>", or "<base>.1.<ext>", "<base>.2.<ext>"… when [taken] (compared
     * ignoring case) already has it. [base] is the video's name without its extension.
     */
    fun targetName(base: String, ext: String, taken: Collection<String>): String {
        val used = taken.mapTo(HashSet()) { it.lowercase() }
        var name = "$base.$ext"
        var n = 1
        while (name.lowercase() in used) name = "$base.${n++}.$ext"
        return name
    }
}

/**
 * Copies a subtitle file the user picked next to the downloaded video it is for (so it pairs like any
 * sidecar subtitle, survives restarts and shows in other players too). If the video's folder can't be
 * written, the copy goes to app storage keyed by the video's id; [LocalPlayback][com.abhinavxt.debforge.player.LocalPlayback]
 * lists that location too. All of it runs on IO.
 */
@Singleton
class SubtitleImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val downloadDao: DownloadDao
) {
    /** [reason] is a string resource for the message shown to the user. */
    class Problem(val reason: Int) : Exception()

    /** The copy: its file name, link, and the video's folder name for the sidecar pairing. */
    data class Added(val name: String, val uri: Uri)

    suspend fun add(videoId: String, picked: Uri): Added = withContext(Dispatchers.IO) {
        val videoPath = downloadDao.getById(videoId)?.finalFilePath?.takeIf { LocalFiles.exists(it) }
            ?: throw Problem(R.string.player_subs_file_failed)
        val resolver = context.contentResolver
        val displayName = try {
            resolver.query(picked, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        } catch (e: Exception) {
            null
        } ?: picked.lastPathSegment
        val ext = SubtitleFiles.extensionFor(displayName, resolver.getType(picked))
            ?: throw Problem(R.string.player_subs_file_invalid)
        val bytes = try {
            resolver.openInputStream(picked)?.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > SubtitleFiles.MAX_BYTES) throw Problem(R.string.player_subs_file_invalid)
                }
                out.toByteArray()
            }
        } catch (e: Problem) {
            throw e
        } catch (e: Exception) {
            null
        } ?: throw Problem(R.string.player_subs_file_failed)
        if (!SubtitleFiles.looksLike(ext, SubtitleFiles.decodeHead(bytes))) {
            throw Problem(R.string.player_subs_file_invalid)
        }

        val base = LocalFiles.name(videoPath).substringBeforeLast('.')
        val taken = LocalFiles.subtitleSiblings(context, videoPath).keys + fallbackFiles(videoId).map { it.name }
        val name = SubtitleFiles.targetName(base, ext, taken + LocalFiles.name(videoPath))
        try {
            Added(name, writeNextToVideo(videoPath, name, bytes))
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            try {
                Added(name, writeToAppStorage(videoId, name, bytes))
            } catch (e2: Exception) {
                throw Problem(R.string.player_subs_file_failed)
            }
        }
    }

    private fun writeNextToVideo(videoPath: String, name: String, bytes: ByteArray): Uri {
        if (SafPaths.isSaf(videoPath)) {
            val target = SafPaths.withName(videoPath, name) ?: throw IOException("bad path")
            val uri = SafStore.ensureFile(context, target)
            try {
                (context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("no stream")).use { it.write(bytes) }
            } catch (e: Exception) {
                SafStore.delete(context, target)
                throw e
            }
            return uri
        }
        val dir = File(videoPath).parentFile ?: throw IOException("no folder")
        if (!dir.canWrite()) throw IOException("folder not writable")
        val file = File(dir, name)
        try {
            file.writeBytes(bytes)
        } catch (e: Exception) {
            file.delete()
            throw e
        }
        return LocalFiles.uriFor(context, file.path) ?: Uri.fromFile(file)
    }

    private fun writeToAppStorage(videoId: String, name: String, bytes: ByteArray): Uri {
        val dir = fallbackDir(videoId)
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("no app folder")
        val file = File(dir, name)
        file.writeBytes(bytes)
        return Uri.fromFile(file)
    }

    private fun fallbackDir(videoId: String) = fallbackDir(context, videoId)

    private fun fallbackFiles(videoId: String): List<File> = fallbackSubtitles(context, videoId).keys.map { File(it) }

    companion object {
        private fun fallbackDir(context: Context, videoId: String) =
            File(File(context.filesDir, "subtitles"), videoId.replace(Regex("[^A-Za-z0-9._-]"), "_"))

        /** Subtitle copies kept in app storage for [videoId] (the video's folder wasn't writable): name to file. Blocking. */
        fun appStorageSubtitles(context: Context, videoId: String): Map<String, Uri> =
            fallbackSubtitles(context, videoId).entries.associate { (path, uri) -> File(path).name to uri }

        private fun fallbackSubtitles(context: Context, videoId: String): Map<String, Uri> {
            val files = fallbackDir(context, videoId).listFiles() ?: return emptyMap()
            return files.filter { it.isFile && it.extension.lowercase() in Subtitles.MIME_TYPES }
                .associate { it.path to Uri.fromFile(it) }
        }
    }
}
