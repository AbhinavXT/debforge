package com.abhinavxt.debforge.data.history

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.abhinavxt.debforge.data.prefs.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One played video or audio track. Same key space as `playback_positions` and
 * AudioResume (download id, provider item id, `ytstream:<id>`, `ytaudio:<id>`),
 * one row per item so a replay moves it to the top. [kind], [origin] and
 * [provider] are plain strings (see [HistoryKind], [HistoryOrigin]; provider is
 * a ProviderId name): persisted values, never rename them.
 */
@Entity(tableName = "play_history", indices = [Index("playedAt")])
data class HistoryEntity(
    @PrimaryKey val itemId: String,
    val kind: String,
    val origin: String,
    val provider: String?,
    val sourceRef: String,
    val parentRef: String?,
    val showKey: String?,
    val title: String,
    val subtitle: String?,
    val artworkUrl: String?,
    val positionMs: Long,
    val durationMs: Long,
    val playedAt: Long
)

object HistoryKind {
    const val VIDEO = "VIDEO"
    const val AUDIO = "AUDIO"
}

object HistoryOrigin {
    const val LOCAL = "LOCAL"
    const val CLOUD = "CLOUD"
    const val YOUTUBE = "YOUTUBE"
}

/** Newest rows kept. */
const val HISTORY_LIMIT = 500

/** Under this far in, a play is a mis-tap, not history (unless it reached the end). */
const val HISTORY_MIN_POSITION_MS = 5_000L

fun shouldRecord(positionMs: Long, ended: Boolean): Boolean = ended || positionMs >= HISTORY_MIN_POSITION_MS

/** YouTube when played from a YouTube stream, LOCAL when it plays from a file on the device, else CLOUD. */
fun historyOriginFor(isYouTube: Boolean, isLocalFile: Boolean): String = when {
    isYouTube -> HistoryOrigin.YOUTUBE
    isLocalFile -> HistoryOrigin.LOCAL
    else -> HistoryOrigin.CLOUD
}

@Dao
abstract class HistoryDao {
    @Upsert
    abstract suspend fun upsert(entity: HistoryEntity)

    /** Newest first. */
    @Query("SELECT * FROM play_history ORDER BY playedAt DESC")
    abstract fun observeAll(): Flow<List<HistoryEntity>>

    @Query("SELECT * FROM play_history ORDER BY playedAt DESC")
    abstract suspend fun all(): List<HistoryEntity>

    @Query("SELECT * FROM play_history WHERE itemId = :itemId")
    abstract suspend fun get(itemId: String): HistoryEntity?

    @Query("DELETE FROM play_history WHERE itemId = :itemId")
    abstract suspend fun delete(itemId: String)

    @Query("DELETE FROM play_history")
    abstract suspend fun clear()

    /** Deletes everything but the newest [keep] rows. */
    @Query(
        "DELETE FROM play_history WHERE itemId NOT IN " +
            "(SELECT itemId FROM play_history ORDER BY playedAt DESC LIMIT :keep)"
    )
    abstract suspend fun prune(keep: Int = HISTORY_LIMIT)

    /** Upsert and prune together so the table never overshoots. */
    @Transaction
    open suspend fun record(entity: HistoryEntity, keep: Int = HISTORY_LIMIT) {
        upsert(entity)
        prune(keep)
    }

    /** For restore: adds the row unless one at least as new is already there. */
    @Transaction
    open suspend fun insertIfNewer(entity: HistoryEntity, keep: Int = HISTORY_LIMIT) {
        val existing = get(entity.itemId)
        if (existing == null || existing.playedAt < entity.playedAt) record(entity, keep)
    }

    /** For restore: writes the rows newer than what is stored, then prunes once. Returns how many were written. */
    @Transaction
    open suspend fun mergeFromBackup(rows: List<HistoryEntity>, keep: Int = HISTORY_LIMIT): Int {
        val have = all().associate { it.itemId to it.playedAt }
        val todo = com.abhinavxt.debforge.data.backup.HistoryBackup.toWrite(have, rows, keep)
        todo.forEach { upsert(it) }
        prune(keep)
        return todo.count { get(it.itemId) != null }
    }
}

/**
 * Play history. Writes are fire-and-forget on their own scope (like
 * PlaybackPositions) so a save in onStop finishes after the screen is gone.
 * While history is paused nothing is written at all; clearing still works.
 */
@Singleton
class PlayHistory internal constructor(
    private val dao: HistoryDao,
    private val paused: Flow<Boolean>,
    private val scope: CoroutineScope
) {
    @Inject
    constructor(dao: HistoryDao, settings: SettingsStore) :
        this(dao, settings.historyPausedFlow, CoroutineScope(SupervisorJob() + Dispatchers.IO))

    fun record(entry: HistoryEntity) {
        scope.launch {
            runCatching {
                if (!paused.first()) dao.record(entry)
            }
        }
    }

    fun observe(): Flow<List<HistoryEntity>> = dao.observeAll()

    suspend fun all(): List<HistoryEntity> = dao.all()

    suspend fun remove(itemId: String) = dao.delete(itemId)

    suspend fun clear() = dao.clear()

    suspend fun insertIfNewer(entry: HistoryEntity) = dao.insertIfNewer(entry)

    /** Backup restore: merge, newest playedAt wins per item. Returns rows added or updated. */
    suspend fun mergeFromBackup(rows: List<HistoryEntity>): Int = dao.mergeFromBackup(rows)
}
