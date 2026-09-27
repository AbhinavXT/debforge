package com.abhinavxt.debforge.data.usage

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.abhinavxt.debforge.domain.ProviderId
import com.abhinavxt.debforge.download.NetworkMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bytes downloaded per local day, service and network kind. [metered] is
 * true for mobile data (and other metered links), false for Wi-Fi / Ethernet.
 */
@Entity(tableName = "data_usage", primaryKeys = ["day", "provider", "metered"])
data class UsageEntity(
    /** ISO date, e.g. "2026-09-27" (sorts and compares as text). */
    val day: String,
    val provider: ProviderId,
    val metered: Boolean,
    val bytes: Long
)

@Dao
abstract class UsageDao {

    @Query("SELECT bytes FROM data_usage WHERE day = :day AND provider = :provider AND metered = :metered")
    abstract suspend fun bytesFor(day: String, provider: ProviderId, metered: Boolean): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun put(row: UsageEntity)

    @Transaction
    open suspend fun add(day: String, provider: ProviderId, metered: Boolean, bytes: Long) {
        put(UsageEntity(day, provider, metered, (bytesFor(day, provider, metered) ?: 0L) + bytes))
    }

    @Query("SELECT * FROM data_usage WHERE day >= :fromDay ORDER BY day")
    abstract fun observeSince(fromDay: String): Flow<List<UsageEntity>>

    @Query("DELETE FROM data_usage")
    abstract suspend fun clear()
}

/**
 * Counts downloaded bytes from the engine's hot loop without touching the
 * database per read: bytes pile up in memory and are written every few
 * seconds while downloads run (a crash loses at most that much).
 */
@Singleton
class UsageRecorder @Inject constructor(
    private val dao: UsageDao,
    private val network: NetworkMonitor
) {
    private data class Key(val provider: ProviderId, val metered: Boolean)

    private val pending = ConcurrentHashMap<Key, AtomicLong>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private var flusher: Job? = null

    fun add(provider: ProviderId, bytes: Int) {
        if (bytes <= 0) return
        val key = Key(provider, metered = !network.unmetered.value)
        pending.getOrPut(key) { AtomicLong() }.addAndGet(bytes.toLong())
        ensureFlusher()
    }

    private fun ensureFlusher() {
        if (flusher?.isActive == true) return
        synchronized(lock) {
            if (flusher?.isActive == true) return
            flusher = scope.launch {
                // Keep flushing while bytes arrive; stop after a quiet interval.
                do {
                    delay(FLUSH_MS)
                } while (flush())
            }
        }
    }

    /** Writes what's pending. Returns false if there was nothing to write. */
    suspend fun flush(): Boolean {
        val day = LocalDate.now().toString()
        var wrote = false
        pending.forEach { (key, counter) ->
            val bytes = counter.getAndSet(0)
            if (bytes > 0) {
                wrote = true
                runCatching { dao.add(day, key.provider, key.metered, bytes) }
                    .onFailure { counter.addAndGet(bytes) } // retry next round
            }
        }
        return wrote
    }

    private companion object {
        const val FLUSH_MS = 5_000L
    }
}
