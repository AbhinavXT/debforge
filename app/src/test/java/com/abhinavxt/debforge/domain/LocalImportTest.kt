package com.abhinavxt.debforge.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalImportTest {
    private val root = "/a/b"

    private fun f(path: String, size: Long = 100, name: String = path.substringAfterLast('/'), mtime: Long = 1L) =
        ScannedFile(path, name, size, mtime)

    private fun row(path: String, size: Long = 100, ext: String = "mp3", mtime: Long = 1L) =
        LocalRow(localId(path), path, size, ext, mtime)

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

    @Test fun differentModifiedTimeIsNotMove() {
        val r = row("/a/b/x.mp3")
        val d = diffScan(listOf(f("/a/b/y.mp3", mtime = 2L)), listOf(r), emptySet(), root)
        assertTrue(d.moves.isEmpty())
        assertEquals(1, d.inserts.size)
        assertEquals(listOf(r.id), d.removes)
    }

    @Test fun changedSizeOrModifiedTimeIsUpdate() {
        val a = row("/a/b/x.mp3")
        val b = row("/a/b/y.mp3")
        val c = row("/a/b/z.mp3")
        val d = diffScan(
            listOf(f("/a/b/x.mp3", size = 200), f("/a/b/y.mp3", mtime = 9L), f("/a/b/z.mp3")),
            listOf(a, b, c), emptySet(), root
        )
        assertEquals(setOf(a.id, b.id), d.updates.keys)
        assertEquals(200L, d.updates[a.id]!!.size)
        assertEquals(9L, d.updates[b.id]!!.lastModified)
        assertTrue(d.inserts.isEmpty() && d.removes.isEmpty() && d.moves.isEmpty())
    }

    @Test fun newFileAtAMovedRowsOldPathGetsAFallbackId() {
        // x.mp3 moved to y.mp3 (row keeps the id made from x.mp3); a new, different file now sits at x.mp3.
        val moved = LocalRow(localId("/a/b/x.mp3"), "/a/b/y.mp3", 100, "mp3", 1L)
        val newcomer = f("/a/b/x.mp3", size = 555, mtime = 7L)
        val d = diffScan(listOf(f("/a/b/y.mp3"), newcomer), listOf(moved), emptySet(), root)
        assertEquals(listOf(newcomer), d.inserts)
        val id = d.insertIds.getValue("/a/b/x.mp3")
        assertNotEquals(moved.id, id)
        assertTrue(id.matches(Regex("local:[0-9a-f]{40}")))
        // deterministic
        assertEquals(id, diffScan(listOf(f("/a/b/y.mp3"), newcomer), listOf(moved), emptySet(), root).insertIds["/a/b/x.mp3"])
    }

    @Test fun freeIdIsKeptAndRemovedRowsIdsAreReusable() {
        val d = diffScan(listOf(f("/a/b/x.mp3")), emptyList(), emptySet(), root)
        assertEquals(localId("/a/b/x.mp3"), d.insertIds["/a/b/x.mp3"])
        // the row holding the id is being removed (outside root), so the id is free again
        val stale = LocalRow(localId("/a/b/x.mp3"), "/z/x.mp3", 100, "mp3", 1L)
        val d2 = diffScan(listOf(f("/a/b/x.mp3")), listOf(stale), emptySet(), root)
        assertEquals(localId("/a/b/x.mp3"), d2.insertIds["/a/b/x.mp3"])
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

    @Test fun safRootMatchesOnItsHashBoundary() {
        val root = "saf:content://x/tree/a#"
        assertEquals(true, isUnderRoot("saf:content://x/tree/a#Shows/ep.mkv", root))
        assertEquals(true, isUnderRoot("saf:content://x/tree/a#ep.mkv", root))
        assertEquals(false, isUnderRoot("saf:content://x/tree/b#ep.mkv", root))
    }

    @Test fun scannedFileBecomesACompletedLocalRow() {
        val f = ScannedFile("/m/a.mkv", "a.mkv", 123, 456)
        val e = f.toLocalEntity()
        assertEquals(localId("/m/a.mkv"), e.id)
        assertEquals(ProviderId.LOCAL, e.provider)
        assertEquals(DownloadState.COMPLETED, e.state)
        assertEquals("/m/a.mkv", e.finalFilePath)
        assertEquals("/m/a.mkv", e.sourceRef)
        assertEquals(456L, e.updatedAt)
        assertEquals(123L, e.filesize)
        assertEquals("", e.title)
    }
}
