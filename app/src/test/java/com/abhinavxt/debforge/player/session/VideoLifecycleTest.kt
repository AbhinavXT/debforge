package com.abhinavxt.debforge.player.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoLifecycleTest {

    /** Records the steps taken; holds a pretend player. */
    private class FakeSteps : VideoLifecycle.Steps {
        val log = mutableListOf<String>()
        var player = false
        var playing = false

        fun count(step: String) = log.count { it == step }

        override fun hasPlayer() = player
        override fun isPlaying() = playing
        override fun parkAudio() { log += "park" }
        override fun savePosition() { log += if (player) "save" else "save(no player)" }
        override fun audioVideoClosed() { log += "videoClosed" }
        override fun buildPlayer() { log += "build"; player = true }
        override fun releasePlayer() { log += "release"; player = false; playing = false }
        override fun startTicking() { log += "tick" }
        override fun stopTicking() { log += "stopTick" }
        override fun handToBackground() { log += "toBackground" }
        override fun takeBack() { log += "takeBack" }
        override fun disableVideo() { log += "disableVideo" }
        override fun discard() { log += "discard" }
        override fun closeHost() { log += "closeHost" }
    }

    private fun started(): Triple<FakeSteps, VideoLifecycle, Long> {
        val steps = FakeSteps()
        val life = VideoLifecycle(steps)
        val token = life.open()
        assertTrue(life.hostStarted(token))
        steps.playing = true
        steps.log.clear()
        return Triple(steps, life, token)
    }

    @Test fun openParksAudioAndStartBuildsThePlayer() {
        val steps = FakeSteps()
        val life = VideoLifecycle(steps)
        val token = life.open()
        assertEquals(listOf("park"), steps.log)
        assertTrue(life.hostStarted(token))
        assertEquals(listOf("park", "build", "tick"), steps.log)
    }

    @Test fun openingAnotherVideoSavesThePreviousPositionFirst() {
        val (steps, life, first) = started()
        val second = life.open()
        assertEquals(listOf("closeHost", "stopTick", "save", "release", "discard", "park"), steps.log)
        assertNotEquals(first, second)
        assertFalse(life.isCurrent(first))
        assertTrue(life.isCurrent(second))
    }

    @Test fun openingAnotherVideoTakesThePlayerBackFromTheServiceBeforeSaving() {
        val (steps, life, token) = started()
        life.hostPaused(token, mayLeave = true, backgroundOn = true)
        life.hostStopped(token, finishing = false)
        steps.log.clear()
        life.open()
        assertEquals(listOf("closeHost", "takeBack", "stopTick", "save", "release", "discard", "park"), steps.log)
        assertFalse(life.inBackground)
    }

    @Test fun openingAVideoWhileNoneIsOpenClosesNoHost() {
        val steps = FakeSteps()
        VideoLifecycle(steps).open()
        assertEquals(0, steps.count("closeHost"))
    }

    @Test fun openingAnotherVideoTellsTheOldHostToCloseBeforeDiscardingIt() {
        val (steps, life, _) = started()
        life.open()
        assertEquals(1, steps.count("closeHost"))
        assertTrue(steps.log.indexOf("closeHost") < steps.log.indexOf("discard"))
    }

    @Test fun aTokenFromAnotherProcessNeverMatches() {
        // Process death: the saved screen holds a token of the dead process; the new one starts from its own seed.
        val old = VideoLifecycle(FakeSteps(), seed = 1000L)
        val saved = old.open()
        val steps = FakeSteps()
        val fresh = VideoLifecycle(steps, seed = 987654321L)
        assertFalse(fresh.isCurrent(saved)) // nothing open yet: the screen rebuilds from its intent
        val reopened = fresh.open()
        assertFalse(fresh.isCurrent(saved))
        assertTrue(fresh.isCurrent(reopened))
        assertFalse(fresh.hostStarted(saved))
    }

    @Test fun defaultSeedsDifferBetweenProcesses() {
        val tokens = (1..5).map { VideoLifecycle(FakeSteps()).open() }.toSet()
        assertTrue(tokens.size > 1)
    }

    @Test fun aRecreatedHostKeepsItsTokenAndTheVideoStaysOpen() {
        val (steps, life, token) = started()
        life.hostStopped(token, finishing = false)
        life.hostDestroyed(token, finishing = false)
        assertTrue(life.isCurrent(token))
        steps.log.clear()
        assertTrue(life.hostStarted(token))
        assertEquals(0, steps.count("closeHost"))
    }

    @Test fun aReplacedVideosHostIsToldToClose() {
        val (steps, life, first) = started()
        life.open()
        assertEquals(1, steps.count("closeHost"))
        steps.log.clear()
        assertFalse(life.hostStarted(first))
        life.hostStopped(first, finishing = true)
        life.hostDestroyed(first, finishing = true)
        assertEquals(emptyList<String>(), steps.log) // the new video is untouched
        assertTrue(life.isOpen)
    }

    @Test fun closeCallsVideoClosedExactlyOnce() {
        val (steps, life, _) = started()
        life.close()
        life.close()
        assertEquals(1, steps.count("videoClosed"))
        assertEquals(listOf("stopTick", "save", "videoClosed", "release", "release", "discard"), steps.log)
        assertFalse(life.isOpen)
    }

    @Test fun finishingHostReportsVideoClosedOnceAcrossStopAndDestroy() {
        val (steps, life, token) = started()
        life.hostStopped(token, finishing = true)
        life.hostDestroyed(token, finishing = true)
        life.close()
        assertEquals(1, steps.count("videoClosed"))
        assertTrue(steps.log.indexOf("save") < steps.log.indexOf("videoClosed"))
        assertFalse(life.isOpen)
    }

    @Test fun handoffToAudioDoesNotReportVideoClosed() {
        val (steps, life, token) = started()
        life.handingOff = true
        life.hostStopped(token, finishing = true)
        assertEquals(0, steps.count("videoClosed"))
    }

    @Test fun stoppingInTheBackgroundKeepsThePlayer() {
        val (steps, life, token) = started()
        life.hostPaused(token, mayLeave = true, backgroundOn = true)
        assertTrue(life.inBackground)
        life.hostStopped(token, finishing = false)
        assertEquals(listOf("toBackground", "stopTick", "save", "disableVideo"), steps.log)
        assertTrue(steps.player)
        steps.log.clear()
        // Back: the service lets go, the same player goes on.
        assertTrue(life.hostStarted(token))
        assertEquals(listOf("takeBack", "build", "tick"), steps.log)
        assertFalse(life.inBackground)
    }

    @Test fun stoppingWithoutBackgroundReleasesThePlayer() {
        val (steps, life, token) = started()
        life.hostPaused(token, mayLeave = true, backgroundOn = false)
        life.hostStopped(token, finishing = false)
        assertEquals(listOf("stopTick", "save", "release"), steps.log)
        assertFalse(steps.player)
        assertTrue(life.isOpen)
    }

    @Test fun noBackgroundWhenPausedOrNotAllowedToLeave() {
        val (steps, life, token) = started()
        life.hostPaused(token, mayLeave = false, backgroundOn = true) // finishing, recreating or picture-in-picture
        assertFalse(life.inBackground)
        steps.playing = false
        life.hostPaused(token, mayLeave = true, backgroundOn = true)
        assertFalse(life.inBackground)
        assertEquals(0, steps.count("toBackground"))
    }

    @Test fun finishingInTheBackgroundSkipsVideoClosedAndTakesThePlayerBack() {
        val (steps, life, token) = started()
        life.hostPaused(token, mayLeave = true, backgroundOn = true)
        life.hostStopped(token, finishing = true)
        life.hostDestroyed(token, finishing = true)
        assertEquals(0, steps.count("videoClosed"))
        assertEquals(1, steps.count("takeBack"))
        assertFalse(life.isOpen)
    }

    @Test fun recreatedHostKeepsTheVideoOpen() {
        val (steps, life, token) = started()
        life.hostStopped(token, finishing = false)
        life.hostDestroyed(token, finishing = false)
        assertTrue(life.isCurrent(token))
        assertEquals(0, steps.count("discard"))
        assertEquals(0, steps.count("videoClosed"))
        steps.log.clear()
        assertTrue(life.hostStarted(token))
        assertEquals(listOf("build", "tick"), steps.log)
    }

    @Test fun resumeTakesThePlayerBackOnlyFromTheBackground() {
        val (steps, life, token) = started()
        life.hostResumed(token)
        assertEquals(0, steps.count("takeBack"))
        life.hostPaused(token, mayLeave = true, backgroundOn = true)
        life.hostResumed(token)
        assertEquals(1, steps.count("takeBack"))
        assertFalse(life.inBackground)
    }
}
