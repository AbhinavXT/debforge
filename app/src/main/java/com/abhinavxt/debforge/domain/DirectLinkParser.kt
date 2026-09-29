package com.abhinavxt.debforge.domain

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * Links DebForge downloads itself, without a debrid service: Pixeldrain,
 * Google Drive files and plain file URLs (signed CDN links such as
 * googleusercontent.com, a debrid's own download link, ...).
 *
 * Pure Kotlin, no Android types: unit-tested in DirectLinkParserTest.
 */
object DirectLinkParser {

    sealed interface Target {
        data class PixeldrainFile(val id: String) : Target
        data class PixeldrainList(val id: String) : Target
        data class DriveFile(val id: String) : Target
        /** A Drive folder: not supported (needs the Drive API). */
        data object DriveFolder : Target
        data class Plain(val url: String) : Target
    }

    private val PIXELDRAIN_HOSTS = setOf("pixeldrain.com", "pixeldra.in", "pixeldrain.net")
    private val DRIVE_HOSTS = setOf("drive.google.com", "docs.google.com", "drive.usercontent.google.com")
    private val ID = Regex("[A-Za-z0-9_-]+")

    /** Files worth downloading straight from a plain link (by the URL's last segment). */
    private val FILE_EXTS = setOf(
        "mkv", "mp4", "m4v", "avi", "mov", "webm", "ts", "m2ts", "wmv", "flv", "mpg", "mpeg",
        "mp3", "flac", "m4a", "aac", "ogg", "opus", "wav",
        "zip", "rar", "7z", "tar", "gz", "xz", "iso", "apk", "pdf", "epub", "cbz", "cbr",
        "srt", "ass", "vtt"
    )

    /** What [url] points at, or null if it isn't an http(s) URL. */
    fun classify(url: String): Target? {
        val clean = url.trim()
        val uri = runCatching { URI(clean) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host?.lowercase()?.removePrefix("www.") ?: return null
        val segs = uri.rawPath.orEmpty().split('/').filter { it.isNotEmpty() }

        if (host in PIXELDRAIN_HOSTS) {
            val (kind, id) = when {
                segs.size >= 2 && segs[0] == "u" -> "file" to segs[1]
                segs.size >= 2 && segs[0] == "l" -> "list" to segs[1]
                segs.size >= 3 && segs[0] == "api" && segs[1] == "file" -> "file" to segs[2]
                segs.size >= 3 && segs[0] == "api" && segs[1] == "list" -> "list" to segs[2]
                else -> null to null
            }
            if (id != null && ID.matches(id)) {
                return if (kind == "file") Target.PixeldrainFile(id) else Target.PixeldrainList(id)
            }
        }

        if (host in DRIVE_HOSTS) {
            if ("folders" in segs) return Target.DriveFolder
            // /file/d/<id>/view
            val f = segs.indexOf("file")
            if (f >= 0 && segs.getOrNull(f + 1) == "d") {
                segs.getOrNull(f + 2)?.takeIf { ID.matches(it) }?.let { return Target.DriveFile(it) }
            }
            // /open?id=, /uc?id=, /download?id=
            if (segs.lastOrNull() in setOf("open", "uc", "download")) {
                queryParam(uri.rawQuery, "id")?.takeIf { ID.matches(it) }?.let { return Target.DriveFile(it) }
            }
        }
        return Target.Plain(clean)
    }

    /**
     * True when [url] is better downloaded directly than sent to a debrid
     * service: Pixeldrain, Google Drive, Google's signed download links
     * (often tied to the device that asked for them) and links to a file.
     */
    fun prefersDirect(url: String): Boolean = when (val t = classify(url)) {
        null -> false
        is Target.Plain -> {
            val uri = runCatching { URI(t.url) }.getOrNull()
            val host = uri?.host?.lowercase().orEmpty()
            host.endsWith("googleusercontent.com") ||
                extensionOf(uri?.rawPath.orEmpty().substringAfterLast('/')) in FILE_EXTS
        }
        else -> true
    }

    fun pixeldrainDownloadUrl(id: String) = "https://pixeldrain.com/api/file/$id?download"
    fun pixeldrainInfoUrl(id: String) = "https://pixeldrain.com/api/file/$id/info"
    fun pixeldrainListUrl(id: String) = "https://pixeldrain.com/api/list/$id"

    /** Drive's download endpoint; confirm=t skips the "can't scan for viruses" page for big files. */
    fun driveDownloadUrl(id: String) = "https://drive.usercontent.google.com/download?id=$id&export=download&confirm=t"

    /**
     * The real download link from Drive's "can't scan this file for viruses"
     * page (its download form), or null if [html] has no such form (the file
     * is private, over its quota, or not a file).
     */
    fun driveConfirmUrl(html: String): String? {
        val form = Regex("""<form[^>]*id="download-form"[^>]*>(.*?)</form>""", RegexOption.DOT_MATCHES_ALL)
            .find(html) ?: return null
        val tag = form.value.substringBefore('>')
        val action = Regex("""action="([^"]+)"""").find(tag)?.groupValues?.get(1)?.let(::unescapeHtml) ?: return null
        val inputs = Regex("""<input[^>]*>""").findAll(form.groupValues[1]).mapNotNull { m ->
            val t = m.value
            if (!Regex("""type="hidden"""").containsMatchIn(t)) return@mapNotNull null
            val name = Regex("""name="([^"]*)"""").find(t)?.groupValues?.get(1) ?: return@mapNotNull null
            val value = Regex("""value="([^"]*)"""").find(t)?.groupValues?.get(1).orEmpty()
            unescapeHtml(name) to unescapeHtml(value)
        }.toList()
        if (inputs.isEmpty()) return null
        val query = inputs.joinToString("&") { (k, v) -> enc(k) + "=" + enc(v) }
        return if ('?' in action) "$action&$query" else "$action?$query"
    }

    /** File name from a Content-Disposition header (RFC 6266, incl. filename*=UTF-8''...). */
    fun filenameFromContentDisposition(header: String?): String? {
        if (header.isNullOrBlank()) return null
        Regex("""filename\*\s*=\s*([^']*)'[^']*'([^;]+)""", RegexOption.IGNORE_CASE).find(header)?.let { m ->
            val charset = m.groupValues[1].trim().ifEmpty { "UTF-8" }
            val raw = m.groupValues[2].trim().trim('"')
            runCatching { URLDecoder.decode(raw.replace("+", "%2B"), charset) }.getOrNull()
                ?.let(::sanitize)?.let { return it }
        }
        Regex("""filename\s*=\s*"([^"]*)"""", RegexOption.IGNORE_CASE).find(header)
            ?.let { sanitize(it.groupValues[1]) }?.let { return it }
        return Regex("""filename\s*=\s*([^;]+)""", RegexOption.IGNORE_CASE).find(header)
            ?.let { sanitize(it.groupValues[1].trim()) }
    }

    /** The URL's last path segment if it looks like a file name ("movie.mkv"), else null. */
    fun filenameFromUrl(url: String): String? {
        val last = runCatching { URI(url.trim()).rawPath }.getOrNull()?.substringAfterLast('/') ?: return null
        val decoded = runCatching { URLDecoder.decode(last.replace("+", "%2B"), "UTF-8") }.getOrDefault(last)
        return decoded.takeIf { extensionOf(it).isNotEmpty() }?.let(::sanitize)
    }

    /** "download-<tag>.<ext>" with the extension from the Content-Type. */
    fun fallbackName(contentType: String?, tag: String): String {
        val ext = when (contentType?.substringBefore(';')?.trim()?.lowercase()) {
            "video/mp4" -> "mp4"
            "video/x-matroska", "video/matroska" -> "mkv"
            "video/webm" -> "webm"
            "video/quicktime" -> "mov"
            "video/x-msvideo" -> "avi"
            "video/mp2t" -> "ts"
            "audio/mpeg" -> "mp3"
            "audio/mp4" -> "m4a"
            "audio/flac" -> "flac"
            "application/zip" -> "zip"
            "application/x-rar-compressed", "application/vnd.rar" -> "rar"
            "application/x-7z-compressed" -> "7z"
            "application/pdf" -> "pdf"
            "application/vnd.android.package-archive" -> "apk"
            else -> "bin"
        }
        return "download-$tag.$ext"
    }

    /** A web page rather than a file (a login wall, an error or a confirmation page). */
    fun isWebPage(contentType: String?): Boolean =
        contentType?.substringBefore(';')?.trim()?.lowercase() in setOf("text/html", "application/xhtml+xml")

    /** Total size from "Content-Range: bytes 0-0/12345", or null. */
    fun contentRangeTotal(header: String?): Long? =
        header?.substringAfter('/', "")?.trim()?.toLongOrNull()?.takeIf { it > 0 }

    /** Stable download id for a link: the same link queued twice is the same download. */
    fun itemId(sourceRef: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(sourceRef.toByteArray(Charsets.UTF_8))
        return "direct:" + digest.take(10).joinToString("") { "%02x".format(it) }
    }

    /** "pixeldrain.com" for the Downloads list. */
    fun hostOf(url: String): String =
        runCatching { URI(url.trim()).host }.getOrNull()?.lowercase()?.removePrefix("www.") ?: url

    private fun extensionOf(name: String): String =
        if ('.' in name) name.substringAfterLast('.').lowercase() else ""

    private fun sanitize(name: String): String? =
        name.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("""[:*?"<>|\u0000-\u001f]"""), "_")
            .trim().trim('.')
            .takeIf { it.isNotBlank() }

    private fun queryParam(rawQuery: String?, key: String): String? =
        rawQuery?.split('&')?.firstNotNullOfOrNull { part ->
            val k = part.substringBefore('=')
            if (k == key) runCatching { URLDecoder.decode(part.substringAfter('=', ""), "UTF-8") }.getOrNull() else null
        }?.takeIf { it.isNotEmpty() }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun unescapeHtml(s: String) = s
        .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
        .replace("&lt;", "<").replace("&gt;", ">")
}
