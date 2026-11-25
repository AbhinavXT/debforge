package com.abhinavxt.debforge.data.backup

import com.abhinavxt.debforge.data.bookmarks.BOOKMARK_LIMIT
import com.abhinavxt.debforge.data.bookmarks.BookmarkEntity
import com.abhinavxt.debforge.data.bookmarks.bookmarkKey
import com.abhinavxt.debforge.data.bookmarks.bookmarkLabel
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BookmarksTest {
    private val adapter = Moshi.Builder().build().adapter<Map<String, Any?>>(
        Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
    )

    private fun viaJson(section: Map<String, Any?>): Any? = adapter.fromJson(adapter.toJson(mapOf("x" to section)))!!["x"]

    private fun b(id: String, pos: Long, name: String? = null) = BookmarkEntity(0, id, pos, name, 5)

    @Test fun youtubeAudioSharesTheVideoKey() {
        assertEquals("ytstream:abc", bookmarkKey("ytaudio:abc"))
        assertEquals("ytstream:abc", bookmarkKey("ytstream:abc"))
        assertEquals("dl-1", bookmarkKey("dl-1"))
    }

    @Test fun labelIsNameOrTime() {
        assertEquals("1:05", bookmarkLabel(null, 65_000))
        assertEquals("1:01:05", bookmarkLabel(" ", 3_665_000))
        assertEquals("Intro", bookmarkLabel("Intro", 65_000))
    }

    @Test fun roundTripDropsLocalFiles() {
        val rows = listOf(b("x", 1000, "A"), b("local:abc", 2000), b("y", 0))
        val back = BookmarksBackup.fromJson(viaJson(BookmarksBackup.toJson(rows)))
        assertEquals(listOf("x" to 1000L, "y" to 0L), back.map { it.itemId to it.positionMs })
        assertEquals("A", back[0].name)
        assertNull(back[1].name)
    }

    @Test fun decoderSkipsBrokenRowsAndCapsStrings() {
        val ok = mapOf("id" to "x", "pos" to 5, "n" to "n")
        val items = listOf(
            ok, "junk", mapOf("id" to "", "pos" to 1), mapOf("id" to "x"), mapOf("id" to "y", "pos" to -1),
            mapOf("id" to "local:q", "pos" to 1), mapOf("id" to "z".repeat(513), "pos" to 1),
            mapOf("id" to "w", "pos" to 2, "n" to "n".repeat(101)), ok
        )
        val back = BookmarksBackup.fromJson(mapOf("items" to items))
        assertEquals(listOf("x" to 5L, "w" to 2L), back.map { it.itemId to it.positionMs })
        assertNull(back[1].name)
        assertEquals(emptyList<BookmarkEntity>(), BookmarksBackup.fromJson("nope"))
        assertEquals(emptyList<BookmarkEntity>(), BookmarksBackup.fromJson(null))
    }

    @Test fun decoderCapsRowCount() {
        val items = (0 until BOOKMARK_LIMIT + 50).map { mapOf("id" to "x", "pos" to it) }
        assertEquals(BOOKMARK_LIMIT, BookmarksBackup.fromJson(mapOf("items" to items)).size)
    }

    @Test fun mergeWritesOnlyNewPositionsWithinRoom() {
        val have = setOf("x" to 1000L)
        val incoming = listOf(b("x", 1000), b("x", 2000), b("y", 1000), b("y", 3000))
        assertEquals(listOf("x" to 2000L, "y" to 1000L), BookmarksBackup.toWrite(have, incoming, 2).map { it.itemId to it.positionMs })
        assertEquals(emptyList<BookmarkEntity>(), BookmarksBackup.toWrite(have, incoming, -1))
    }
}
