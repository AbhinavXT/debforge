package com.abhinavxt.debforge.download

import java.io.File
import java.nio.ByteBuffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Fmp4HeaderTest {

    private val files = mutableListOf<File>()

    @After fun cleanup() { files.forEach { it.delete() } }

    private class W {
        val buf: ByteBuffer = ByteBuffer.allocate(1 shl 20)
        val marks = HashMap<String, Int>()
        val pos get() = buf.position()
        fun u8(v: Int) { buf.put(v.toByte()) }
        fun u16(v: Int) { buf.putShort(v.toShort()) }
        fun u32(v: Long) { buf.putInt(v.toInt()) }
        fun u64(v: Long) { buf.putLong(v) }
        fun zeros(n: Int) { repeat(n) { buf.put(0) } }
        fun raw(b: ByteArray) { buf.put(b) }
        fun mark(name: String) { marks[name] = pos }
        fun bytes() = buf.array().copyOf(pos)
        fun box(type: String, large: Boolean = false, body: W.() -> Unit) {
            val start = pos
            if (large) { u32(1); raw(type.toByteArray()); u64(0) } else { u32(0); raw(type.toByteArray()) }
            body()
            val size = (pos - start).toLong()
            if (large) buf.putLong(start + 8, size) else buf.putInt(start, size.toInt())
        }
        /** Writes a duration field of the box version's width and marks it. */
        fun dur(name: String, ver: Int, v: Long) { mark(name); if (ver == 0) u32(v) else u64(v) }
    }

    private class Spec(
        val ver: Int = 0,
        val movieTs: Long = 1000,
        val mediaTs: Long = 44100,
        val sidxTs: Long = 44100,
        val refDur: Long = 441000,
        val refs: Int = 3,
        val mehd: Boolean = true,
        val handlers: List<String> = listOf("soun"),
        val moovAfterMdat: Boolean = false,
        val sidx: Boolean = true,
        val sidxSizeDelta: Long = 0,
        val large: Boolean = false,
        val refType: Int = 0,
        val initialDur: Long = 0,
        val truncate: Int = 0,
        val emptyMvhdLast: Boolean = false,
        val firstOffset: Long = 0,
    )

    private fun moov(w: W, s: Spec) = w.box("moov", s.large) {
        if (!s.emptyMvhdLast) box("mvhd") {
            u8(s.ver); zeros(3)
            if (s.ver == 0) { u32(0); u32(0); u32(s.movieTs) } else { u64(0); u64(0); u32(s.movieTs) }
            dur("mvhd", s.ver, s.initialDur)
            zeros(80)
        }
        for ((i, h) in s.handlers.withIndex()) box("trak") {
            box("tkhd") {
                u8(s.ver); zeros(3)
                if (s.ver == 0) { u32(0); u32(0); u32(1); u32(0) } else { u64(0); u64(0); u32(1); u32(0) }
                dur("tkhd$i", s.ver, s.initialDur)
                zeros(60)
            }
            box("mdia") {
                box("mdhd") {
                    u8(s.ver); zeros(3)
                    if (s.ver == 0) { u32(0); u32(0); u32(s.mediaTs) } else { u64(0); u64(0); u32(s.mediaTs) }
                    dur("mdhd$i", s.ver, s.initialDur)
                    zeros(4)
                }
                box("hdlr") { u32(0); u32(0); raw(h.toByteArray()); zeros(12); u8(0) }
            }
        }
        box("mvex") {
            if (s.mehd) box("mehd") { u8(s.ver); zeros(3); dur("mehd", s.ver, s.initialDur) }
            box("trex") { zeros(24) }
        }
        if (s.emptyMvhdLast) box("mvhd") { }
    }

    private class Built(val bytes: ByteArray, val marks: Map<String, Int>, val s: Spec)

    private fun build(s: Spec): Built {
        val frag = W()
        val fragSizes = ArrayList<Long>()
        repeat(s.refs) {
            val st = frag.pos
            frag.box("moof") { box("mfhd") { zeros(8) } }
            frag.box("mdat", s.large) { zeros(100) }
            fragSizes += (frag.pos - st).toLong()
        }
        val w = W()
        w.box("ftyp") { raw("M4A ".toByteArray()); u32(0); raw("M4A ".toByteArray()) }
        if (!s.moovAfterMdat) moov(w, s)
        if (s.sidx) w.box("sidx") {
            u8(s.ver); zeros(3); u32(1); u32(s.sidxTs)
            if (s.ver == 0) { u32(0); u32(0) } else { u64(0); u64(s.firstOffset) }
            u16(0); u16(s.refs)
            for (sz in fragSizes) { u32((s.refType.toLong() shl 31) or (sz + s.sidxSizeDelta)); u32(s.refDur); u32(0) }
        }
        val fragStart = w.pos
        w.raw(frag.bytes())
        if (s.moovAfterMdat) moov(w, s)
        // fragment marks are irrelevant; marks only in header
        val all = w.bytes()
        val out = if (s.truncate > 0) all.copyOf(all.size - s.truncate) else all
        check(fragStart > 0)
        return Built(out, w.marks, s)
    }

    private fun file(b: ByteArray): File = File.createTempFile("fmp4", ".m4a").also { it.writeBytes(b); files += it }

    private fun ok(r: Fmp4Header.Result) = (r as? Fmp4Header.Result.Ok)?.plan ?: error("expected Ok, got $r")
    private fun unsupported(b: Built, expected: Long? = null): String =
        (Fmp4Header.check(file(b.bytes), expected) as? Fmp4Header.Result.Unsupported)?.reason ?: error("expected Unsupported")

    private fun readField(b: ByteArray, off: Int, ver: Int) =
        if (ver == 0) ByteBuffer.wrap(b).getInt(off).toLong() and 0xFFFFFFFFL else ByteBuffer.wrap(b).getLong(off)

    private fun validPatchesExactly(ver: Int, mehd: Boolean, large: Boolean) {
        val b = build(Spec(ver = ver, mehd = mehd, large = large))
        val f = file(b.bytes)
        val before = f.readBytes()
        val plan = ok(Fmp4Header.check(f, 30))
        assertEquals(30.0, plan.durationSec, 1e-9)
        Fmp4Header.patch(f, plan)
        val after = f.readBytes()
        assertEquals(before.size, after.size)
        val expected = mapOf("mvhd" to 30000L, "tkhd0" to 30000L, "mdhd0" to 1323000L, "mehd" to 30000L)
        val allowed = HashSet<Int>()
        val width = if (ver == 0) 4 else 8
        for ((name, v) in expected) {
            val off = b.marks[name]
            if (name == "mehd" && !mehd) { assertEquals(null, off); continue }
            assertEquals(name, v, readField(after, off!!, ver))
            for (i in 0 until width) allowed += off + i
        }
        val changed = before.indices.filter { before[it] != after[it] }
        assertTrue(changed.isNotEmpty())
        assertTrue("changed bytes outside duration fields: $changed", allowed.containsAll(changed))
        // idempotent
        Fmp4Header.patch(f, plan)
        assertArrayEquals(after, f.readBytes())
    }

    @Test fun validVersion0() = validPatchesExactly(0, mehd = true, large = false)
    @Test fun validVersion1() = validPatchesExactly(1, mehd = true, large = false)
    @Test fun validWithoutMehd() = validPatchesExactly(0, mehd = false, large = false)
    @Test fun validWithLargesizeBoxes() = validPatchesExactly(1, mehd = true, large = true)

    @Test fun sizeZeroLastBoxReachesEndOfFile() {
        val b = build(Spec())
        // turn the final mdat's size into 0 (to end of file); sizes are unchanged in meaning
        val bytes = b.bytes.copyOf()
        val mdatStart = bytes.size - 108
        ByteBuffer.wrap(bytes).putInt(mdatStart, 0)
        ok(Fmp4Header.check(file(bytes), null))
    }

    @Test fun neverLowersLargerExistingValues() {
        val b = build(Spec(initialDur = 99_999_999))
        val f = file(b.bytes)
        Fmp4Header.patch(f, ok(Fmp4Header.check(f, null)))
        assertArrayEquals(b.bytes, f.readBytes())
    }

    @Test fun expectedDurationWithinToleranceIsAccepted() {
        ok(Fmp4Header.check(file(build(Spec()).bytes), 31)) // within 2 s
        ok(Fmp4Header.check(file(build(Spec(refDur = 441000 * 100)).bytes), 3010)) // within 2%
    }

    @Test fun expectedDurationMismatch() {
        assertTrue(unsupported(build(Spec()), 60).contains("expected"))
    }

    @Test fun noSidx() { assertTrue(unsupported(build(Spec(sidx = false))).contains("sidx")) }

    @Test fun moovAfterMdat() { assertTrue(unsupported(build(Spec(moovAfterMdat = true))).contains("moov")) }

    @Test fun sidxSumMismatch() {
        assertTrue(unsupported(build(Spec(sidxSizeDelta = 1))).contains("cover"))
    }

    @Test fun twoTraks() { assertTrue(unsupported(build(Spec(handlers = listOf("soun", "soun")))).contains("trak")) }

    @Test fun videoTrak() { assertTrue(unsupported(build(Spec(handlers = listOf("vide")))).contains("audio")) }

    @Test fun hierarchicalSidxReference() {
        assertTrue(unsupported(build(Spec(refType = 1))).contains("media"))
    }

    @Test fun version0Overflow() {
        // 3 x 2e9 ticks at timescale 1 -> 6e12 s * 1000 overflows 32 bits
        val b = build(Spec(sidxTs = 1, refDur = 2_000_000_000))
        assertTrue(unsupported(b).contains("version 0"))
        // the same durations are fine with version 1 boxes
        ok(Fmp4Header.check(file(build(Spec(ver = 1, sidxTs = 1, refDur = 2_000_000_000)).bytes), null))
    }

    @Test fun truncatedFile() {
        unsupported(build(Spec(truncate = 50)))
        unsupported(build(Spec(truncate = 1)))
    }

    @Test fun truncatedInsideHeaders() {
        val b = build(Spec())
        for (n in listOf(0, 4, 20, 60, 150)) {
            val f = file(b.bytes.copyOf(n))
            assertTrue(Fmp4Header.check(f, null) is Fmp4Header.Result.Unsupported)
        }
    }

    @Test fun roundsUp() {
        // 1 tick at sidx ts 44100 across 3 refs = 3/44100 s -> mvhd ts 1000 rounds up to 1
        val b = build(Spec(refDur = 1))
        val f = file(b.bytes)
        Fmp4Header.patch(f, ok(Fmp4Header.check(f, null)))
        assertEquals(1L, readField(f.readBytes(), b.marks["mvhd"]!!, 0))
    }

    private fun assertUnsupported(bytes: ByteArray) {
        assertTrue(Fmp4Header.check(file(bytes), null) is Fmp4Header.Result.Unsupported)
    }

    // ftyp is 20 bytes, so moov starts at 20 and its first child (mvhd) at 28 in build() output.
    private fun withBox(at: Int, size: Long, type: String, large: Long? = null): ByteArray {
        val bytes = build(Spec()).bytes.copyOf()
        val bb = ByteBuffer.wrap(bytes)
        bb.putInt(at, size.toInt())
        for (i in 0 until 4) bytes[at + 4 + i] = type[i].code.toByte()
        if (large != null) bb.putLong(at + 8, large)
        return bytes
    }

    @Test(timeout = 2000) fun hugeLargesizeAtTopLevel() {
        assertUnsupported(withBox(20, 1, "free", Long.MAX_VALUE - 5))
        assertUnsupported(withBox(20, 1, "free", Long.MAX_VALUE))
    }

    @Test(timeout = 2000) fun hugeLargesizeInsideMoov() {
        assertUnsupported(withBox(28, 1, "free", Long.MAX_VALUE - 5))
        assertUnsupported(withBox(28, 1, "free", Long.MAX_VALUE))
    }

    @Test(timeout = 2000) fun sizeSmallerThanHeader() {
        assertUnsupported(withBox(20, 4, "free"))
        assertUnsupported(withBox(28, 4, "free"))
        assertUnsupported(withBox(28, 1, "free", 3))
    }

    @Test(timeout = 2000) fun emptyMvhdAtEndOfMoov() {
        assertUnsupported(build(Spec(emptyMvhdLast = true)).bytes)
    }

    @Test(timeout = 2000) fun wrappingSidxFirstOffset() {
        assertUnsupported(build(Spec(ver = 1, firstOffset = Long.MAX_VALUE)).bytes)
        assertUnsupported(build(Spec(ver = 1, firstOffset = Long.MAX_VALUE - 100)).bytes)
    }
}
