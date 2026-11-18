package com.abhinavxt.debforge.data.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RestorePlanTest {
    private val tracks = listOf("a", "b", "c", "d").map { SavedTrack(it, it) }
    private fun snap(cur: String, pos: Long = 5000) =
        Snapshot(QueueSnapshot(tracks), SavedPosition(cur, pos, 100_000))

    @Test fun allSurvive() {
        assertEquals(RestorePlan(tracks, 2, 5000), restorePlan(snap("c")) { true })
    }

    @Test fun currentGoneStartsNextSurvivorAtZero() {
        val p = restorePlan(snap("b")) { it != "b" }!!
        assertEquals(listOf("a", "c", "d"), p.tracks.map { it.id })
        assertEquals(1, p.index)
        assertEquals(0L, p.posMs)
    }

    @Test fun currentAndFollowingGoneFallsBackToLast() {
        val p = restorePlan(snap("c")) { it == "a" || it == "b" }!!
        assertEquals(1, p.index)
        assertEquals(0L, p.posMs)
    }

    @Test fun allGoneIsNull() = assertNull(restorePlan(snap("a")) { false })

    @Test fun firstAndLastRemovedKeepCurrent() {
        val p = restorePlan(snap("b")) { it == "b" || it == "c" }!!
        assertEquals(listOf("b", "c"), p.tracks.map { it.id })
        assertEquals(0, p.index)
        assertEquals(5000L, p.posMs)
    }

    @Test fun youtubeTracksAlwaysSurvive() {
        val yt = SavedTrack("ytaudio:x", "Clip", local = false, videoId = "x", itag = 140)
        val s = Snapshot(QueueSnapshot(listOf(yt)), SavedPosition("ytaudio:x", 1000, 0))
        assertEquals(0, restorePlan(s) { false }!!.index)
    }
}
