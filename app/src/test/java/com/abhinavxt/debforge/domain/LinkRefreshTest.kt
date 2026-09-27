package com.abhinavxt.debforge.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LinkRefreshTest {

    @Test fun firstAttemptsAllowedThenGiveUp() {
        val t = 1_000_000L
        val a = LinkRefresh.next(LinkRefresh.State(), t)!!
        assertEquals(1, a.attempts)
        val b = LinkRefresh.next(a, t + 5_000)!!
        assertEquals(2, b.attempts)
        assertNull(LinkRefresh.next(b, t + 10_000))
    }

    @Test fun windowResets() {
        val t = 1_000_000L
        val b = LinkRefresh.next(LinkRefresh.next(LinkRefresh.State(), t)!!, t + 1_000)!!
        // An hour later (link expired again after a long pause): allowed again.
        val c = LinkRefresh.next(b, t + 3_600_000)!!
        assertEquals(1, c.attempts)
    }
}
