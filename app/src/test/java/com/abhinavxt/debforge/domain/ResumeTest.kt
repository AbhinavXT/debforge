package com.abhinavxt.debforge.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumeTest {

    private val MIN = 60_000L
    private val episode = 22 * MIN
    private val movie = 120 * MIN

    @Test fun peekStartsOver() = assertEquals(0L, Resume.resumeAt(20_000, episode, finished = false))

    @Test fun resumesWithSmallRewind() = assertEquals(10 * MIN - 5_000, Resume.resumeAt(10 * MIN, episode, finished = false))

    @Test fun finishedStartsOver() = assertEquals(0L, Resume.resumeAt(10 * MIN, episode, finished = true))

    @Test fun creditsCountAsFinished() {
        // Episode: last minute counts (5% of 22 min is ~66 s).
        assertTrue(Resume.isFinished(episode - 50_000, episode))
        assertFalse(Resume.isFinished(episode - 3 * MIN, episode))
        // Movie: last 5% (6 min) counts.
        assertTrue(Resume.isFinished(movie - 5 * MIN, movie))
        assertFalse(Resume.isFinished(movie - 10 * MIN, movie))
    }

    @Test fun unknownLengthNeverFinished() = assertFalse(Resume.isFinished(99 * MIN, 0))

    @Test fun progress() {
        assertEquals(0.5f, Resume.progress(11 * MIN, episode)!!, 0.001f)
        assertNull(Resume.progress(5, 0))
        assertEquals(1f, Resume.progress(30 * MIN, episode)!!, 0.001f)
    }

    @Test fun continueWatchingFiltersAndSorts() {
        val entries = listOf(
            Resume.Entry("peek", 10_000, episode, false, updatedAt = 5),
            Resume.Entry("done", episode, episode, true, updatedAt = 6),
            Resume.Entry("credits", episode - 30_000, episode, false, updatedAt = 7),
            Resume.Entry("older", 5 * MIN, episode, false, updatedAt = 1),
            Resume.Entry("newer", 8 * MIN, movie, false, updatedAt = 9)
        )
        assertEquals(listOf("newer", "older"), Resume.continueWatching(entries, { it }).map { it.id })
    }

    @Test fun continueWatchingLimit() {
        val entries = (1..20).map { Resume.Entry("e$it", 5 * MIN, episode, false, updatedAt = it.toLong()) }
        val top = Resume.continueWatching(entries, { it }, limit = 3)
        assertEquals(listOf("e20", "e19", "e18"), top.map { it.id })
    }
}
