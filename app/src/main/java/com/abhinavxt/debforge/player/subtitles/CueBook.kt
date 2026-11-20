package com.abhinavxt.debforge.player.subtitles

/** One cue as read from the file (before any correction), as plain text. */
data class RawCue(val startMs: Long, val endMs: Long, val text: String)

/**
 * The raw cues of this session's side-loaded subtitles, per timing key, for the sync tools. Thread-safe, bounded.
 * A new parse of a key fills a fresh list; the previous one stays readable until the new one has its first cue, so
 * the tools keep working while the file reloads.
 */
class CueBook(private val maxCuesPerKey: Int = 20_000, private val maxKeys: Int = 8) {
    private class Book {
        var ready: ArrayList<RawCue> = ArrayList()
        var pending: ArrayList<RawCue>? = null
        var truncated = false
    }

    private val books = object : LinkedHashMap<String, Book>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Book>?) = size > maxKeys
    }

    /** A new parse of [key] starts. */
    @Synchronized fun begin(key: String) {
        val book = books.getOrPut(key) { Book() }
        book.pending = ArrayList()
        book.truncated = false
    }

    @Synchronized fun add(key: String, cue: RawCue) {
        val book = books.getOrPut(key) { Book() }
        val list = book.pending ?: ArrayList<RawCue>().also { book.pending = it }
        if (list.size < maxCuesPerKey) list.add(cue) else book.truncated = true
        if (list.size == 1) {
            // The first cue of the new parse: it replaces the old list.
            book.ready = list
        }
    }

    @Synchronized fun cues(key: String): List<RawCue> = books[key]?.ready?.sortedBy { it.startMs }.orEmpty()

    /** True when [key] had more cues than the book keeps, so the tools only see the first part. */
    @Synchronized fun isTruncated(key: String): Boolean = books[key]?.truncated == true

    /** Cues starting within [windowMs] of [centreMs] (file times), in order. */
    fun around(key: String, centreMs: Long, windowMs: Long): List<RawCue> =
        cues(key).filter { it.startMs in (centreMs - windowMs)..(centreMs + windowMs) }

    @Synchronized fun clear() = books.clear()
}
