package com.abhinavxt.debforge.data.playback

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import com.abhinavxt.debforge.domain.ProviderId
import com.abhinavxt.debforge.domain.Resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where the user got to in a video. Keyed by the item id (stable across
 * refreshes, namespaced per service), not by URL: debrid links change every
 * time they're unrestricted, which is exactly why external players lose
 * their place with streams.
 *
 * [sourceRef] + [provider] let "Continue watching" get a fresh link later.
 * [showKey] (ReleaseInfo.groupKey) ties episodes to their show.
 */
@Entity(tableName = "playback_positions", indices = [Index("updatedAt")])
data class PlaybackEntity(
    @PrimaryKey val itemId: String,
    val provider: ProviderId,
    val sourceRef: String,
    val filename: String,
    val title: String,
    val showKey: String?,
    val positionMs: Long,
    val durationMs: Long,
    val finished: Boolean,
    val updatedAt: Long,
    /**
     * Torrent / job the file belongs to (v8). Lets "Continue watching" find
     * its subtitles and next episode when the Library hasn't loaded it.
     */
    val parentRef: String? = null
) {
    val resumeEntry: Resume.Entry get() = Resume.Entry(itemId, positionMs, durationMs, finished, updatedAt)
    val progress: Float? get() = Resume.progress(positionMs, durationMs)
}

@Dao
interface PlaybackDao {
    @Upsert
    suspend fun upsert(entity: PlaybackEntity)

    @Query("SELECT * FROM playback_positions WHERE itemId = :itemId")
    suspend fun get(itemId: String): PlaybackEntity?

    /** Everything, newest first; small (one row per video ever played). */
    @Query("SELECT * FROM playback_positions ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<PlaybackEntity>>

    @Query("DELETE FROM playback_positions WHERE itemId = :itemId")
    suspend fun delete(itemId: String)
}

/**
 * Saves positions off the player's lifecycle: a save started in onStop must
 * finish even though the activity is going away.
 */
@Singleton
class PlaybackPositions @Inject constructor(private val dao: PlaybackDao) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun get(itemId: String): PlaybackEntity? = dao.get(itemId)

    fun save(entity: PlaybackEntity) {
        scope.launch { runCatching { dao.upsert(entity) } }
    }

    val all: Flow<List<PlaybackEntity>> = dao.observeAll()

    suspend fun forget(itemId: String) = dao.delete(itemId)
}
