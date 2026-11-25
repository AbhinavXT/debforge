package com.abhinavxt.debforge.data.backup

import com.abhinavxt.debforge.data.bookmarks.BOOKMARK_LIMIT
import com.abhinavxt.debforge.data.bookmarks.BOOKMARK_NAME_MAX
import com.abhinavxt.debforge.data.bookmarks.BookmarkEntity
import com.abhinavxt.debforge.data.bookmarks.isLocalBookmarkKey

/**
 * Bookmarks as the "bookmarks" section of a backup:
 * `{"v":1,"items":[{"id":itemId,"pos":ms,"n":name,"at":ms}]}`. Pure; the decoder never throws (broken rows are skipped).
 */
object BookmarksBackup {

    fun toJson(rows: List<BookmarkEntity>): Map<String, Any?> = linkedMapOf(
        "v" to 1,
        "items" to rows.filterNot { isLocalBookmarkKey(it.itemId) }.map {
            linkedMapOf<String, Any?>("id" to it.itemId, "pos" to it.positionMs, "n" to it.name, "at" to it.createdAt)
        }
    )

    fun fromJson(value: Any?): List<BookmarkEntity> {
        val m = value as? Map<*, *> ?: return emptyList()
        return (m["items"] as? List<*>).orEmpty().take(BOOKMARK_LIMIT).mapNotNull { e ->
            val o = e as? Map<*, *> ?: return@mapNotNull null
            fun num(k: String) = (o[k] as? Number)?.toDouble()?.takeIf { it.isFinite() }?.toLong()
            val id = (o["id"] as? String)?.takeIf { it.isNotEmpty() && it.length <= 512 && !isLocalBookmarkKey(it) }
                ?: return@mapNotNull null
            BookmarkEntity(
                itemId = id,
                positionMs = num("pos")?.takeIf { it >= 0 } ?: return@mapNotNull null,
                name = (o["n"] as? String)?.trim()?.takeIf { it.isNotEmpty() && it.length <= BOOKMARK_NAME_MAX },
                createdAt = (num("at") ?: 0L).coerceAtLeast(0)
            )
        }.distinctBy { it.itemId to it.positionMs }
    }

    /** The rows of [incoming] whose (itemId, positionMs) is not in [existing], at most [room] of them. */
    fun toWrite(existing: Set<Pair<String, Long>>, incoming: List<BookmarkEntity>, room: Int): List<BookmarkEntity> =
        incoming.filter { (it.itemId to it.positionMs) !in existing }.take(room.coerceAtLeast(0))
}
