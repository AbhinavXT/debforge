package com.abhinavxt.debforge.data.lyrics

import com.squareup.moshi.JsonClass
import kotlin.math.abs

data class LrcLine(val timeMs: Long, val text: String)

/** One LRCLIB record; every field can be missing. */
@JsonClass(generateAdapter = true)
data class LrcLibItem(
    val plainLyrics: String? = null,
    val syncedLyrics: String? = null,
    val instrumental: Boolean? = null,
    val duration: Double? = null
) {
    val hasLyrics: Boolean get() = instrumental == true || !plainLyrics.isNullOrBlank() || !syncedLyrics.isNullOrBlank()
}

private val TIME = Regex("""\[(\d{1,5}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
private val OFFSET = Regex("""^\s*\[offset:\s*([+-]?\d{1,9})\s*]""", RegexOption.IGNORE_CASE)

/** Parses LRC text: several timestamps per line allowed, metadata tags ignored, `[offset:ms]` applied. Sorted by time. */
fun parseLrc(text: String): List<LrcLine> {
    var offset = 0L
    val out = ArrayList<LrcLine>()
    for (raw in text.lineSequence()) {
        val off = OFFSET.find(raw)
        if (off != null) { offset = off.groupValues[1].toLongOrNull() ?: 0L; continue }
        var rest = raw.trimStart()
        val times = ArrayList<Long>()
        while (true) {
            val m = TIME.find(rest)?.takeIf { it.range.first == 0 } ?: break
            val frac = m.groupValues[3]
            val min = m.groupValues[1].toLongOrNull()
            val sec = m.groupValues[2].toLongOrNull()
            if (min != null && sec != null) times += min * 60_000 + sec * 1000 + if (frac.isEmpty()) 0 else (("0.$frac").toDoubleOrNull() ?: 0.0).let { (it * 1000).toLong() }
            rest = rest.substring(m.range.last + 1).trimStart()
        }
        times.forEach { out += LrcLine((it - offset).coerceAtLeast(0), rest.trim()) }
    }
    return out.sortedBy { it.timeMs }
}

/** Index of the line being sung at [positionMs]: the last one starting at or before it, or -1 before the first. */
fun lineAt(lines: List<LrcLine>, positionMs: Long): Int {
    var lo = 0
    var hi = lines.size - 1
    var found = -1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        if (lines[mid].timeMs <= positionMs) { found = mid; lo = mid + 1 } else hi = mid - 1
    }
    return found
}

private val NOISE = Regex(
    """\s*[(\[][^)\]]*\b(official|lyrics?|video|audio|visuali[sz]er|hd|4k|hq|mv|m/v|remaster(ed)?)\b[^)\]]*[)\]]""",
    RegexOption.IGNORE_CASE
)
private val FEAT = Regex("""\s*[(\[]?\s*\b(ft|feat|featuring)\b\.?\s.*$""", RegexOption.IGNORE_CASE)

/** A YouTube title without its "(Official Video)", "[Lyrics]" and "ft. ..." noise. */
fun cleanTitle(title: String): String {
    val t = FEAT.replace(NOISE.replace(title, ""), "").trim().trimEnd('-', '|').trim()
    return t.ifEmpty { title.trim() }
}

/** The artist as a channel name gives it: "Name - Topic" and "NameVEVO" are just Name. */
fun cleanArtist(artist: String?): String? =
    artist?.replace(Regex("""\s*-\s*Topic$""", RegexOption.IGNORE_CASE), "")
        ?.replace(Regex("""VEVO$""", RegexOption.IGNORE_CASE), "")?.trim()?.takeIf { it.isNotEmpty() }

/**
 * The record whose length is within 3 s of [durationSec] (closest first). With a known length and none close,
 * only plain text of another record is offered (its synced lines would drift); with no length, the first with lyrics.
 */
fun pickLyrics(items: List<LrcLibItem>, durationSec: Long): LrcLibItem? {
    val usable = items.filter { it.hasLyrics }
    if (durationSec <= 0) return usable.firstOrNull()
    usable.filter { it.duration != null && abs(it.duration - durationSec) <= 3 }
        .minByOrNull { abs(it.duration!! - durationSec) }?.let { return it }
    return usable.firstOrNull { !it.plainLyrics.isNullOrBlank() }?.copy(syncedLyrics = null, instrumental = null)
}
