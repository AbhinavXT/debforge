package com.abhinavxt.debforge.ui.media

import java.net.URLDecoder

/** Playable entries of an M3U/M3U8 text: comments and blanks dropped, BOM stripped, `file://` unwrapped, backslashes turned into slashes. */
internal fun parseM3u(text: String): List<String> =
    text.removePrefix("﻿").lineSequence().map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .map { line ->
            if (line.startsWith("file://", true)) {
                // shortcut: a literal '+' in a file URI is read as a space, fine for real playlists.
                runCatching { URLDecoder.decode(line.substring(7), "UTF-8") }.getOrDefault(line.substring(7))
            } else line
        }
        .map { it.replace('\\', '/') }
        .toList()

/**
 * Track ids for [entries] in file order, no duplicates. [tracks] are (id, absolute path).
 * An entry matches the track whose path ends with it (case-insensitive), else the first track with the same file name.
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
            ?: rel.substringAfterLast('/').let { n -> norm.firstOrNull { (_, p) -> p.substringAfterLast('/') == n } }
        if (hit == null) missing++ else ids.add(hit.first)
    }
    return ids.toList() to missing
}
