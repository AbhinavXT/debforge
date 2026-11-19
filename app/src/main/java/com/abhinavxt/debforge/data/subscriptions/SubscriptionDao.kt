package com.abhinavxt.debforge.data.subscriptions

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SubscriptionDao {
    @Query("SELECT * FROM subscriptions ORDER BY title COLLATE NOCASE")
    fun observeAll(): Flow<List<SubscriptionEntity>>

    @Query("SELECT * FROM subscriptions")
    suspend fun all(): List<SubscriptionEntity>

    @Query("SELECT * FROM subscriptions WHERE id = :id")
    suspend fun get(id: Long): SubscriptionEntity?

    @Query("SELECT * FROM subscriptions WHERE url = :url")
    suspend fun getByUrl(url: String): SubscriptionEntity?

    /** Returns -1 if the url is already followed. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(sub: SubscriptionEntity): Long

    @Update
    suspend fun update(sub: SubscriptionEntity)

    @Query("UPDATE subscriptions SET autoDownload = :mode WHERE id = :id")
    suspend fun setAutoDownload(id: Long, mode: AutoDownload)

    @Query("UPDATE subscriptions SET etag = :etag, lastModified = :lastModified, lastChecked = :checkedAt WHERE id = :id")
    suspend fun markChecked(id: Long, etag: String?, lastModified: String?, checkedAt: Long)

    /** Feed items go with it (ON DELETE CASCADE). */
    @Query("DELETE FROM subscriptions WHERE id = :id")
    suspend fun delete(id: Long)

    // --- feed items ---

    @Query("SELECT * FROM feed_items WHERE subId = :subId ORDER BY publishedAt DESC")
    fun observeItems(subId: Long): Flow<List<FeedItemEntity>>

    @Query("SELECT * FROM feed_items WHERE subId = :subId")
    suspend fun items(subId: Long): List<FeedItemEntity>

    @Query("SELECT itemKey FROM feed_items WHERE subId = :subId")
    suspend fun itemKeys(subId: Long): List<String>

    @Query(
        "SELECT f.subId AS subId, f.itemKey AS itemKey, f.title AS title, f.mediaUrl AS mediaUrl, " +
            "f.publishedAt AS publishedAt, f.durationSec AS durationSec, f.thumbUrl AS thumbUrl, " +
            "s.title AS subTitle, s.kind AS kind " +
            "FROM feed_items f INNER JOIN subscriptions s ON s.id = f.subId " +
            "WHERE f.isNew = 1 ORDER BY f.publishedAt DESC LIMIT :limit"
    )
    fun observeNew(limit: Int = 30): Flow<List<NewFeedItem>>

    @Query("SELECT COUNT(*) FROM feed_items WHERE isNew = 1")
    fun observeUnseenCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM feed_items WHERE subId = :subId AND isNew = 1")
    fun observeUnseenCount(subId: Long): Flow<Int>

    /** Insert new items; existing ones (same subId + itemKey) keep their isNew / queued flags. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertItems(items: List<FeedItemEntity>)

    @Update
    suspend fun updateItems(items: List<FeedItemEntity>)

    @Query("UPDATE feed_items SET isNew = 0 WHERE subId = :subId AND itemKey = :itemKey")
    suspend fun markSeen(subId: Long, itemKey: String)

    @Query("UPDATE feed_items SET isNew = 0 WHERE subId = :subId")
    suspend fun markAllSeen(subId: Long)

    @Query("UPDATE feed_items SET isNew = 0")
    suspend fun markEverythingSeen()

    @Query("UPDATE feed_items SET queued = 1 WHERE subId = :subId AND itemKey = :itemKey")
    suspend fun markQueued(subId: Long, itemKey: String)

    /** Keep the newest [keep] items of a subscription so podcasts with hundreds of episodes stay small. */
    @Query(
        "DELETE FROM feed_items WHERE subId = :subId AND itemKey NOT IN " +
            "(SELECT itemKey FROM feed_items WHERE subId = :subId ORDER BY publishedAt DESC LIMIT :keep)"
    )
    suspend fun trimItems(subId: Long, keep: Int)
}
