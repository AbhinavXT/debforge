package com.abhinavxt.debforge.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MovieHashTest {

    private val chunk = MovieHash.CHUNK

    @Test fun zeroesHashToTheSize() {
        val zeros = ByteArray(chunk)
        assertEquals("%016x".format(1_000_000L), MovieHash.compute(1_000_000, zeros, zeros))
    }

    @Test fun wordsAreLittleEndian() {
        val head = ByteArray(chunk).also { it[0] = 1 } // first word = 1
        val tail = ByteArray(chunk).also { it[8] = 2 } // second word = 2
        assertEquals("%016x".format(1_000_000L + 3), MovieHash.compute(1_000_000, head, tail))
    }

    @Test fun overflowWrapsLikeUnsigned() {
        val ff = ByteArray(chunk) { 0xFF.toByte() } // every word = 2^64 - 1
        // 8192 words of -1 each side: size - 16384, mod 2^64.
        val expected = 1_000_000L - 2 * (chunk / 8)
        assertEquals("%016x".format(expected), MovieHash.compute(1_000_000, ff, ff))
    }

    @Test fun usesTheLastChunkOfALongerTail() {
        val tail = ByteArray(chunk + 16).also { it[16] = 5 } // first byte of the last 64 KiB
        assertEquals("%016x".format(1_000_000L + 5), MovieHash.compute(1_000_000, ByteArray(chunk), tail))
    }

    @Test fun tooSmallHasNoHash() {
        assertNull(MovieHash.compute(1_000, ByteArray(1_000), ByteArray(1_000)))
    }
}
