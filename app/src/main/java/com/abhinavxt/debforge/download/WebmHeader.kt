package com.abhinavxt.debforge.download

import java.io.File
import java.io.RandomAccessFile

/**
 * Cheap structural check for a downloaded WebM audio file.
 *
 * A YouTube audio download is already a complete, seekable WebM file, so when it passes this
 * check the slow MediaMuxer remux can be skipped. Only element headers, SeekHead, Info and
 * Tracks are read (a few KB plus a handful of seeks); cluster data is never touched.
 */
object WebmHeader {
    sealed interface Result {
        data class Ok(val durationMs: Long) : Result
        data class Unsupported(val reason: String) : Result
    }

    private const val ID_EBML = 0x1A45DFA3L
    private const val ID_DOCTYPE = 0x4282L
    private const val ID_SEGMENT = 0x18538067L
    private const val ID_SEEKHEAD = 0x114D9B74L
    private const val ID_SEEK = 0x4DBBL
    private const val ID_SEEK_ID = 0x53ABL
    private const val ID_SEEK_POS = 0x53ACL
    private const val ID_INFO = 0x1549A966L
    private const val ID_TIMESTAMP_SCALE = 0x2AD7B1L
    private const val ID_DURATION = 0x4489L
    private const val ID_TRACKS = 0x1654AE6BL
    private const val ID_TRACK_ENTRY = 0xAEL
    private const val ID_TRACK_TYPE = 0x83L
    private const val ID_CLUSTER = 0x1F43B675L
    private const val ID_CUES = 0x1C53BB6BL

    private const val UNKNOWN = -1L
    private const val MAX_BODY = 1L shl 20

    private class Header(val id: Long, val size: Long, val headerLen: Int)

    /**
     * Returns [Result.Ok] with the duration in milliseconds when [file] is a complete,
     * single-audio-track WebM with Cues, whose Segment ends exactly at the end of the file.
     * [expectedDurationSec], when given, must match the header duration within 2% or 2 s.
     */
    fun check(file: File, expectedDurationSec: Long?): Result = try {
        RandomAccessFile(file, "r").use { check(it, expectedDurationSec) }
    } catch (e: java.io.IOException) {
        Result.Unsupported("unreadable: ${e.message}")
    }

    private fun check(raf: RandomAccessFile, expectedDurationSec: Long?): Result {
        val len = raf.length()
        val ebml = readHeader(raf, 0, len) ?: return unsupported("no EBML header")
        if (ebml.id != ID_EBML || ebml.size == UNKNOWN) return unsupported("no EBML header")
        val ebmlEnd = ebml.headerLen + ebml.size
        if (ebml.size > MAX_BODY || ebmlEnd > len) return unsupported("bad EBML header")
        val ebmlBody = readBytes(raf, ebml.headerLen.toLong(), ebml.size.toInt())
        var docType: String? = null
        forEachChild(ebmlBody) { id, off, size ->
            if (id == ID_DOCTYPE) docType = String(ebmlBody, off, size, Charsets.US_ASCII)
        }
        if (docType != "webm") return unsupported("DocType is $docType")

        val seg = readHeader(raf, ebmlEnd, len) ?: return unsupported("no Segment")
        if (seg.id != ID_SEGMENT) return unsupported("no Segment")
        if (seg.size == UNKNOWN) return unsupported("Segment size unknown")
        val segStart = ebmlEnd + seg.headerLen
        if (segStart + seg.size != len) return unsupported("Segment size does not match file size")

        var durationTicks: Double? = null
        var scale = 1_000_000L
        var tracks = -1
        var audioTracks = 0
        var cuesSeen = false
        var seekCuesPos = -1L
        var infoSeen = false

        var pos = segStart
        while (pos < len) {
            val h = readHeader(raf, pos, len) ?: return unsupported("bad element at $pos")
            if (h.id == ID_CLUSTER) break
            val body = pos + h.headerLen
            if (h.size == UNKNOWN) return unsupported("unknown-size element")
            val end = body + h.size
            if (end > len) return unsupported("element overruns file")
            when (h.id) {
                ID_CUES -> cuesSeen = true
                ID_SEEKHEAD, ID_INFO, ID_TRACKS -> {
                    if (h.size > MAX_BODY) return unsupported("oversized header element")
                    val b = readBytes(raf, body, h.size.toInt())
                    when (h.id) {
                        ID_SEEKHEAD -> readSeekHead(b)?.let { seekCuesPos = it }
                        ID_INFO -> {
                            infoSeen = true
                            forEachChild(b) { id, off, size ->
                                if (id == ID_TIMESTAMP_SCALE) scale = readUInt(b, off, size)
                                if (id == ID_DURATION) durationTicks = readFloat(b, off, size)
                            }
                        }
                        else -> {
                            if (tracks != -1) return unsupported("more than one Tracks element")
                            tracks = 0
                            forEachChild(b) { id, off, size ->
                                if (id == ID_TRACK_ENTRY) {
                                    tracks++
                                    forEachChild(b.copyOfRange(off, off + size)) { cid, coff, csize ->
                                        if (cid == ID_TRACK_TYPE &&
                                            readUInt(b, off + coff, csize) == 2L
                                        ) audioTracks++
                                    }
                                }
                            }
                        }
                    }
                }
            }
            pos = end
        }

        if (!infoSeen) return unsupported("no Info")
        val ticks = durationTicks ?: return unsupported("no Duration")
        if (scale <= 0) return unsupported("bad TimestampScale")
        val durationMs = (ticks * scale / 1_000_000.0)
        if (!(durationMs > 0.0) || durationMs.isInfinite()) return unsupported("no positive Duration")
        if (expectedDurationSec != null) {
            val actualSec = durationMs / 1000.0
            val tolerance = maxOf(expectedDurationSec * 0.02, 2.0)
            if (kotlin.math.abs(actualSec - expectedDurationSec) > tolerance) {
                return unsupported("duration $actualSec s differs from expected $expectedDurationSec s")
            }
        }
        if (tracks != 1 || audioTracks != 1) return unsupported("not exactly one audio track")

        if (!cuesSeen) {
            if (seekCuesPos < 0) return unsupported("no Cues")
            val at = segStart + seekCuesPos
            val h = if (at in segStart until len) readHeader(raf, at, len) else null
            if (h == null || h.id != ID_CUES) return unsupported("SeekHead Cues position invalid")
        }
        return Result.Ok(durationMs.toLong())
    }

    private fun unsupported(reason: String) = Result.Unsupported(reason)

    /** Returns the Cues position from a SeekHead body, or null. */
    private fun readSeekHead(b: ByteArray): Long? {
        var found: Long? = null
        forEachChild(b) { id, off, size ->
            if (id == ID_SEEK) {
                var seekId = -1L
                var seekPos = -1L
                val sub = b.copyOfRange(off, off + size)
                forEachChild(sub) { cid, coff, csize ->
                    if (cid == ID_SEEK_ID) seekId = readUInt(sub, coff, csize)
                    if (cid == ID_SEEK_POS) seekPos = readUInt(sub, coff, csize)
                }
                if (seekId == ID_CUES && seekPos >= 0) found = seekPos
            }
        }
        return found
    }

    /** Calls [action] with (id, bodyOffset, bodySize) for each child; stops at malformed data. */
    private fun forEachChild(b: ByteArray, action: (Long, Int, Int) -> Unit) {
        var p = 0
        while (p < b.size) {
            val h = parseHeader(b, p, b.size) ?: return
            if (h.size == UNKNOWN) return
            val off = p + h.headerLen
            if (h.size > b.size - off) return
            action(h.id, off, h.size.toInt())
            p = off + h.size.toInt()
        }
    }

    private fun readHeader(raf: RandomAccessFile, pos: Long, len: Long): Header? {
        if (pos < 0 || pos >= len) return null
        val n = minOf(16L, len - pos).toInt()
        return parseHeader(readBytes(raf, pos, n), 0, n)
    }

    private fun parseHeader(b: ByteArray, start: Int, limit: Int): Header? {
        if (start >= limit) return null
        val idLen = vintLength(b[start].toInt() and 0xFF)
        if (idLen == 0 || idLen > 4 || start + idLen >= limit) return null
        var id = 0L
        for (i in 0 until idLen) id = (id shl 8) or (b[start + i].toLong() and 0xFF)
        val sPos = start + idLen
        val first = b[sPos].toInt() and 0xFF
        val sLen = vintLength(first)
        if (sLen == 0 || sPos + sLen > limit) return null
        var size = (first and (0xFF shr sLen)).toLong()
        var allOnes = size == (0xFF shr sLen).toLong()
        for (i in 1 until sLen) {
            val v = b[sPos + i].toLong() and 0xFF
            if (v != 0xFFL) allOnes = false
            size = (size shl 8) or v
        }
        return Header(id, if (allOnes) UNKNOWN else size, idLen + sLen)
    }

    /** Length in bytes of a vint from its first byte, or 0 if invalid. */
    private fun vintLength(first: Int): Int =
        if (first == 0) 0 else Integer.numberOfLeadingZeros(first) - 24 + 1

    private fun readBytes(raf: RandomAccessFile, pos: Long, n: Int): ByteArray {
        val out = ByteArray(n)
        raf.seek(pos)
        raf.readFully(out)
        return out
    }

    private fun readUInt(b: ByteArray, off: Int, size: Int): Long {
        var v = 0L
        for (i in 0 until minOf(size, 8)) v = (v shl 8) or (b[off + i].toLong() and 0xFF)
        return v
    }

    private fun readFloat(b: ByteArray, off: Int, size: Int): Double? = when (size) {
        4 -> java.lang.Float.intBitsToFloat(readUInt(b, off, 4).toInt()).toDouble()
        8 -> java.lang.Double.longBitsToDouble(readUInt(b, off, 8))
        else -> null
    }
}
