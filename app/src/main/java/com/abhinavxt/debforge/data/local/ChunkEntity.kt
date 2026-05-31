package com.abhinavxt.debforge.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * One byte-range of a download. Together, all chunks for a download form the
 * persisted resume bitmap: on restart the engine re-requests only ranges where
 * [bytesWritten] < (endByte - startByte + 1).
 *
 * Boundary contract (critical for no corruption):
 *  - ranges are contiguous and non-overlapping,
 *  - [startByte] is inclusive, [endByte] is inclusive,
 *  - the final chunk's [endByte] == filesize - 1 exactly,
 *  - a chunk NEVER writes past its [endByte] even if the stream sends more.
 *
 * [bytesWritten] is persisted throttled (~every 2-4 MB and on pause/cancel),
 * so on a crash we may re-download up to a few MB of one chunk — acceptable,
 * and always correct because writes are seek-positioned by absolute offset.
 *
 * Cascade delete: removing a DownloadEntity removes its chunks.
 */
@Entity(
    tableName = "chunks",
    primaryKeys = ["downloadId", "index"],
    foreignKeys = [
        ForeignKey(
            entity = DownloadEntity::class,
            parentColumns = ["rdId"],
            childColumns = ["downloadId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("downloadId")]
)
data class ChunkEntity(
    val downloadId: String,
    val index: Int,
    val startByte: Long,
    val endByte: Long,
    val bytesWritten: Long = 0L,
    val complete: Boolean = false
) {
    /** Absolute offset where the next byte for this chunk must be written. */
    val nextWriteOffset: Long get() = startByte + bytesWritten

    /** Total bytes this chunk is responsible for. */
    val totalBytes: Long get() = endByte - startByte + 1
}
