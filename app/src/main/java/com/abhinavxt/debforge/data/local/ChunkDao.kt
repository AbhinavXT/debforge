package com.abhinavxt.debforge.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ChunkDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(chunks: List<ChunkEntity>)

    @Query("SELECT * FROM chunks WHERE downloadId = :downloadId ORDER BY `index` ASC")
    suspend fun getForDownload(downloadId: String): List<ChunkEntity>

    @Query("SELECT * FROM chunks WHERE downloadId = :downloadId ORDER BY `index` ASC")
    fun observeForDownload(downloadId: String): Flow<List<ChunkEntity>>

    /** Throttled progress write (~every 2-4 MB and on pause/cancel). */
    @Query(
        "UPDATE chunks SET bytesWritten = :bytesWritten WHERE downloadId = :downloadId AND `index` = :index"
    )
    suspend fun updateProgress(downloadId: String, index: Int, bytesWritten: Long)

    @Query(
        "UPDATE chunks SET bytesWritten = :bytesWritten, complete = 1 WHERE downloadId = :downloadId AND `index` = :index"
    )
    suspend fun markComplete(downloadId: String, index: Int, bytesWritten: Long)

    @Query("SELECT COALESCE(SUM(bytesWritten), 0) FROM chunks WHERE downloadId = :downloadId")
    suspend fun totalBytesWritten(downloadId: String): Long

    @Query("SELECT COUNT(*) FROM chunks WHERE downloadId = :downloadId AND complete = 0")
    suspend fun remainingChunkCount(downloadId: String): Int

    @Query("DELETE FROM chunks WHERE downloadId = :downloadId")
    suspend fun deleteForDownload(downloadId: String)
}
