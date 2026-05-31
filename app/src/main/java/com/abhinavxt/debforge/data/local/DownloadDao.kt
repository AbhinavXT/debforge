package com.abhinavxt.debforge.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.abhinavxt.debforge.domain.DownloadState
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(download: DownloadEntity)

    @Update
    suspend fun update(download: DownloadEntity)

    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE state = :state ORDER BY createdAt ASC")
    fun observeByState(state: DownloadState): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE rdId = :rdId")
    suspend fun getById(rdId: String): DownloadEntity?

    @Query("SELECT * FROM downloads WHERE rdId = :rdId")
    fun observeById(rdId: String): Flow<DownloadEntity?>

    /**
     * Next item the engine should pick up. Single active download model:
     * oldest QUEUED item wins. Returns null when the queue is empty.
     */
    @Query("SELECT * FROM downloads WHERE state = :state ORDER BY createdAt ASC LIMIT 1")
    suspend fun nextInState(state: DownloadState = DownloadState.QUEUED): DownloadEntity?

    @Query("UPDATE downloads SET state = :state, updatedAt = :now WHERE rdId = :rdId")
    suspend fun updateState(
        rdId: String,
        state: DownloadState,
        now: Long = System.currentTimeMillis()
    )

    @Query(
        "UPDATE downloads SET state = :state, errorMessage = :message, updatedAt = :now WHERE rdId = :rdId"
    )
    suspend fun setFailed(
        rdId: String,
        message: String?,
        state: DownloadState = DownloadState.FAILED,
        now: Long = System.currentTimeMillis()
    )

    /** Re-queue any rows stuck in DOWNLOADING (e.g. after process death) so the engine resumes them. */
    @Query("UPDATE downloads SET state = :to WHERE state = :from")
    suspend fun requeueOrphans(
        from: DownloadState = DownloadState.DOWNLOADING,
        to: DownloadState = DownloadState.QUEUED
    )

    @Query(
        "UPDATE downloads SET bytesDownloaded = :bytes, updatedAt = :now WHERE rdId = :rdId"
    )
    suspend fun updateBytes(
        rdId: String,
        bytes: Long,
        now: Long = System.currentTimeMillis()
    )

    @Query("DELETE FROM downloads WHERE rdId = :rdId")
    suspend fun delete(rdId: String)
}
