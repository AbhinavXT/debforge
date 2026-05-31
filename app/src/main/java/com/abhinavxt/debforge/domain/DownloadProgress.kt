package com.abhinavxt.debforge.domain

/**
 * A live snapshot of one download's progress. This is the high-frequency,
 * in-memory view the UI renders — distinct from the throttled durable state in
 * Room. [bytesPerSecond] is a smoothed instantaneous rate.
 *
 * [waitingForNetwork] is true when the engine has paused retrying because
 * connectivity is unavailable. The DB state stays DOWNLOADING (so requeue-on-
 * restart still picks it up), but the UI surfaces a "Waiting for network"
 * subtitle instead of showing stuck progress.
 */
data class DownloadProgress(
    val id: String,
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val bytesPerSecond: Long,
    val state: DownloadState,
    val waitingForNetwork: Boolean = false
) {
    /** 0f..1f; 0 when total is unknown. */
    val fraction: Float
        get() = if (totalBytes > 0) (bytesDownloaded.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f

    /** Seconds remaining at the current rate, or null if not computable. */
    val etaSeconds: Long?
        get() = if (bytesPerSecond > 0 && totalBytes > 0) {
            ((totalBytes - bytesDownloaded).coerceAtLeast(0)) / bytesPerSecond
        } else null
}
