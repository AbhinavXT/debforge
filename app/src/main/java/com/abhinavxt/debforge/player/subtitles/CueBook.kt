package com.abhinavxt.debforge.player.subtitles

/** One cue as read from the file (before any correction), as plain text. */
data class RawCue(val startMs: Long, val endMs: Long, val text: String)

/** The raw cues of this session's side-loaded subtitles, per timing key, for the sync tools. Thread-safe, bounded. */
class CueBook(private val maxCuesPerKey: Int = 20_000, private val maxKeys: Int = 8) {
    private val books = object : LinkedHashMap<String, ArrayList<RawCue>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ArrayList<RawCue>>?) = size > maxKeys
    }

    /** A new parse of [key] starts: forget the old cues. */
    @Synchronized fun begin(key: String) {
        books[key] = ArrayList()
    }

    @Synchronized fun add(key: String, cue: RawCue) {
        val list = books.getOrPut(key) { ArrayList() }
        if (list.size < maxCuesPerKey) list.add(cue)
    }

    @Synchronized fun cues(key: String): List<RawCue> = books[key]?.sortedBy { it.startMs }.orEmpty()

    /** Cues starting within [windowMs] of [centreMs] (file times), in order. */
    fun around(key: String, centreMs: Long, windowMs: Long): List<RawCue> =
        cues(key).filter { it.startMs in (centreMs - windowMs)..(centreMs + windowMs) }

    @Synchronized fun clear() = books.clear()
}
