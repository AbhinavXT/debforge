package com.abhinavxt.debforge.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class UpcomingTest {

    @Test fun readsTmdbAiringEpisode() {
        val next = AiringEpisode.of(3, 4, "2026-10-30")!!
        assertEquals("S03E04", next.episodeLabel)
        assertEquals(7L, next.daysFrom(LocalDate.of(2026, 10, 23)))
        assertNull(AiringEpisode.of(3, 4, null))
        assertNull(AiringEpisode.of(3, 4, "soon"))
        assertNull(AiringEpisode.of(null, 4, "2026-10-30"))
    }

    @Test fun soonestFirstOncePerShow() {
        fun u(key: String, date: String) = Upcoming(key, key, AiringEpisode.of(1, 1, date)!!)
        val sorted = Upcoming.sort(listOf(u("b", "2026-11-02"), u("a", "2026-10-25"), u("b", "2026-11-02")))
        assertEquals(listOf("a", "b"), sorted.map { it.showKey })
    }
}
