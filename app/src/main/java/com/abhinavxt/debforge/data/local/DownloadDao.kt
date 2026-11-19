package com.abhinavxt.debforge.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.abhinavxt.debforge.domain.ProviderId
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

    /**
     * Targeted write after a link refresh. Deliberately NOT @Update with an
     * entity copy: the engine's in-memory entity is a stale snapshot (state
     * QUEUED, old bytesDownloaded), and writing it back would clobber the
     * live DOWNLOADING state.
     */
    @Query(
        "UPDATE downloads SET downloadUrl = :url, chunksAllowed = :chunks, filesize = :filesize, " +
            "updatedAt = :now WHERE rdId = :rdId"
    )
    suspend fun updateLink(
        rdId: String,
        url: String,
        chunks: Int,
        filesize: Long,
        now: Long = System.currentTimeMillis()
    )

    /** True if any tracked download (in any state) already targets [path]. */
    @Query("SELECT EXISTS(SELECT 1 FROM downloads WHERE finalFilePath = :path)")
    suspend fun isFinalPathClaimed(path: String): Boolean

    /** Every id the queue knows (any state), so nothing is queued twice. */
    @Query("SELECT rdId FROM downloads")
    suspend fun allIds(): List<String>

    /** Finished downloads, for auto-delete and the storage summary. */
    @Query("SELECT * FROM downloads WHERE state = 'COMPLETED'")
    suspend fun completed(): List<DownloadEntity>

    /** Rows for files found in the download folder (see LocalImporter). */
    @Query("SELECT * FROM downloads WHERE provider = 'LOCAL'")
    suspend fun localRows(): List<DownloadEntity>

    /** Every path a DebForge download uses, in any state, so the scan never imports one. */
    @Query("SELECT finalFilePath FROM downloads WHERE provider != 'LOCAL' AND finalFilePath IS NOT NULL")
    suspend fun claimedFinalPaths(): List<String>

    @Query("SELECT partFilePath FROM downloads WHERE provider != 'LOCAL' AND partFilePath IS NOT NULL")
    suspend fun claimedPartPaths(): List<String>

    /** Imported rows whose tags haven't been read yet (blank title, see LocalMetadataLogic). */
    @Query("SELECT * FROM downloads WHERE provider = 'LOCAL' AND (title IS NULL OR title = '') LIMIT :limit")
    suspend fun localUnchecked(limit: Int): List<DownloadEntity>

    @Query("UPDATE downloads SET title = :title, artist = :artist WHERE rdId = :id AND provider = 'LOCAL'")
    suspend fun setLocalMeta(id: String, title: String, artist: String?)

    @Query("DELETE FROM downloads WHERE provider = 'LOCAL'")
    suspend fun deleteLocal()

    /** An imported file that moved or was renamed: same row (and id), new location. */
    @Query("UPDATE downloads SET finalFilePath = :path, originalLink = :path, filename = :name, updatedAt = :at WHERE rdId = :id AND provider = 'LOCAL'")
    suspend fun moveLocal(id: String, path: String, name: String, at: Long)

    @Query("DELETE FROM downloads WHERE rdId IN (:ids) AND provider = 'LOCAL'")
    suspend fun deleteByIds(ids: List<String>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(rows: List<DownloadEntity>)

    /** Finished downloads, newest first (Media screen). */
    @Query("SELECT * FROM downloads WHERE state = 'COMPLETED' ORDER BY updatedAt DESC")
    fun observeCompleted(): Flow<List<DownloadEntity>>

    /** "Clear finished": forget completed rows (files stay on disk). Imported files stay: a scan would only bring them back. */
    @Query("DELETE FROM downloads WHERE state = 'COMPLETED' AND provider != 'LOCAL'")
    suspend fun deleteCompleted()

    /** "Pause all": queued rows stop waiting. The running one is interrupted by the runner. */
    @Query("UPDATE downloads SET state = 'PAUSED', updatedAt = :now WHERE state = 'QUEUED'")
    suspend fun pauseQueued(now: Long = System.currentTimeMillis()): Int

    @Query("SELECT rdId FROM downloads WHERE state = 'DOWNLOADING'")
    suspend fun downloadingIds(): List<String>

    /** "Resume all": every paused row goes back in the queue. */
    @Query("UPDATE downloads SET state = 'QUEUED', updatedAt = :now WHERE state = 'PAUSED'")
    suspend fun requeuePaused(now: Long = System.currentTimeMillis()): Int

    /** Anything the queue should (still) be working on. */
    @Query("SELECT EXISTS(SELECT 1 FROM downloads WHERE state IN ('QUEUED', 'DOWNLOADING'))")
    suspend fun hasPendingWork(): Boolean

    /** Rough bytes left in the queue — passed to JobScheduler as an estimate. */
    @Query(
        "SELECT COALESCE(SUM(MAX(filesize - bytesDownloaded, 0)), 0) FROM downloads " +
            "WHERE state IN ('QUEUED', 'DOWNLOADING')"
    )
    suspend fun remainingPendingBytes(): Long

    /**
     * Bytes DebForge's downloads occupy: finished, running, and started partial
     * files. Counts what was downloaded if that already exceeds the declared size.
     */
    @Query(
        "SELECT COALESCE(SUM(MAX(filesize, bytesDownloaded)), 0) FROM downloads " +
            " WHERE provider != 'LOCAL' AND (state IN ('COMPLETED', 'DOWNLOADING') OR bytesDownloaded > 0)"
    )
    fun observeReservedBytes(): Flow<Long>

    @Query(
        "SELECT COALESCE(SUM(MAX(filesize, bytesDownloaded)), 0) FROM downloads " +
            " WHERE provider != 'LOCAL' AND (state IN ('COMPLETED', 'DOWNLOADING') OR bytesDownloaded > 0)"
    )
    suspend fun reservedBytes(): Long

    /** The file the queue would start next (oldest QUEUED), or null. */
    @Query("SELECT * FROM downloads WHERE state = 'QUEUED' ORDER BY createdAt ASC LIMIT 1")
    fun observeNextQueued(): Flow<DownloadEntity?>

    @Query("DELETE FROM downloads WHERE rdId = :rdId")
    suspend fun delete(rdId: String)

    /** Which of [ids] (from [provider]) finished downloading. */
    @Query("SELECT rdId FROM downloads WHERE provider = :provider AND state = 'COMPLETED' AND rdId IN (:ids)")
    suspend fun completedIds(provider: ProviderId, ids: List<String>): List<String>

    /** Ids from [provider] that are queued, running, paused or failed. */
    @Query("SELECT rdId FROM downloads WHERE provider = :provider AND state != 'COMPLETED'")
    suspend fun unfinishedIds(provider: ProviderId): List<String>
}
