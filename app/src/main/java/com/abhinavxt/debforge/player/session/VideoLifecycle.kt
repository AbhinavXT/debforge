package com.abhinavxt.debforge.player.session

/**
 * The order of the video engine's lifecycle steps, kept apart from Media3 and Android so it can be tested
 * with a fake [Steps]. [VideoSession] supplies the steps; its host (PlayerActivity) reports its own
 * lifecycle here through the session, identified by the [token] [open] handed out.
 *
 * The rules are the ones PlayerActivity always followed: the player is built when the host starts and
 * released when it stops, unless "Keep playing in the background" handed it to the media service on the
 * way out; leaving for good tells the audio player where the video stopped, once; a handoff to audio does
 * not (the audio player already has the position).
 */
internal class VideoLifecycle(private val steps: Steps) {

    interface Steps {
        fun hasPlayer(): Boolean
        fun isPlaying(): Boolean
        /** The audio queue waits, paused, while a video plays. */
        fun parkAudio()
        fun savePosition()
        /** Tells the parked audio queue where the video stopped. */
        fun audioVideoClosed()
        fun buildPlayer()
        fun releasePlayer()
        fun startTicking()
        fun stopTicking()
        /** Hands the player to the background media service (sound goes on while away). */
        fun handToBackground()
        /** The service lets go: the session and the picture come back. */
        fun takeBack()
        /** Sound only while away: no point decoding pictures nobody sees. */
        fun disableVideo()
        /** This video is over: drop its per-video state and jobs. */
        fun discard()
    }

    /** Identifies the current video's host; a host holding an older token belongs to a replaced video. */
    var token = 0L
        private set
    var isOpen = false
        private set
    /** The player was handed to the background service: the sound goes on while the host is away. */
    var inBackground = false
        private set
    /** "Play as audio" is closing the video: the audio player already has the position. */
    var handingOff = false
    private var closedReported = false

    /** A new video: the one playing (if any) is saved and let go first. Returns the new video's token. */
    fun open(): Long {
        if (isOpen) {
            if (inBackground) takeBack()
            steps.stopTicking()
            steps.savePosition()
            steps.releasePlayer()
            steps.discard()
        }
        token++
        isOpen = true
        handingOff = false
        closedReported = false
        steps.parkAudio()
        return token
    }

    fun isCurrent(token: Long) = isOpen && token == this.token

    /** Host visible: take the player back from the service, build it if needed, tick. False: [token] is stale. */
    fun hostStarted(token: Long): Boolean {
        if (!isCurrent(token)) return false
        if (inBackground) takeBack()
        steps.buildPlayer()
        steps.startTicking()
        return true
    }

    /**
     * Host losing the foreground: with [backgroundOn] and something playing, hand the player to the service
     * now, while still in the foreground (Android only lets a foreground app start it). [mayLeave] is false
     * while the host is finishing, recreating or in picture-in-picture.
     */
    fun hostPaused(token: Long, mayLeave: Boolean, backgroundOn: Boolean) {
        if (!isCurrent(token)) return
        if (backgroundOn && !inBackground && steps.isPlaying() && mayLeave) {
            steps.handToBackground()
            inBackground = true
        }
    }

    fun hostResumed(token: Long) {
        if (isCurrent(token) && inBackground) takeBack()
    }

    /** Host stopped; [finishing]: it will not come back (the video closes). */
    fun hostStopped(token: Long, finishing: Boolean) {
        if (!isCurrent(token)) return
        steps.stopTicking()
        steps.savePosition()
        if (finishing && !handingOff && !inBackground && !closedReported && steps.hasPlayer()) {
            closedReported = true
            steps.audioVideoClosed()
        }
        if (inBackground && steps.hasPlayer() && !finishing) steps.disableVideo() else steps.releasePlayer()
    }

    /** Host destroyed; [finishing]: for good (else it is being recreated and the video stays open). */
    fun hostDestroyed(token: Long, finishing: Boolean) {
        if (!isCurrent(token)) return
        if (inBackground) {
            takeBack()
            steps.savePosition()
        }
        steps.releasePlayer()
        if (finishing) {
            isOpen = false
            steps.discard()
        }
    }

    /** Ends the video from outside a host's lifecycle: as if its host finished. */
    fun close() {
        if (!isOpen) return
        hostStopped(token, finishing = true)
        hostDestroyed(token, finishing = true)
    }

    private fun takeBack() {
        inBackground = false
        steps.takeBack()
    }
}
