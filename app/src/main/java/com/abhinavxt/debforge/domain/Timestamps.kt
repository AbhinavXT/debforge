package com.abhinavxt.debforge.domain

/** A "m:ss", "mm:ss" or "h:mm:ss" time found in [start] until [end] (exclusive) of a text. */
data class Timestamp(val start: Int, val end: Int, val seconds: Long)

private const val TS = "(?:(\\d{1,2}):)?(\\d{1,2}):(\\d{2})"
private val TIMESTAMP = Regex("(?<![\\w:/.])$TS(?![\\w:])")
private val URL = Regex("https?://\\S+", RegexOption.IGNORE_CASE)
private val CHAPTER_LINE = Regex("^[\\s\\-\u2022*\\[(>]*$TS[\\s\\])\\-\u2013\u2014:.]*(\\S.*)$")

private fun toSeconds(h: String, m: String, s: String): Long? {
    val min = m.toLong()
    val sec = s.toLong()
    if (sec > 59 || (h.isNotEmpty() && min > 59)) return null
    return (if (h.isEmpty()) 0 else h.toLong()) * 3600 + min * 60 + sec
}

/** Times in [text] that can be tapped to seek: not inside a URL, and (when [durationSec] > 0) not past the end. */
fun findTimestamps(text: String, durationSec: Long = 0): List<Timestamp> {
    val urls = URL.findAll(text).map { it.range }.toList()
    return TIMESTAMP.findAll(text).mapNotNull { m ->
        if (urls.any { m.range.first in it }) return@mapNotNull null
        val (h, min, s) = m.destructured
        val sec = toSeconds(h, min, s) ?: return@mapNotNull null
        if (durationSec > 0 && sec > durationSec) null else Timestamp(m.range.first, m.range.last + 1, sec)
    }.toList()
}

/**
 * Chapters a description declares the way YouTube reads them: lines "time title", the first at 0:00, at least three,
 * strictly ascending. Anything else is not a chapter list (empty).
 */
fun descriptionChapters(text: String, durationSec: Long = 0): List<MkvChapters.Chapter> {
    val found = text.lineSequence().mapNotNull { line ->
        val m = CHAPTER_LINE.find(line.trim()) ?: return@mapNotNull null
        val (h, min, s, title) = m.destructured
        toSeconds(h, min, s)?.let { it to title.trim() }
    }.toList()
    if (found.size < 3 || found.first().first != 0L) return emptyList()
    if (found.zipWithNext().any { (a, b) -> b.first <= a.first }) return emptyList()
    if (durationSec > 0 && found.last().first >= durationSec) return emptyList()
    return found.mapIndexed { i, (sec, title) ->
        val end = found.getOrNull(i + 1)?.first ?: durationSec.takeIf { it > 0 }
        MkvChapters.Chapter(sec * 1000, end?.times(1000), title)
    }
}
