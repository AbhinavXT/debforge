package com.abhinavxt.debforge.download

import kotlin.math.min

/**
 * A single contiguous byte range of a file. [startByte] and [endByte] are both
 * INCLUSIVE — this matches HTTP Range semantics ("Range: bytes=start-end").
 */
data class ChunkRange(
    val index: Int,
    val startByte: Long,
    val endByte: Long
) {
    /** Total bytes this range covers. */
    val size: Long get() = endByte - startByte + 1
}

/**
 * Splits a file into contiguous, non-overlapping byte ranges for parallel
 * download. This is the single source of truth for chunk boundaries and is
 * deliberately pure (no I/O, no Android deps) so it can be unit-tested.
 *
 * Invariants guaranteed by [plan]:
 *  - ranges are contiguous and cover exactly [0, filesize-1],
 *  - the FIRST range starts at 0,
 *  - the LAST range ends at exactly filesize-1 (the classic off-by-one that
 *    corrupts the tail if gotten wrong),
 *  - no range is smaller than [MIN_CHUNK_SIZE] (except when the whole file is
 *    smaller than that, in which case there is exactly one range),
 *  - the number of ranges never exceeds min(requested, [MAX_PARALLEL_CHUNKS]).
 */
object ChunkPlanner {

    const val MAX_PARALLEL_CHUNKS = 16

    /** Below this size, splitting buys nothing and just wastes connections. */
    const val MIN_CHUNK_SIZE = 1L shl 20 // 1 MiB

    /**
     * @param filesize total size in bytes; must be > 0.
     * @param requestedChunks RD's advertised `chunks` for this file.
     * @param maxChunks hard ceiling (defaults to [MAX_PARALLEL_CHUNKS]).
     */
    fun plan(
        filesize: Long,
        requestedChunks: Int,
        maxChunks: Int = MAX_PARALLEL_CHUNKS
    ): List<ChunkRange> {
        require(filesize > 0) { "filesize must be positive, was $filesize" }

        // Cap the chunk count three ways: RD's advertised value, our ceiling,
        // and how many MIN_CHUNK_SIZE blocks actually fit in the file.
        val byMinSize = ((filesize + MIN_CHUNK_SIZE - 1) / MIN_CHUNK_SIZE).toInt() // ceil
        val n = requestedChunks
            .coerceAtLeast(1)
            .coerceAtMost(maxChunks)
            .coerceAtMost(byMinSize)
            .coerceAtLeast(1)

        if (n == 1) {
            return listOf(ChunkRange(0, 0, filesize - 1))
        }

        // Even base size; the final chunk absorbs the remainder so the last
        // endByte lands on filesize-1 exactly regardless of divisibility.
        val base = filesize / n
        val ranges = ArrayList<ChunkRange>(n)
        var start = 0L
        for (i in 0 until n) {
            val isLast = i == n - 1
            val end = if (isLast) filesize - 1 else min(start + base - 1, filesize - 1)
            ranges += ChunkRange(index = i, startByte = start, endByte = end)
            start = end + 1
        }
        return ranges
    }
}
