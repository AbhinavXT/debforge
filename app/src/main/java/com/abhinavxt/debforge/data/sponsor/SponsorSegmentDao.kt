package com.abhinavxt.debforge.data.sponsor

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface SponsorSegmentDao {
    @Query("SELECT * FROM sponsor_segments WHERE videoId = :videoId")
    suspend fun get(videoId: String): SponsorSegmentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SponsorSegmentEntity)

    @Query("DELETE FROM sponsor_segments WHERE fetchedAt < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)
}
