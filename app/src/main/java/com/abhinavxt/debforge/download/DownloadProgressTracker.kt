package com.abhinavxt.debforge.download

import com.abhinavxt.debforge.domain.DownloadProgress
import com.abhinavxt.debforge.domain.DownloadState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds live, high-frequency progress for active downloads in memory. The
 * engine pushes updates here often (every buffer flush); the UI collects the
 * StateFlow. This is intentionally separate from Room: durable per-chunk
 * progress is persisted on a throttle (~every 4 MB), while what the user SEES
 * updates smoothly without hammering the database.
 *
 * Thread-safe: backed by a single MutableStateFlow updated atomically.
 */
@Singleton
class DownloadProgressTracker @Inject constructor() {

    private val _progress = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
    val progress: StateFlow<Map<String, DownloadProgress>> = _progress.asStateFlow()

    // Speed smoothing state, keyed by download id. These ARE touched from
    // multiple threads: chunk coroutines on Dispatchers.IO call update(), and
    // the service calls clear() for a different id from its own coroutine. A
    // plain HashMap can corrupt itself on a concurrent resize, so use
    // ConcurrentHashMap (per-id read-modify-write races only affect the
    // cosmetic speed estimate).
    private val lastBytes = ConcurrentHashMap<String, Long>()
    private val lastTimeMs = ConcurrentHashMap<String, Long>()
    private val smoothedBps = ConcurrentHashMap<String, Double>()

    fun update(
        id: String,
        bytesDownloaded: Long,
        totalBytes: Long,
        state: DownloadState,
        waitingReasonRes: Int? = null
    ) {
        val now = System.currentTimeMillis()
        val prevBytes = lastBytes[id]
        val prevTime = lastTimeMs[id]
        val bps: Long = if (prevBytes != null && prevTime != null && now > prevTime) {
            val instant = (bytesDownloaded - prevBytes).toDouble() / ((now - prevTime) / 1000.0)
            // Exponential moving average to keep the number from jittering.
            val smoothed = smoothedBps[id]?.let { it * 0.7 + instant * 0.3 } ?: instant
            smoothedBps[id] = smoothed
            smoothed.toLong().coerceAtLeast(0)
        } else 0L
        lastBytes[id] = bytesDownloaded
        lastTimeMs[id] = now

        _progress.update { current ->
            current + (id to DownloadProgress(
                id, bytesDownloaded, totalBytes,
                // When waiting for network the rate isn't meaningful — zero it
                // out so the UI doesn't show a frozen old speed.
                bytesPerSecond = if (waitingReasonRes != null) 0L else bps,
                state = state,
                waitingReasonRes = waitingReasonRes
            ))
        }
    }

    /** Drop a download from the live map (on completion/cancel) and reset its samples. */
    fun clear(id: String) {
        lastBytes.remove(id)
        lastTimeMs.remove(id)
        smoothedBps.remove(id)
        _progress.update { it - id }
    }
}
