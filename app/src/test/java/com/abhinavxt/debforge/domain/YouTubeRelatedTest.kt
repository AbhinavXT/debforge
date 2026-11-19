package com.abhinavxt.debforge.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class YouTubeRelatedTest {
    private fun id(n: Int) = "abcdefghi%02d".format(n)
    private fun c(n: Int, stream: Boolean = true, live: Boolean = false, dur: Long = 100) =
        RelatedCandidate(YouTubeSearchResult("https://www.youtube.com/watch?v=${id(n)}", "t$n", "ch", dur, null), stream, live)

    @Test fun dropsNonStreams() = assertEquals(listOf(1), relatedVideos(id(0), listOf(c(1), c(2, stream = false))).map { it.title.removePrefix("t").toInt() })
    @Test fun dropsLive() = assertEquals(1, relatedVideos(id(0), listOf(c(1), c(2, live = true), c(3, dur = 0))).size)
    @Test fun excludesSelf() = assertEquals(1, relatedVideos(id(1), listOf(c(1), c(2))).size)
    @Test fun dedupes() = assertEquals(2, relatedVideos(id(0), listOf(c(1), c(1), c(2))).size)
    @Test fun capsAt20() = assertEquals(20, relatedVideos(id(0), (1..30).map { c(it) }).size)
}
