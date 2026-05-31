package com.abhinavxt.debforge.domain

/**
 * Lifecycle of a single download in the queue/engine.
 *
 * Transitions (happy path):
 *   QUEUED -> DOWNLOADING -> COMPLETED
 * Interruptions:
 *   DOWNLOADING -> PAUSED  (user) -> QUEUED (on resume)
 *   DOWNLOADING -> FAILED  (error) -> QUEUED (on retry)
 *
 * Only one download is DOWNLOADING at a time (single active download model).
 */
enum class DownloadState {
    QUEUED,
    DOWNLOADING,
    PAUSED,
    COMPLETED,
    FAILED
}
