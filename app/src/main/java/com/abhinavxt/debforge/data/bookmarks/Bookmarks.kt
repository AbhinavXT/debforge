package com.abhinavxt.debforge.data.bookmarks

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * A saved spot in a long video or audio item. [itemId] is the playback key (see [bookmarkKey]); no foreign key,
 * so bookmarks survive a re-download like `playback_positions`.
 */
@Entity(tableName = "bookmarks", indices = [Index("itemId")])
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val itemId: String,
    val positionMs: Long,
    val name: String?,
    val createdAt: Long
)

const val BOOKMARK_LIMIT = 5_000
const val BOOKMARK_NAME_MAX = 100

/** Audio and video of the same YouTube item share bookmarks: the audio key maps to the video's position key. */
fun bookmarkKey(playbackKey: String): String =
    if (playbackKey.startsWith("ytaudio:")) "ytstream:" + playbackKey.removePrefix("ytaudio:") else playbackKey

/** Imported files only mean something on the phone that has them (same rule as history). */
fun isLocalBookmarkKey(itemId: String) = itemId.startsWith("local:")

/** The name when set, else the time. */
fun bookmarkLabel(name: String?, positionMs: Long): String = name?.takeIf { it.isNotBlank() } ?: formatBookmarkTime(positionMs)

fun formatBookmarkTime(positionMs: Long): String {
    val s = positionMs.coerceAtLeast(0) / 1000
    val h = s / 3600
    return if (h > 0) "%d:%02d:%02d".format(h, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

@Dao
abstract class BookmarkDao {
    @Query("SELECT * FROM bookmarks WHERE itemId = :itemId ORDER BY positionMs, id")
    abstract fun observe(itemId: String): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks")
    abstract suspend fun all(): List<BookmarkEntity>

    @Query("INSERT INTO bookmarks (itemId, positionMs, name, createdAt) VALUES (:itemId, :positionMs, :name, :createdAt)")
    abstract suspend fun insert(itemId: String, positionMs: Long, name: String?, createdAt: Long): Long

    @Query("UPDATE bookmarks SET name = :name WHERE id = :id")
    abstract suspend fun rename(id: Long, name: String?)

    /** Undo of a delete: puts the row back under its old id. */
    @androidx.room.Upsert
    abstract suspend fun restore(row: BookmarkEntity)

    @Query("DELETE FROM bookmarks WHERE id = :id")
    abstract suspend fun delete(id: Long)

    @Query("SELECT COUNT(*) FROM bookmarks")
    abstract suspend fun count(): Int

    /** For restore: writes the rows whose (itemId, positionMs) is new. Returns how many were written. */
    @Transaction
    open suspend fun mergeFromBackup(rows: List<BookmarkEntity>): Int {
        val todo = com.abhinavxt.debforge.data.backup.BookmarksBackup.toWrite(
            all().mapTo(HashSet()) { it.itemId to it.positionMs }, rows, BOOKMARK_LIMIT - count()
        )
        todo.forEach { insert(it.itemId, it.positionMs, it.name, it.createdAt) }
        return todo.size
    }
}
