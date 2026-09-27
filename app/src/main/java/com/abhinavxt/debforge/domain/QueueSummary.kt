package com.abhinavxt.debforge.domain

/** One download as the widget and the Quick Settings tile see it. */
data class QueueRow(
    val name: String,
    val state: DownloadState,
    val bytesDone: Long,
    /** 0 or less = size unknown. */
    val totalBytes: Long,
    val createdAt: Long
)

/**
 * What's going on in the download queue, in one glance: counts plus the file
 * to show (the running one, else the next in line). Pure: unit-tested in
 * QueueSummaryTest.
 */
data class QueueSummary(
    val running: Int,
    val queued: Int,
    val paused: Int,
    val failed: Int,
    val current: QueueRow?
) {
    /** Something is downloading or waiting its turn: "Pause all" applies. */
    val hasActive: Boolean get() = running + queued > 0

    /** 0..100 for [current], or null when its size is unknown. */
    val currentPercent: Int?
        get() = current?.takeIf { it.totalBytes > 0 }?.let {
            ((it.bytesDone.coerceIn(0, it.totalBytes) * 100) / it.totalBytes).toInt()
        }

    companion object {
        val EMPTY = QueueSummary(0, 0, 0, 0, null)

        fun of(rows: List<QueueRow>): QueueSummary {
            val byState = rows.groupBy { it.state }
            fun count(s: DownloadState) = byState[s]?.size ?: 0
            // The queue runs oldest first, so the oldest queued row is next.
            val current = byState[DownloadState.DOWNLOADING]?.minByOrNull { it.createdAt }
                ?: byState[DownloadState.QUEUED]?.minByOrNull { it.createdAt }
                ?: byState[DownloadState.PAUSED]?.minByOrNull { it.createdAt }
            return QueueSummary(
                running = count(DownloadState.DOWNLOADING),
                queued = count(DownloadState.QUEUED),
                paused = count(DownloadState.PAUSED),
                failed = count(DownloadState.FAILED),
                current = current
            )
        }
    }
}
