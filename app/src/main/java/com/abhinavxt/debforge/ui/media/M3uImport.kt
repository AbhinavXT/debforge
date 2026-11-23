package com.abhinavxt.debforge.ui.media

/** %XX escapes decoded as UTF-8; unlike URLDecoder a '+' stays a '+' and a malformed escape stays as typed. */
internal fun percentDecode(s: String): String {
    if ('%' !in s) return s
    val out = java.io.ByteArrayOutputStream()
    var i = 0
    while (i < s.length) {
        val v = if (s[i] == '%' && i + 2 < s.length) s.substring(i + 1, i + 3).takeIf { h -> h.all { it.digitToIntOrNull(16) != null } }?.toInt(16) else null
        if (v != null) { out.write(v); i += 3 }
        else { val cp = s.codePointAt(i); out.write(String(Character.toChars(cp)).toByteArray()); i += Character.charCount(cp) }
    }
    return out.toString(Charsets.UTF_8.name())
}

/** Playable entries of an M3U/M3U8 text: comments and blanks dropped, BOM stripped, `file://` unwrapped, %XX decoded (not remote URLs), backslashes turned into slashes. */
internal fun parseM3u(text: String): List<String> =
    text.removePrefix("\uFEFF").lineSequence().map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .map { line -> if (line.contains("://") && !line.startsWith("file://", true)) line else percentDecode(if (line.startsWith("file://", true)) line.substring(7) else line) }
        .map { it.replace('\\', '/') }
        .toList()

/**
 * Track ids for [entries] in file order, no duplicates. [tracks] are (id, absolute path).
 * An entry matches the track whose path ends with it (case-insensitive), else the track with the same file name if exactly one has it.
 * Remote entries never match. Returns the ids and how many entries found nothing.
 */
internal fun matchM3u(entries: List<String>, tracks: List<Pair<String, String>>): Pair<List<String>, Int> {
    val norm = tracks.map { (id, p) -> id to p.replace('\\', '/').lowercase() }
    val ids = LinkedHashSet<String>()
    var missing = 0
    for (e in entries) {
        var rel = e.lowercase().trimStart('/')
        while (rel.startsWith("./") || rel.startsWith("../")) rel = rel.substringAfter('/')
        val hit = if (e.contains("://") || rel.isEmpty()) null
        else norm.firstOrNull { (_, p) -> p == "/$rel" || p.endsWith("/$rel") }
            ?: rel.substringAfterLast('/').let { n -> norm.filter { (_, p) -> p.substringAfterLast('/') == n }.singleOrNull() }
        if (hit == null) missing++ else ids.add(hit.first)
    }
    return ids.toList() to missing
}
