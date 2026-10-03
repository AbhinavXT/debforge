package com.abhinavxt.debforge.domain

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * OpenSubtitles' file hash: the file size plus the sums of the first and the
 * last 64 KiB read as little-endian 64-bit words, overflowing as unsigned.
 * Subtitles found by hash are timed for exactly this release. Only the two
 * ends of the file are needed, so a stream costs two small range requests.
 * Pure Kotlin: unit-tested in MovieHashTest.
 */
object MovieHash {

    const val CHUNK = 64 * 1024

    /** Null for files too small to hash (OpenSubtitles wants at least one full chunk). */
    fun compute(size: Long, head: ByteArray, tail: ByteArray): String? {
        if (size < CHUNK || head.size < CHUNK || tail.size < CHUNK) return null
        var hash = size
        hash += sum(head, 0)
        hash += sum(tail, tail.size - CHUNK)
        return "%016x".format(hash)
    }

    private fun sum(bytes: ByteArray, from: Int): Long {
        val buf = ByteBuffer.wrap(bytes, from, CHUNK).order(ByteOrder.LITTLE_ENDIAN)
        var total = 0L
        repeat(CHUNK / 8) { total += buf.getLong() }
        return total
    }
}
