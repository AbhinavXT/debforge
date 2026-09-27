package com.abhinavxt.debforge.download.watch

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.abhinavxt.debforge.domain.ProviderId
import kotlinx.coroutines.flow.Flow

/**
 * "Download this when it's ready": a job on the service (torrent still
 * fetching, etc.) whose files should be queued automatically once available.
 */
@Entity(tableName = "watches", primaryKeys = ["provider", "jobRef"])
data class WatchEntity(
    val provider: ProviderId,
    val jobRef: String,
    val name: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Dao
interface WatchDao {
    @Query("SELECT * FROM watches ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<WatchEntity>>

    @Query("SELECT * FROM watches ORDER BY createdAt ASC")
    suspend fun getAll(): List<WatchEntity>

    @Query("SELECT COUNT(*) FROM watches")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(watch: WatchEntity)

    @Query("DELETE FROM watches WHERE provider = :provider AND jobRef = :jobRef")
    suspend fun delete(provider: ProviderId, jobRef: String)
}
