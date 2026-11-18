package com.abhinavxt.debforge.download.conditions

import com.abhinavxt.debforge.data.local.DownloadEntity

private const val BYTES_PER_GB = 1024L * 1024L * 1024L

fun storageLimitBytes(gb: Int): Long = if (gb > 0) gb * BYTES_PER_GB else 0L

/**
 * Should the file that would start next wait because of the storage limit?
 *
 * Only a file that has not started is held back: a started file is already
 * counted in [reservedBytes] and may always finish, so the limit never strands
 * a half-downloaded file. Unknown size (<= 0) blocks only once already at or
 * over the cap. [capBytes] <= 0 means the limit is off.
 */
fun storageBlocks(capBytes: Long, reservedBytes: Long, next: DownloadEntity?): Boolean =
    capBytes > 0 && next != null && next.bytesDownloaded == 0L &&
        (if (next.filesize > 0) reservedBytes + next.filesize > capBytes else reservedBytes >= capBytes)
