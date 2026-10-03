package com.abhinavxt.debforge.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MkvChaptersTest {

    // --- a tiny EBML writer ---------------------------------------------------

    private fun idBytes(id: Long): ByteArray {
        var n = 4
        while (n > 1 && (id shr ((n - 1) * 8)) == 0L) n--
        return ByteArray(n) { i -> (id shr ((n - 1 - i) * 8)).toByte() }
    }

    private fun size(len: Int): ByteArray =
        if (len < 0x7F) byteArrayOf((0x80 or len).toByte())
        else ByteArray(8) { i -> if (i == 0) 0x01 else (len.toLong() shr ((7 - i) * 8)).toByte() }

    private fun el(id: Long, vararg children: ByteArray): ByteArray {
        val body = children.fold(ByteArray(0)) { acc, b -> acc + b }
        return idBytes(id) + size(body.size) + body
    }

    private fun uint(id: Long, v: Long) = el(id, ByteArray(8) { i -> (v shr ((7 - i) * 8)).toByte() })
    private fun str(id: Long, s: String) = el(id, s.toByteArray())

    private fun atom(startMs: Long, title: String, endMs: Long? = null, hidden: Boolean = false) = el(
        0xB6,
        uint(0x91, startMs * 1_000_000),
        *(listOfNotNull(
            endMs?.let { uint(0x92, it * 1_000_000) },
            if (hidden) uint(0x98, 1) else null,
            el(0x80, str(0x85, title), str(0x437C, "eng"))
        ).toTypedArray())
    )

    private fun chapters(vararg editions: ByteArray) = el(0x1043A770, *editions)
    private fun edition(vararg atoms: ByteArray, default: Boolean = false) =
        el(0x45B9, *(listOfNotNull(if (default) uint(0x45DB, 1) else null).toTypedArray() + atoms))

    private val ebmlHeader = el(0x1A45DFA3, str(0x4282, "matroska"))
    private val info = el(0x1549A966, uint(0x2AD7B1, 1_000_000))
    private val cluster = el(0x1F43B675, uint(0xE7, 0))

    private fun file(vararg segmentChildren: ByteArray) = ebmlHeader + el(0x18538067, *segmentChildren)

    private fun reader(bytes: ByteArray, log: MutableList<Pair<Long, Int>>? = null): (Long, Int) -> ByteArray? = { off, len ->
        log?.add(off to len)
        if (off >= bytes.size) null else bytes.copyOfRange(off.toInt(), minOf(bytes.size, (off + len).toInt()))
    }

    // --- tests ----------------------------------------------------------------

    @Test fun readsChaptersNearTheStart() {
        val mkv = file(info, chapters(edition(atom(0, "Prologue"), atom(90_000, "Opening"), atom(180_000, "Part A"))), cluster)
        val list = MkvChapters.read(reader(mkv))
        assertEquals(listOf(0L, 90_000L, 180_000L), list.map { it.startMs })
        assertEquals("Opening", list[1].title)
    }

    @Test fun followsSeekHeadToChaptersPastTheHead() {
        val padding = el(0xEC, ByteArray(MkvChapters.HEAD_BYTES)) // Void
        val chaps = chapters(edition(atom(0, "Intro", endMs = 60_000), atom(60_000, "Episode")))
        // SeekPosition is relative to the Segment's data; the SeekHead itself has a fixed size.
        fun seekHead(pos: Long) = el(0x114D9B74, el(0x4DBB, el(0x53AB, idBytes(0x1043A770)), uint(0x53AC, pos)))
        val headLen = seekHead(0).size
        val chaptersPos = (headLen + info.size + padding.size + cluster.size).toLong()
        val mkv = file(seekHead(chaptersPos), info, padding, cluster, chaps)
        val log = mutableListOf<Pair<Long, Int>>()
        val list = MkvChapters.read(reader(mkv, log))
        assertEquals(listOf("Intro", "Episode"), list.map { it.title })
        assertEquals(60_000L, list[0].endMs)
        assertTrue("small reads only", log.drop(1).all { it.second <= chaps.size })
    }

    @Test fun skipsHiddenChaptersAndUsesTheDefaultEdition() {
        val mkv = file(
            chapters(
                edition(atom(0, "Other cut")),
                edition(atom(0, "Recap"), atom(30_000, "Secret", hidden = true), atom(60_000, "Story"), default = true)
            )
        )
        assertEquals(listOf("Recap", "Story"), MkvChapters.read(reader(mkv)).map { it.title })
    }

    @Test fun noChaptersBeforeTheVideo() {
        assertEquals(emptyList<MkvChapters.Chapter>(), MkvChapters.read(reader(file(info, cluster))))
    }

    @Test fun notMatroska() {
        val mp4ish = ByteArray(4096) { (it % 251).toByte() }
        assertEquals(emptyList<MkvChapters.Chapter>(), MkvChapters.read(reader(mp4ish)))
    }

    @Test fun truncatedFileGivesNothingRatherThanCrashing() {
        val mkv = file(info, chapters(edition(atom(0, "Opening"), atom(90_000, "Part A"))))
        val cut = mkv.copyOf(mkv.size - 7)
        MkvChapters.read(reader(cut)) // no exception
    }
}
