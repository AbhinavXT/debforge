package com.abhinavxt.debforge.download.watch

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.abhinavxt.debforge.domain.DownloadItem
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
    val createdAt: Long = System.currentTimeMillis(),
    /** Only tell the user when it is ready; don't queue the files. */
    @ColumnInfo(defaultValue = "0") val notifyOnly: Boolean = false,
    /** Newline-joined file paths to download; null = all. */
    val selected: String? = null
)

/** What a check does with one watch. */
enum class WatchStep { DROP, WAIT, NOTIFY, ENQUEUE }

fun watchStep(w: WatchEntity, signedIn: Set<ProviderId>, ready: Boolean): WatchStep = when {
    w.provider !in signedIn -> WatchStep.DROP
    !ready -> WatchStep.WAIT
    w.notifyOnly -> WatchStep.NOTIFY
    else -> WatchStep.ENQUEUE
}

/** Whether a job the user did not ask to auto-download gets a notify-only watch. */
fun shouldWatchNotifyOnly(setting: Boolean, autoDownload: Boolean, jobRef: String?, ready: Boolean): Boolean =
    setting && !autoDownload && jobRef != null && !ready

/** The provider a "finished on the service" tap should switch to; null when the extras are missing or it is signed out. */
fun cloudReadyTarget(provider: String?, jobRef: String?, signedIn: Set<ProviderId>): ProviderId? {
    if (jobRef.isNullOrEmpty()) return null
    return signedIn.firstOrNull { it.name == provider }
}

/** Keeps all [files] when [selected] is null; else those whose path ends with a selected path (the service may prefix the torrent folder). */
fun filterSelected(files: List<DownloadItem>, selected: String?): List<DownloadItem> {
    if (selected == null) return files
    val paths = selected.split('\n').filter { it.isNotEmpty() }
    return files.filter { f -> paths.any { f.filename == it || f.filename.endsWith("/$it") } }
}

/** Background check period: 15 min, or 60 once the newest watch is over 6 h old. */
fun watchPeriodMinutes(newestCreatedAt: Long, now: Long): Long =
    if (now - newestCreatedAt > 6L * 60 * 60 * 1000) 60 else 15

@Dao
interface WatchDao {
    @Query("SELECT * FROM watches ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<WatchEntity>>

    @Query("SELECT * FROM watches ORDER BY createdAt ASC")
    suspend fun getAll(): List<WatchEntity>

    @Query("SELECT MAX(createdAt) FROM watches")
    suspend fun newestCreatedAt(): Long?

    @Query("SELECT COUNT(*) FROM watches")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(watch: WatchEntity)

    /** Never overwrites: an auto-download watch for the same job stays as it is. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(watch: WatchEntity)

    @Query("DELETE FROM watches WHERE notifyOnly = 1")
    suspend fun deleteNotifyOnly()

    @Query("DELETE FROM watches WHERE provider = :provider AND jobRef = :jobRef")
    suspend fun delete(provider: ProviderId, jobRef: String)
}
