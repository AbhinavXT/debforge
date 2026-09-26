package com.abhinavxt.debforge.data.metadata

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * Cached TMDB lookup for one title group (see ReleaseInfo.groupKey). Misses
 * are cached too ([found] = false) so a title TMDB doesn't know isn't looked
 * up again on every scroll.
 */
@Entity(tableName = "media_meta")
data class MediaMetaEntity(
    @PrimaryKey val cacheKey: String,
    val found: Boolean,
    val title: String?,
    val year: Int?,
    val posterPath: String?,
    val backdropPath: String?,
    val overview: String?,
    val fetchedAt: Long
)

@Dao
interface MediaMetaDao {
    @Query("SELECT * FROM media_meta WHERE cacheKey = :key")
    suspend fun get(key: String): MediaMetaEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entity: MediaMetaEntity)

    @Query("DELETE FROM media_meta")
    suspend fun clear()
}
