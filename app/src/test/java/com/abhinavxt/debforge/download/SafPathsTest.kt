package com.abhinavxt.debforge.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SafPathsTest {

    private val tree = "content://com.android.externalstorage.documents/tree/1A2B-3C4D%3AMovies"

    @Test fun joinAndParse() {
        val p = SafPaths.join(tree, "Shows/The Bear/Season 02/ep #1.mkv")
        assertTrue(SafPaths.isSaf(p))
        val parsed = SafPaths.parse(p)!!
        assertEquals(tree, parsed.treeUri)
        assertEquals(listOf("Shows", "The Bear", "Season 02"), parsed.dirs)
        assertEquals("ep #1.mkv", parsed.name)
    }

    @Test fun rootLevelFile() {
        val parsed = SafPaths.parse(SafPaths.join(tree, "movie.mkv"))!!
        assertEquals(emptyList<String>(), parsed.dirs)
        assertEquals("movie.mkv", parsed.name)
    }

    @Test fun partToFinalRename() {
        val part = SafPaths.join(tree, "Movies/x.mkv") + ".part"
        assertEquals(SafPaths.join(tree, "Movies/x.mkv"), SafPaths.withName(part, "x.mkv"))
    }

    @Test fun notSaf() {
        assertFalse(SafPaths.isSaf("/storage/emulated/0/Movies/x.mkv"))
        assertNull(SafPaths.parse("/storage/emulated/0/Movies/x.mkv"))
        assertNull(SafPaths.parse("saf:no-hash-here"))
        assertTrue(SafPaths.isTreeUri(tree))
    }

    @Test fun labels() {
        assertEquals("SD card (1A2B-3C4D)/Movies", SafPaths.displayName(tree))
        assertEquals(
            "Internal storage/Download/DebForge",
            SafPaths.displayName("content://com.android.externalstorage.documents/tree/primary%3ADownload%2FDebForge")
        )
        assertEquals("Internal storage", SafPaths.displayName("content://com.android.externalstorage.documents/tree/primary%3A"))
    }

    @Test fun volumeIds() {
        assertEquals("1A2B-3C4D", SafPaths.volumeId(tree))
        assertEquals("primary", SafPaths.volumeId("content://com.android.externalstorage.documents/tree/primary%3ADownload%2FDebForge"))
        assertEquals("primary", SafPaths.volumeId("content://com.android.externalstorage.documents/tree/primary%3A"))
        // Cloud providers: free space unknown.
        assertNull(SafPaths.volumeId("content://com.google.android.apps.docs.storage/tree/acc%3D1%3Bdoc%3Dabc"))
        assertNull(SafPaths.volumeId("content://com.android.externalstorage.documents/tree/"))
    }
}
