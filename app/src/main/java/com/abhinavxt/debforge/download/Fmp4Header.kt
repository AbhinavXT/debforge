package com.abhinavxt.debforge.download

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Validates and patches the header of a downloaded fragmented MP4 audio file.
 *
 * YouTube's progressive DASH audio (itag 139/140) is already a complete fragmented MP4, but its
 * header durations are often 0, which players read. Remuxing it through MediaMuxer is slow and
 * not resumable, so when the file is provably well formed we only overwrite those duration fields
 * in place. Pure Kotlin, no android.* imports. I/O is O(moov + sidx), never O(file size).
 */
object Fmp4Header {

    /** One big-endian unsigned field to write: [size] is 4 or 8 bytes at absolute [offset]. */
    data class Field(val name: String, val offset: Long, val size: Int, val value: Long)

    /** The in-place writes a valid file needs. */
    data class Plan(val fields: List<Field>, val durationSec: Double)

    sealed interface Result {
        data class Ok(val plan: Plan) : Result
        data class Unsupported(val reason: String) : Result
    }

    private class Unsup(msg: String) : Exception(msg)

    private const val MAX_MOOV = 32L * 1024 * 1024
    private const val U32_MAX = 0xFFFFFFFFL

    private class BoxHeader(val type: String, val start: Long, val headerSize: Int, val size: Long) {
        val payloadStart get() = start + headerSize
        val end get() = start + size
    }

    /**
     * Returns [Result.Ok] with the patch plan only if [file] is a complete, well-formed
     * single-audio-track fragmented MP4 whose sidx accounts for every byte of the file.
     * [expectedDurationSec], when given, must match the sidx duration within 2% or 2 s.
     */
    fun check(file: File, expectedDurationSec: Long?): Result = try {
        RandomAccessFile(file, "r").use { raf -> Result.Ok(doCheck(raf.channel, raf.length(), expectedDurationSec)) }
    } catch (e: Unsup) {
        Result.Unsupported(e.message ?: "unsupported")
    } catch (e: java.io.IOException) {
        Result.Unsupported("read failed: ${e.message}")
    }

    /**
     * Writes the plan's fields in place, only where the current value is 0 or smaller than the
     * planned one. Never changes the file length; idempotent.
     */
    fun patch(file: File, plan: Plan) {
        RandomAccessFile(file, "rw").use { raf ->
            val ch = raf.channel
            for (f in plan.fields) {
                val cur = readUInt(ch, f.offset, f.size)
                if (cur == 0L || cur < f.value) writeUInt(ch, f.offset, f.size, f.value)
            }
            ch.force(true)
        }
    }

    private fun doCheck(ch: FileChannel, len: Long, expected: Long?): Plan {
        var pos = 0L
        var first = true
        var moov: BoxHeader? = null
        var sidx: BoxHeader? = null
        while (pos < len) {
            val h = readHeader(ch, pos, len)
            if (first && h.type != "ftyp") throw Unsup("ftyp is not first")
            first = false
            if (h.type == "moof" || h.type == "mdat") break
            when (h.type) {
                "moov" -> { if (moov != null) throw Unsup("multiple moov"); moov = h }
                "sidx" -> { if (sidx != null) throw Unsup("multiple sidx"); sidx = h }
            }
            pos = h.end
        }
        if (first) throw Unsup("empty file")
        if (moov == null) throw Unsup("moov missing before fragments")
        if (sidx == null) throw Unsup("no sidx before fragments")

        // sidx
        val sp = sidx.size - sidx.headerSize
        if (sp < 32 || sp > MAX_MOOV) throw Unsup("bad sidx size")
        val sb = readBuf(ch, sidx.payloadStart, sp.toInt())
        val sver = sb.get(0).toInt() and 0xFF
        if (sver > 1) throw Unsup("sidx version $sver")
        val sTs = sb.getInt(8).toLong() and U32_MAX
        var p = 12
        val firstOffset: Long
        if (sver == 0) { firstOffset = sb.getInt(p + 4).toLong() and U32_MAX; p += 8 }
        else { firstOffset = sb.getLong(p + 8); p += 16 }
        if (firstOffset < 0) throw Unsup("bad sidx first_offset")
        val count = sb.getShort(p + 2).toInt() and 0xFFFF
        p += 4
        if (sp != (p + 12L * count)) throw Unsup("sidx length mismatch")
        if (count == 0) throw Unsup("empty sidx")
        if (sTs == 0L) throw Unsup("sidx timescale 0")
        var sizeSum = 0L
        var durSum = 0L
        for (i in 0 until count) {
            val w = sb.getInt(p).toLong() and U32_MAX
            if ((w ushr 31) != 0L) throw Unsup("sidx reference is not media")
            sizeSum += w and 0x7FFFFFFFL
            durSum += sb.getInt(p + 4).toLong() and U32_MAX
            p += 12
        }
        if (sidx.end + firstOffset + sizeSum != len) throw Unsup("sidx does not cover the file")
        if (durSum <= 0) throw Unsup("sidx duration 0")
        val sec = durSum.toDouble() / sTs
        if (expected != null && Math.abs(sec - expected) > maxOf(2.0, expected * 0.02)) {
            throw Unsup("duration $sec s differs from expected $expected s")
        }

        // moov
        val mp = moov.size - moov.headerSize
        if (mp > MAX_MOOV) throw Unsup("moov too large")
        val base = moov.payloadStart
        val mb = readBuf(ch, base, mp.toInt())
        var mvhd: BoxHeader? = null
        var mehd: BoxHeader? = null
        var hasMvex = false
        var trak: BoxHeader? = null
        for (c in children(mb, 0, mp.toInt(), base)) {
            when (c.type) {
                "mvhd" -> { if (mvhd != null) throw Unsup("multiple mvhd"); mvhd = c }
                "mvex" -> {
                    hasMvex = true
                    for (m in children(mb, (c.payloadStart - base).toInt(), (c.end - base).toInt(), base)) {
                        if (m.type == "mehd") mehd = m
                    }
                }
                "trak" -> { if (trak != null) throw Unsup("more than one trak"); trak = c }
            }
        }
        if (!hasMvex) throw Unsup("no mvex")
        if (mvhd == null) throw Unsup("no mvhd")
        if (trak == null) throw Unsup("no trak")

        var tkhd: BoxHeader? = null
        var mdia: BoxHeader? = null
        for (c in children(mb, (trak.payloadStart - base).toInt(), (trak.end - base).toInt(), base)) {
            if (c.type == "tkhd") tkhd = c
            if (c.type == "mdia") mdia = c
        }
        if (tkhd == null || mdia == null) throw Unsup("trak lacks tkhd or mdia")
        var mdhd: BoxHeader? = null
        var hdlr: BoxHeader? = null
        for (c in children(mb, (mdia.payloadStart - base).toInt(), (mdia.end - base).toInt(), base)) {
            if (c.type == "mdhd") mdhd = c
            if (c.type == "hdlr") hdlr = c
        }
        if (mdhd == null || hdlr == null) throw Unsup("mdia lacks mdhd or hdlr")
        val hp = (hdlr.payloadStart - base).toInt()
        if (hdlr.size - hdlr.headerSize < 12) throw Unsup("short hdlr")
        val handler = String(ByteArray(4) { mb.get(hp + 8 + it) }, Charsets.ISO_8859_1)
        if (handler != "soun") throw Unsup("track is not audio ($handler)")

        val fields = ArrayList<Field>()
        // mvhd
        val mv = ver(mb, mvhd, base)
        val mvOff = (mvhd.payloadStart - base).toInt()
        val mvNeed = if (mv == 0) 20 else 32
        if (mvhd.size - mvhd.headerSize < mvNeed) throw Unsup("short mvhd")
        val movieTs = if (mv == 0) mb.getInt(mvOff + 12).toLong() and U32_MAX else mb.getInt(mvOff + 20).toLong() and U32_MAX
        if (movieTs == 0L) throw Unsup("movie timescale 0")
        fields += field("mvhd", mvhd, mv, if (mv == 0) 16 else 24, durSum, sTs, movieTs, base)
        // tkhd
        val tv = ver(mb, tkhd, base)
        if (tkhd.size - tkhd.headerSize < (if (tv == 0) 24 else 36)) throw Unsup("short tkhd")
        fields += field("tkhd", tkhd, tv, if (tv == 0) 20 else 28, durSum, sTs, movieTs, base)
        // mdhd
        val dv = ver(mb, mdhd, base)
        val dOff = (mdhd.payloadStart - base).toInt()
        if (mdhd.size - mdhd.headerSize < (if (dv == 0) 20 else 32)) throw Unsup("short mdhd")
        val mediaTs = if (dv == 0) mb.getInt(dOff + 12).toLong() and U32_MAX else mb.getInt(dOff + 20).toLong() and U32_MAX
        if (mediaTs == 0L) throw Unsup("media timescale 0")
        fields += field("mdhd", mdhd, dv, if (dv == 0) 16 else 24, durSum, sTs, mediaTs, base)
        // mehd
        if (mehd != null) {
            val ev = ver(mb, mehd, base)
            if (mehd.size - mehd.headerSize < (if (ev == 0) 8 else 12)) throw Unsup("short mehd")
            fields += field("mehd", mehd, ev, 4, durSum, sTs, movieTs, base)
        }
        return Plan(fields, sec)
    }

    private fun ver(mb: ByteBuffer, b: BoxHeader, base: Long): Int {
        val v = mb.get((b.payloadStart - base).toInt()).toInt() and 0xFF
        if (v > 1) throw Unsup("${b.type} version $v")
        return v
    }

    /** value = ceil(durSum * ts / sTs), checked against the box version's width. */
    private fun field(name: String, b: BoxHeader, version: Int, rel: Int, durSum: Long, sTs: Long, ts: Long, base: Long): Field {
        val v = try {
            val n = Math.multiplyExact(durSum, ts)
            n / sTs + if (n % sTs != 0L) 1 else 0
        } catch (e: ArithmeticException) {
            throw Unsup("$name duration overflows")
        }
        if (v < 0) throw Unsup("$name duration overflows")
        if (version == 0 && v > U32_MAX) throw Unsup("$name duration does not fit version 0")
        return Field(name, b.payloadStart + rel, if (version == 0) 4 else 8, v)
    }

    /** Child boxes in an in-memory buffer between [from] and [to]; [base] maps to file offsets. */
    private fun children(mb: ByteBuffer, from: Int, to: Int, base: Long): List<BoxHeader> {
        val out = ArrayList<BoxHeader>()
        var p = from
        while (p < to) {
            if (to - p < 8) throw Unsup("truncated child box")
            var size = mb.getInt(p).toLong() and U32_MAX
            val type = String(ByteArray(4) { mb.get(p + 4 + it) }, Charsets.ISO_8859_1)
            var hs = 8
            if (size == 1L) {
                if (to - p < 16) throw Unsup("truncated child box")
                size = mb.getLong(p + 8); hs = 16
            } else if (size == 0L) size = (to - p).toLong()
            if (size < hs || p + size > to) throw Unsup("bad child box size ($type)")
            out += BoxHeader(type, base + p, hs, size)
            p += size.toInt()
        }
        return out
    }

    private fun readHeader(ch: FileChannel, pos: Long, len: Long): BoxHeader {
        if (len - pos < 8) throw Unsup("truncated box header")
        val b = readBuf(ch, pos, 8)
        var size = b.getInt(0).toLong() and U32_MAX
        val type = String(ByteArray(4) { b.get(4 + it) }, Charsets.ISO_8859_1)
        var hs = 8
        if (size == 1L) {
            if (len - pos < 16) throw Unsup("truncated box header")
            size = readBuf(ch, pos + 8, 8).getLong(0); hs = 16
        } else if (size == 0L) size = len - pos
        if (size < hs || size < 0 || pos + size > len) throw Unsup("box $type overruns the file")
        return BoxHeader(type, pos, hs, size)
    }

    private fun readBuf(ch: FileChannel, pos: Long, n: Int): ByteBuffer {
        val b = ByteBuffer.allocate(n)
        var p = pos
        while (b.hasRemaining()) {
            val r = ch.read(b, p)
            if (r < 0) throw Unsup("unexpected end of file")
            p += r
        }
        b.flip()
        return b
    }

    private fun readUInt(ch: FileChannel, pos: Long, size: Int): Long {
        val b = readBuf(ch, pos, size)
        return if (size == 4) b.getInt(0).toLong() and U32_MAX else b.getLong(0)
    }

    private fun writeUInt(ch: FileChannel, pos: Long, size: Int, v: Long) {
        val b = ByteBuffer.allocate(size)
        if (size == 4) b.putInt(v.toInt()) else b.putLong(v)
        b.flip()
        var p = pos
        while (b.hasRemaining()) p += ch.write(b, p)
    }
}
