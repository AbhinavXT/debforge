package com.abhinavxt.debforge.player

import org.junit.Assert.assertEquals
import org.junit.Test

class SkipLengthTest {
    @Test fun validValuesPassThrough() {
        SKIP_LENGTHS.forEach { assertEquals(it, validSkipSeconds(it)) }
    }

    @Test fun unknownOrMissingFallsBackToDefault() {
        assertEquals(DEFAULT_SKIP_SECONDS, validSkipSeconds(null))
        assertEquals(DEFAULT_SKIP_SECONDS, validSkipSeconds(0))
        assertEquals(DEFAULT_SKIP_SECONDS, validSkipSeconds(7))
        assertEquals(DEFAULT_SKIP_SECONDS, validSkipSeconds(-30))
    }

    @Test fun millisecondsFollowTheLength() {
        assertEquals(5_000L, skipMs(5))
        assertEquals(30_000L, skipMs(30))
        assertEquals(10_000L, skipMs(99))
    }
}
