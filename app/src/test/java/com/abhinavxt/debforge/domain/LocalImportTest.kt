package com.abhinavxt.debforge.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalImportTest {
    private val root = "/a/b"

    private fun f(path: String, size: Long = 100, name: String = path.substringAfterLast('/')) =
        ScannedFile(path, name, size, 1L)

    private fun row(path: String, size: Long = 100, ext: String = "mp3") =
        LocalRow(localId(path), path, size, ext)

    @Test fun classifiesKinds() {
        assertEquals(FileKind.Audio, classifyScanned("a.mp3", 1))
        assertEquals(FileKind.Video, classifyScanned("a.MKV", 1))
        assertNull(classifyScanned("a.txt", 1))
    }

    @Test fun skipsTempHiddenTrashedAndEmpty() {
        for (n in listOf("a.mp4.part", "a.m4a.part.audio", "a.mp4.part.mux", "a.mp3.tmp", ".a.mp3", ".trashed-1-a.mp3")) {
            assertNull(n, classifyScanned(n, 10))
        }
        assertNull(classifyScanned("a.mp3", 0))
        assertNull(classifyScanned("a.mp3", -1))
    }

    @Test fun claimedAndSkippedIgnored() {
        val d = diffScan(listOf(f("/a/b/x.mp3"), f("/a/b/y.mp3"), f("/a/b/z.txt")), emptyList(), setOf("/a/b/x.mp3"), root)
        assertEquals(listOf("/a/b/y.mp3"), d.inserts.map { it.path })
    }

    @Test fun unchangedProducesNoOps() {
        val d = diffScan(listOf(f("/a/b/x.mp3")), listOf(row("/a/b/x.mp3")), emptySet(), root)
        assertTrue(d.inserts.isEmpty() && d.removes.isEmpty() && d.moves.isEmpty())
    }

    @Test fun vanishedRemoved() {
        val r = row("/a/b/x.mp3")
        val d = diffScan(emptyList(), listOf(r), emptySet(), root)
        assertEquals(listOf(r.id), d.removes)
    }

    @Test fun uniqueRenameIsMove() {
        val r = row("/a/b/x.mp3")
        val n = f("/a/b/y.mp3")
        val d = diffScan(listOf(n), listOf(r), emptySet(), root)
        assertEquals(mapOf(r.id to n), d.moves)
        assertTrue(d.inserts.isEmpty() && d.removes.isEmpty())
    }

    @Test fun ambiguousRenameIsRemoveAndInsert() {
        val r1 = row("/a/b/x.mp3")
        val r2 = row("/a/b/w.mp3")
        val n = f("/a/b/y.mp3")
        val d = diffScan(listOf(n), listOf(r1, r2), emptySet(), root)
        assertTrue(d.moves.isEmpty())
        assertEquals(setOf(r1.id, r2.id), d.removes.toSet())
        assertEquals(listOf(n), d.inserts)
        val d2 = diffScan(listOf(n, f("/a/b/z.mp3")), listOf(r1), emptySet(), root)
        assertTrue(d2.moves.isEmpty())
        assertEquals(listOf(r1.id), d2.removes)
        assertEquals(2, d2.inserts.size)
    }

    @Test fun differentSizeIsNotMove() {
        val r = row("/a/b/x.mp3")
        val d = diffScan(listOf(f("/a/b/y.mp3", size = 5)), listOf(r), emptySet(), root)
        assertTrue(d.moves.isEmpty())
        assertEquals(1, d.inserts.size)
        assertEquals(listOf(r.id), d.removes)
    }

    @Test fun rowsOutsideRootRemoved() {
        val bc = row("/a/bc/x.mp3")
        val other = row("/z/x.mp3")
        val inside = row("/a/b/c/x.mp3")
        val d = diffScan(listOf(f("/a/b/c/x.mp3")), listOf(bc, other, inside), emptySet(), root)
        assertEquals(setOf(bc.id, other.id), d.removes.toSet())
        assertTrue(d.moves.isEmpty() && d.inserts.isEmpty())
    }

    @Test fun localIdStable() {
        assertEquals(localId("/a/b/x.mp3"), localId("/a/b/x.mp3"))
        assertNotEquals(localId("/a/b/x.mp3"), localId("/a/b/y.mp3"))
        assertTrue(localId("/p").matches(Regex("local:[0-9a-f]{40}")))
    }
}
