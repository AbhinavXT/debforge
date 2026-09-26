package com.abhinavxt.debforge.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.abhinavxt.debforge.domain.DownloadState
import com.abhinavxt.debforge.domain.ProviderId

/**
 * A single download tracked by the engine. Created when the user enqueues an
 * item from a provider's list; persists across process death so the engine
 * can resume.
 *
 *  - [id]            provider-namespaced item id. Stable identity.
 *  - [provider]      which service owns this file — link refreshes always go
 *                    back to THIS provider, even if the user has since switched.
 *  - [sourceRef]     opaque handle the provider turns into a fresh URL.
 *  - [downloadUrl]   current direct URL; EMPTY means "not resolved yet" (TorBox
 *                    listings carry no URL — it's minted right before download).
 *  - [filesize]      exact byte count for preallocation + completeness check.
 *  - [chunksAllowed] max parallel connections for this file.
 *  - [partFilePath]  absolute path of the ".part" temp file being written.
 *  - [finalFilePath] absolute path the file is renamed to on success.
 *  - [bytesDownloaded] denormalized running total for cheap list display; the
 *                    authoritative per-range progress lives in ChunkEntity.
 *  - [supportsRanges] cached result of the 206-probe. Null = not probed yet.
 *
 * Column names `rdId` / `originalLink` are kept from schema v1-2 (Real-Debrid
 * only) so the upgrade is a single ADD COLUMN instead of a table rebuild; the
 * Kotlin names are provider-neutral.
 */
@Entity(
    tableName = "downloads",
    indices = [Index(value = ["state"])]
)
data class DownloadEntity(
    @PrimaryKey @ColumnInfo(name = "rdId") val id: String,
    @ColumnInfo(defaultValue = "REAL_DEBRID") val provider: ProviderId,
    val filename: String,
    @ColumnInfo(name = "originalLink") val sourceRef: String,
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
