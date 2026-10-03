package com.abhinavxt.debforge.player

import android.net.Uri
import com.abhinavxt.debforge.domain.MovieHash
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [MovieHash] of what the player is playing: two 64 KiB reads (range
 * requests for a stream). Null when the source can't tell its size or
 * doesn't do ranges; the search then goes by title only.
 */
@Singleton
class MovieHasher @Inject constructor(private val ranges: RangeReader) {

    suspend fun hashOf(uri: Uri): String? = withContext(Dispatchers.IO) {
        val head = ranges.read(uri, 0, MovieHash.CHUNK) ?: return@withContext null
        val size = head.totalSize?.takeIf { it >= MovieHash.CHUNK } ?: return@withContext null
        val tail = ranges.read(uri, size - MovieHash.CHUNK, MovieHash.CHUNK) ?: return@withContext null
        MovieHash.compute(size, head.bytes, tail.bytes)
    }
}
