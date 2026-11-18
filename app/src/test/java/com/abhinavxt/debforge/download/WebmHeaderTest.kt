package com.abhinavxt.debforge.download

import com.abhinavxt.debforge.download.WebmHeader.Result
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebmHeaderTest {
    private val files = mutableListOf<File>()

    @After fun cleanup() = files.forEach { it.delete() }

    private fun idBytes(id: Long): ByteArray {
        var n = 1
        while (n < 4 && (id shr (8 * n)) != 0L) n++
        return ByteArray(n) { (id shr (8 * (n - 1 - it))).toByte() }
    }

    private fun size(n: Long): ByteArray {
        var len = 1
        while (n >= (1L shl (7 * len)) - 1) len++
        val v = n or (1L shl (7 * len))
        return ByteArray(len) { (v shr (8 * (len - 1 - it))).toByte() }
    }

    private fun el(id: Long, body: ByteArray) = idBytes(id) + size(body.size.toLong()) + body
    private fun uint(id: Long, v: Long) = el(id, byteArrayOf(*(0 until 4).map { (v shr (8 * (3 - it))).toByte() }.toByteArray()))
    private fun f64(id: Long, v: Double) = el(id, java.nio.ByteBuffer.allocate(8).putDouble(v).array())
    private fun f32(id: Long, v: Float) = el(id, java.nio.ByteBuffer.allocate(4).putFloat(v).array())

    private fun ebml(docType: String = "webm") = el(0x1A45DFA3, el(0x4282, docType.toByteArray()))
    private fun info(duration: ByteArray?, scale: Long? = null) =
        el(0x1549A966, (scale?.let { uint(0x2AD7B1, it) } ?: ByteArray(0)) + (duration ?: ByteArray(0)))
    private fun track(type: Int) = el(0xAE, el(0x83, byteArrayOf(type.toByte())))
    private fun tracks(vararg types: Int) = el(0x1654AE6B, types.fold(ByteArray(0)) { a, t -> a + track(t) })
    private fun cues() = el(0x1C53BB6B, el(0xBB, byteArrayOf(1, 2, 3)))
    private fun cluster() = el(0x1F43B675, ByteArray(100) { 7 })
    private fun seekHead(cuesPos: Long) = el(
        0x114D9B74,
        el(0x4DBB, el(0x53AB, idBytes(0x1C53BB6B)) + uint(0x53AC, cuesPos)),
    )

    private fun segment(body: ByteArray, unknown: Boolean = false) =
        idBytes(0x18538067) + (if (unknown) byteArrayOf(0x01, -1, -1, -1, -1, -1, -1, -1) else size(body.size.toLong())) + body

    private fun write(bytes: ByteArray): File {
        val f = File.createTempFile("webm", ".webm")
        files += f
        f.writeBytes(bytes)
        return f
    }

    private fun good(duration: ByteArray? = f64(0x4489, 120000.0), scale: Long? = null, tr: ByteArray = tracks(2)) =
        ebml() + segment(info(duration, scale) + tr + cues() + cluster() + cluster())

    private fun ok(r: Result) = (r as Result.Ok).durationMs
    private fun bad(r: Result) = (r as Result.Unsupported).reason

    @Test fun validWith8ByteDuration() {
        assertEquals(120_000L, ok(WebmHeader.check(write(good()), 120)))
    }

    @Test fun validWith4ByteDuration() {
        assertEquals(90_000L, ok(WebmHeader.check(write(good(f32(0x4489, 90000f))), null)))
    }

    @Test fun customTimestampScale() {
        // 10_000 ticks of 1 ms... scale 1_000 ns means 1000 ticks per ms
        assertEquals(60_000L, ok(WebmHeader.check(write(good(f64(0x4489, 60_000_000.0), 1000)), 60)))
    }

    @Test fun noDuration() {
        assertTrue(bad(WebmHeader.check(write(good(null)), null)).contains("Duration"))
    }

    @Test fun noCues() {
        val b = ebml() + segment(info(f64(0x4489, 1000.0)) + tracks(2) + cluster())
        assertTrue(bad(WebmHeader.check(write(b), null)).contains("Cues"))
    }

    @Test fun cuesReachableOnlyViaSeekHeadPastClusters() {
        val i = info(f64(0x4489, 5000.0))
        val t = tracks(2)
        val sh0 = seekHead(0)
        val cl = cluster() + cluster()
        val pos = (sh0.size + i.size + t.size + cl.size).toLong()
        val b = ebml() + segment(seekHead(pos) + i + t + cl + cues())
        assertEquals(5000L, ok(WebmHeader.check(write(b), null)))
    }

    @Test fun seekHeadPointingAtGarbageIsRejected() {
        val i = info(f64(0x4489, 5000.0))
        val b = ebml() + segment(seekHead(3) + i + tracks(2) + cluster())
        assertTrue(bad(WebmHeader.check(write(b), null)).contains("SeekHead"))
    }

    @Test fun unknownSizeSegment() {
        val b = ebml() + segment(info(f64(0x4489, 1000.0)) + tracks(2) + cues() + cluster(), unknown = true)
        assertTrue(bad(WebmHeader.check(write(b), null)).contains("unknown"))
    }

    @Test fun truncatedFile() {
        val full = good()
        assertTrue(bad(WebmHeader.check(write(full.copyOf(full.size - 10)), null)).contains("size"))
    }

    @Test fun trailingBytes() {
        assertTrue(bad(WebmHeader.check(write(good() + byteArrayOf(0, 0)), null)).contains("size"))
    }

    @Test fun twoTracks() {
        assertTrue(bad(WebmHeader.check(write(good(tr = tracks(2, 2))), null)).contains("audio track"))
    }

    @Test fun videoTrack() {
        assertTrue(bad(WebmHeader.check(write(good(tr = tracks(1))), null)).contains("audio track"))
    }

    @Test fun wrongDocType() {
        val b = ebml("matroska") + segment(info(f64(0x4489, 1000.0)) + tracks(2) + cues() + cluster())
        assertTrue(bad(WebmHeader.check(write(b), null)).contains("DocType"))
    }

    @Test fun expectedDurationMismatch() {
        val f = write(good())
        assertTrue(bad(WebmHeader.check(f, 200)).contains("expected"))
        // within 2 s for a short track
        assertEquals(120_000L, ok(WebmHeader.check(f, 121)))
    }

    @Test fun emptyOrGarbageFile() {
        assertTrue(WebmHeader.check(write(ByteArray(0)), null) is Result.Unsupported)
        assertTrue(WebmHeader.check(write(ByteArray(50) { 0x55 }), null) is Result.Unsupported)
    }
}
