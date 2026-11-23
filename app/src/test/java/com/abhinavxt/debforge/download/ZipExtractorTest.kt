package com.abhinavxt.debforge.download

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ZipExtractorTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun zip(vararg entries: Pair<String, String>) = ByteArrayOutputStream().also { bos ->
        ZipOutputStream(bos).use { z -> entries.forEach { (n, c) -> z.putNextEntry(ZipEntry(n)); z.write(c.toByteArray()); z.closeEntry() } }
    }.toByteArray()

    @Test fun extractsFilesAndNestedFolders() {
        val root = File(tmp.root, "out")
        val r = ZipExtractor.extract(ByteArrayInputStream(zip("a.txt" to "hi", "d/e/b.txt" to "yo", "dir/" to "")), ZipExtractor.FileSink(root))
        assertEquals(ZipExtractor.Result(2, 0), r)
        assertEquals("hi", File(root, "a.txt").readText())
        assertEquals("yo", File(root, "d/e/b.txt").readText())
    }

    @Test fun zipSlipEntriesAreSkippedAndCounted() {
        val root = File(tmp.root, "out")
        val data = zip("../evil.txt" to "x", "/abs.txt" to "x", "a/../../evil2.txt" to "x", "C:\\win.txt" to "x", "..\\evil3.txt" to "x", "ok.txt" to "fine")
        val r = ZipExtractor.extract(ByteArrayInputStream(data), ZipExtractor.FileSink(root))
        assertEquals(ZipExtractor.Result(1, 5), r)
        assertEquals(listOf("ok.txt"), root.walk().filter { it.isFile }.map { it.name }.toList())
        assertFalse(File(tmp.root, "evil.txt").exists())
        assertFalse(File("/abs.txt").exists())
    }

    @Test fun safeNameNormalises() {
        assertEquals("a/b", ZipExtractor.safeName("./a//b"))
        assertNull(ZipExtractor.safeName(".."))
        assertNull(ZipExtractor.safeName(""))
    }

    @Test fun nameCollisionGetsNumberedFolder() {
        val archive = File(tmp.root, "Show.ZIP").path
        assertEquals(File(tmp.root, "Show").path, ZipExtractor.targetFolder(archive) { File(it).exists() })
        File(tmp.root, "Show").mkdir()
        assertEquals(File(tmp.root, "Show (2)").path, ZipExtractor.targetFolder(archive) { File(it).exists() })
        File(tmp.root, "Show (2)").mkdir()
        assertEquals(File(tmp.root, "Show (3)").path, ZipExtractor.targetFolder(archive) { File(it).exists() })
    }

    @Test fun safTargetStaysInSameFolder() {
        val p = SafPaths.join("content://x/tree/a", "Movies/pack.zip")
        assertEquals(SafPaths.join("content://x/tree/a", "Movies/pack (2)"), ZipExtractor.targetFolder(p) { it.endsWith("/pack") })
    }

    @Test fun cancelRemovesPartialOutput() {
        val root = File(tmp.root, "out")
        var n = 0
        try {
            ZipExtractor.extract(ByteArrayInputStream(zip("a" to "1", "b" to "2", "c" to "3")), ZipExtractor.FileSink(root), check = { if (++n > 5) throw IllegalStateException("cancel") })
            fail()
        } catch (e: IllegalStateException) {
            assertFalse(root.exists())
        }
    }

    @Test fun corruptArchiveRemovesPartialOutput() {
        val root = File(tmp.root, "out")
        fun noise(seed: Long) = java.util.Base64.getEncoder().encodeToString(java.util.Random(seed).let { r -> ByteArray(8000).also(r::nextBytes) })
        val good = zip("a.txt" to noise(1), "b.txt" to noise(2))
        val broken = good.copyOf(good.size - 3000) // cut inside the second entry
        try {
            ZipExtractor.extract(ByteArrayInputStream(broken), ZipExtractor.FileSink(root))
            fail()
        } catch (e: IOException) {
            assertFalse(root.exists())
        }
    }

    @Test fun diskFullBecomesNoSpaceAndCleansUp() {
        val sink = object : ZipExtractor.Sink {
            var discarded = false
            override fun open(rel: String) = object : java.io.OutputStream() {
                override fun write(b: Int) = throw IOException("write failed: ENOSPC (No space left on device)")
            }
            override fun discard() { discarded = true }
        }
        try {
            ZipExtractor.extract(ByteArrayInputStream(zip("a" to "1")), sink)
            fail()
        } catch (e: ZipExtractor.NoSpaceException) {
            assertTrue(sink.discarded)
        }
    }

    @Test fun repeatedNameKeepsLaterEntryOnlyAndCountsOnce() {
        val root = File(tmp.root, "out")
        val r = ZipExtractor.extract(ByteArrayInputStream(zip("a.txt" to "x".repeat(100), "./a.txt" to "y".repeat(10))), ZipExtractor.FileSink(root))
        assertEquals(ZipExtractor.Result(1, 0), r)
        assertEquals("y".repeat(10), File(root, "a.txt").readText())
    }

    @Test fun stopsAndCleansUpWhenOutputWouldExceedFreeSpaceLessMargin() {
        val root = File(tmp.root, "out")
        val data = zip("a.txt" to "x".repeat(100))
        try {
            ZipExtractor.extract(ByteArrayInputStream(data), ZipExtractor.FileSink(root), freeBytes = { ZipExtractor.SAFETY_MARGIN + 50 })
            fail()
        } catch (e: ZipExtractor.NoSpaceException) {
            assertFalse(root.exists())
        }
        // Fits, or free space unknown: unchanged behaviour.
        ZipExtractor.extract(ByteArrayInputStream(data), ZipExtractor.FileSink(root), freeBytes = { ZipExtractor.SAFETY_MARGIN + 100 })
        ZipExtractor.extract(ByteArrayInputStream(data), ZipExtractor.FileSink(root), freeBytes = { null })
    }
}
