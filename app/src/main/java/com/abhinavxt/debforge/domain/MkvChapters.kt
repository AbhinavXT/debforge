package com.abhinavxt.debforge.domain

/**
 * Reads the chapter list of a Matroska (.mkv / .webm) file, which Media3
 * doesn't expose. Chapters sit near the start of the file, before the
 * video; [read] is asked for the first [HEAD_BYTES] and, if the Chapters
 * element lies beyond them, for that element alone (a stream costs one or
 * two range requests). Pure Kotlin: unit-tested in MkvChaptersTest.
 */
object MkvChapters {

    data class Chapter(val startMs: Long, val endMs: Long?, val title: String?)

    const val HEAD_BYTES = 256 * 1024
    /** Larger Chapters elements are not chapters anyone wrote by hand. */
    private const val MAX_CHAPTERS_BYTES = 512 * 1024

    private const val EBML = 0x1A45DFA3L
    private const val SEGMENT = 0x18538067L
    private const val SEEK_HEAD = 0x114D9B74L
    private const val SEEK = 0x4DBBL
    private const val SEEK_ID = 0x53ABL
    private const val SEEK_POSITION = 0x53ACL
    private const val CHAPTERS = 0x1043A770L
    private const val CLUSTER = 0x1F43B675L
    private const val EDITION_ENTRY = 0x45B9L
    private const val EDITION_FLAG_HIDDEN = 0x45BDL
    private const val EDITION_FLAG_DEFAULT = 0x45DBL
    private const val CHAPTER_ATOM = 0xB6L
    private const val CHAPTER_TIME_START = 0x91L
    private const val CHAPTER_TIME_END = 0x92L
    private const val CHAPTER_FLAG_HIDDEN = 0x98L
    private const val CHAPTER_DISPLAY = 0x80L
    private const val CHAP_STRING = 0x85L

    /** An element in a buffer: [dataStart] and [size] in bytes; size -1 = unknown (live / streamed). */
    private class Element(val id: Long, val dataStart: Int, val size: Long) {
        val end: Long get() = dataStart + size
    }

    /** [read] (offset, length) returns up to length bytes, fewer at the end of the file, or null on failure. */
    fun read(read: (Long, Int) -> ByteArray?): List<Chapter> = try {
        readOrThrow(read)
    } catch (e: IndexOutOfBoundsException) {
        emptyList() // truncated or not really Matroska
    }

    private fun readOrThrow(read: (Long, Int) -> ByteArray?): List<Chapter> {
        val head = read(0, HEAD_BYTES) ?: return emptyList()
        val ebml = element(head, 0) ?: return emptyList()
        if (ebml.id != EBML || ebml.size < 0) return emptyList()
        val segment = element(head, (ebml.end).toInt()) ?: return emptyList()
        if (segment.id != SEGMENT) return emptyList()
        val segmentStart = segment.dataStart.toLong()

        var chaptersAt: Long? = null
        var p = segment.dataStart
        while (p < head.size) {
            val e = element(head, p) ?: break
            when (e.id) {
                CHAPTERS -> {
                    if (e.size >= 0 && e.end <= head.size) return chapters(head, e)
                    chaptersAt = p.toLong()
                    break
                }
                SEEK_HEAD -> if (e.size >= 0 && e.end <= head.size && chaptersAt == null) {
                    chaptersAt = seekTarget(head, e, CHAPTERS)?.plus(segmentStart)
                }
                CLUSTER -> break
            }
            if (e.size < 0) break
            p = (e.end).toInt().takeIf { e.end <= Int.MAX_VALUE } ?: break
        }

        val at = chaptersAt ?: return emptyList()
        val header = read(at, 16) ?: return emptyList()
        val e = element(header, 0) ?: return emptyList()
        if (e.id != CHAPTERS || e.size < 0 || e.size > MAX_CHAPTERS_BYTES) return emptyList()
        val whole = read(at, (e.end).toInt()) ?: return emptyList()
        if (whole.size < e.end) return emptyList()
        return chapters(whole, e)
    }

    /** Where SeekHead says [target] starts, relative to the Segment's data. */
    private fun seekTarget(buf: ByteArray, seekHead: Element, target: Long): Long? =
        children(buf, seekHead).filter { it.id == SEEK }.firstNotNullOfOrNull { seek ->
            val parts = children(buf, seek)
            val id = parts.firstOrNull { it.id == SEEK_ID }?.let { uint(buf, it) }
            val position = parts.firstOrNull { it.id == SEEK_POSITION }?.let { uint(buf, it) }
            position.takeIf { id == target }
        }

    private fun chapters(buf: ByteArray, chapters: Element): List<Chapter> {
        val editions = children(buf, chapters).filter { it.id == EDITION_ENTRY }
        fun flag(e: Element, id: Long) = children(buf, e).firstOrNull { it.id == id }?.let { uint(buf, it) == 1L } ?: false
        val edition = editions.firstOrNull { flag(it, EDITION_FLAG_DEFAULT) }
            ?: editions.firstOrNull { !flag(it, EDITION_FLAG_HIDDEN) }
            ?: editions.firstOrNull()
            ?: return emptyList()
        return children(buf, edition).filter { it.id == CHAPTER_ATOM }.mapNotNull { atom ->
            val parts = children(buf, atom)
            if (parts.any { it.id == CHAPTER_FLAG_HIDDEN && uint(buf, it) == 1L }) return@mapNotNull null
            val start = parts.firstOrNull { it.id == CHAPTER_TIME_START }?.let { uint(buf, it) } ?: return@mapNotNull null
            val end = parts.firstOrNull { it.id == CHAPTER_TIME_END }?.let { uint(buf, it) }
            val title = parts.firstOrNull { it.id == CHAPTER_DISPLAY }
                ?.let { d -> children(buf, d).firstOrNull { it.id == CHAP_STRING } }
                ?.let { String(buf, it.dataStart, it.size.toInt(), Charsets.UTF_8).trimEnd('\u0000').trim() }
            Chapter(start / 1_000_000, end?.let { it / 1_000_000 }, title?.ifEmpty { null })
        }.sortedBy { it.startMs }
    }

    private fun children(buf: ByteArray, parent: Element): List<Element> {
        val out = ArrayList<Element>()
        var p = parent.dataStart
        val end = minOf(parent.end, buf.size.toLong()).toInt()
        while (p < end) {
            val e = element(buf, p) ?: break
            if (e.size < 0 || e.end > end) break
            out += e
            p = e.end.toInt()
        }
        return out
    }

    /** Header (ID + size) at [at], or null if it doesn't parse or runs past the buffer. */
    private fun element(buf: ByteArray, at: Int): Element? {
        if (at >= buf.size) return null
        val idLen = vintLength(buf[at]) ?: return null
        if (idLen > 4 || at + idLen > buf.size) return null
        var id = 0L
        for (i in 0 until idLen) id = (id shl 8) or (buf[at + i].toLong() and 0xFF)
        val sAt = at + idLen
        if (sAt >= buf.size) return null
        val sLen = vintLength(buf[sAt]) ?: return null
        if (sAt + sLen > buf.size) return null
        var size = (buf[sAt].toLong() and 0xFF) and (0xFFL shr sLen)
        var allOnes = size == (0xFFL shr sLen)
        for (i in 1 until sLen) {
            val b = buf[sAt + i].toLong() and 0xFF
            size = (size shl 8) or b
            allOnes = allOnes && b == 0xFFL
        }
        return Element(id, sAt + sLen, if (allOnes) -1 else size)
    }

    /** Bytes in a variable-length integer, from its first byte's leading zeros (1–8). */
    private fun vintLength(first: Byte): Int? {
        val b = first.toInt() and 0xFF
        if (b == 0) return null
        return Integer.numberOfLeadingZeros(b) - 23
    }

    private fun uint(buf: ByteArray, e: Element): Long {
        var v = 0L
        for (i in 0 until e.size.toInt().coerceAtMost(8)) v = (v shl 8) or (buf[e.dataStart + i].toLong() and 0xFF)
        return v
    }
}
