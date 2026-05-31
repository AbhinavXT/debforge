package com.abhinavxt.debforge.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.abhinavxt.debforge.domain.DownloadState

/**
 * A single download tracked by the engine. Created when the user enqueues an
 * item from the RD downloads list; persists across process death so the engine
 * can resume.
 *
 *  - [rdId]          RD download id (from /downloads). Stable identity.
 *  - [originalLink]  hoster link, used to re-unrestrict when [downloadUrl] dies.
 *  - [downloadUrl]   current direct URL we fetch bytes from (may be refreshed).
 *  - [filesize]      exact byte count for preallocation + completeness check.
 *  - [chunksAllowed] RD's max parallel connections for this file.
 *  - [partFilePath]  absolute path of the ".part" temp file being written.
 *  - [finalFilePath] absolute path the file is renamed to on success.
 *  - [bytesDownloaded] denormalized running total for cheap list display; the
 *                    authoritative per-range progress lives in ChunkEntity.
 *  - [supportsRanges] cached result of the 206-probe: true = parallel path,
 *                    false = single-stream fallback. Null = not probed yet.
 */
@Entity(
    tableName = "downloads",
    indices = [Index(value = ["state"])]
)
data class DownloadEntity(
    @PrimaryKey val rdId: String,
    val filename: String,
    val originalLink: String,
    val downloadUrl: String,
    val host: String,
    val filesize: Long,
    val chunksAllowed: Int,
    val state: DownloadState,
    val bytesDownloaded: Long = 0L,
    val partFilePath: String? = null,
    val finalFilePath: String? = null,
    val supportsRanges: Boolean? = null,
    val errorMessage: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
