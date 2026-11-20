package com.abhinavxt.debforge.player.session

import android.widget.Toast
import androidx.media3.common.C
import com.abhinavxt.debforge.R
import com.abhinavxt.debforge.domain.DataResult
import com.abhinavxt.debforge.domain.Resume
import com.abhinavxt.debforge.domain.SubtitleLink
import com.abhinavxt.debforge.player.QueuedEpisode
import com.abhinavxt.debforge.player.UpNext
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The episodes around the one playing in [VideoSession]: Next / Previous in the same player, the
 * "Next: …" card with its countdown, and fetching the next episode's links ahead of time.
 */
internal class VideoEpisodes(private val s: VideoSession) {

    /** Settings → "Autoplay next episode". Off: the card still offers Play now, without a countdown. */
    var autoplay = true

    /** Episodes after this one (same show), best copy of each. */
    private val queue = ArrayDeque<QueuedEpisode>()
    /** Episodes before this one, nearest first ("Previous episode"). */
    private val previousQueue = ArrayDeque<QueuedEpisode>()
    /** The episode playing, so going to the next or previous one can come back to it. */
    private var currentEpisode: QueuedEpisode? = null
    /** The next episode with its links fetched ahead of time. */
    private var prepared: Prepared? = null
    private var prefetchJob: Job? = null
    /** User tapped Cancel on the card: no autoplay for this file. */
    private var nextCancelled = false
    /** Switching files: the countdown and "ended" can both fire; only one may. */
    var switching = false
        private set
    /** When the card appeared (elapsedRealtime), for its countdown; null = hidden. */
    var cardShownAt: Long? = null

    private data class Prepared(val episode: QueuedEpisode, val url: String, val subtitles: List<SubtitleLink>)

    val hasNext get() = queue.isNotEmpty()
    val hasPrevious get() = previousQueue.isNotEmpty()

    /** The episodes after this one, nearest first (the watch page's list). */
    val upcoming: List<QueuedEpisode> get() = queue.toList()

    /** The queue handed over with the file that starts ([UpNext.take]). */
    fun load(q: UpNext.Queue) {
        queue.addAll(q.next)
        previousQueue.addAll(q.previous)
        currentEpisode = q.current
    }

    /** Prefetch near the end, then show the card and count down to the next episode. */
    fun update() {
        if (switching) return
        val p = s.player ?: return
        val next = queue.firstOrNull()
        val duration = p.duration.takeIf { it != C.TIME_UNSET && it > 0 }
        if (next == null || duration == null || nextCancelled) return hideCard()
        val remaining = duration - p.currentPosition
        if (remaining <= PREFETCH_BEFORE_END_MS) prefetch(next)
        if (remaining > CARD_BEFORE_END_MS) return hideCard() // e.g. seeked back
        val shownAt = cardShownAt ?: android.os.SystemClock.elapsedRealtime().also {
            cardShownAt = it
            s.host?.showNextCard(s.string(R.string.player_next, next.title))
        }
        if (!autoplay || s.controls.sleepAtEnd) {
            // Offer only: the user starts it with Play now (or the sleep timer stops here).
            s.host?.setNextCountdown(null)
            return
        }
        val left = ((COUNTDOWN_MS - (android.os.SystemClock.elapsedRealtime() - shownAt)) / 1000).toInt()
        if (left <= 0) {
            playNext()
        } else {
            s.host?.setNextCountdown(s.context.resources.getQuantityString(R.plurals.player_next_in, left, left))
        }
    }

    private fun hideCard() {
        cardShownAt = null
        s.host?.hideNextCard()
    }

    /** Cancel on the card (or Back while it shows): no autoplay for this file. */
    fun cancel() {
        nextCancelled = true
        hideCard()
    }

    /** The file played to the end: on to the next episode, if autoplay and the user didn't cancel. */
    fun ended() {
        if (autoplay && queue.isNotEmpty() && !nextCancelled) playNext()
    }

    /** Fetches the next episode's link and subtitles once, ahead of time. */
    private fun prefetch(episode: QueuedEpisode) {
        if (prefetchJob != null || prepared?.episode == episode) return
        prefetchJob = s.scope.launch {
            prepared = prepare(episode)
            prefetchJob = null
        }
    }

    private suspend fun prepare(episode: QueuedEpisode): Prepared? {
        val item = episode.item
        val url = item.downloadUrl
            ?: (s.repository.resolveLink(item.provider, item.sourceRef) as? DataResult.Success)?.data?.url
            ?: return null
        return Prepared(episode, url, s.subtitleResolver.resolve(episode.subtitles))
    }

    /**
     * Switches to the next episode in this same player: marks the current one
     * watched, then plays the next from its saved position (if any).
     */
    fun playNext() {
        if (switching) return
        val episode = queue.firstOrNull() ?: return
        switching = true
        hideCard()
        s.savePosition(ended = true)
        s.scrobbleStopped(ended = true)
        s.player?.pause()
        s.scope.launch {
            try {
                switchTo(episode)
            } finally {
                switching = false
            }
        }
    }

    /** Back to the episode before this one, from where it was left (like [playNext], backwards). */
    fun playPrevious() {
        if (switching) return
        val episode = previousQueue.firstOrNull() ?: return
        switching = true
        hideCard()
        s.savePosition()
        s.scrobbleStopped()
        s.player?.pause()
        s.scope.launch {
            try {
                switchTo(episode, backwards = true)
            } finally {
                switching = false
            }
        }
    }

    /** Jumps to the upcoming episode [itemId] (a tap in the watch page's list); the ones skipped over move behind this one. */
    fun jumpTo(itemId: String) {
        if (switching) return
        val index = queue.indexOfFirst { it.item.id == itemId }
        if (index < 0) return
        val episode = queue[index]
        val skipped = queue.take(index)
        switching = true
        hideCard()
        s.savePosition()
        s.scrobbleStopped()
        s.player?.pause()
        s.scope.launch {
            try {
                switchTo(episode, skipped = skipped)
            } finally {
                switching = false
            }
        }
    }

    private suspend fun switchTo(episode: QueuedEpisode, backwards: Boolean = false, skipped: List<QueuedEpisode> = emptyList()) {
        prefetchJob?.join()
        val ready = prepared?.takeIf { it.episode == episode } ?: prepare(episode)
        prepared = null
        if (ready == null) {
            Toast.makeText(s.context, R.string.player_next_failed, Toast.LENGTH_LONG).show()
            return
        }
        // This episode moves to the other side: Previous after going forward, Next after going back.
        if (backwards) {
            previousQueue.removeFirst()
            currentEpisode?.let { queue.addFirst(it) }
        } else {
            repeat(skipped.size) { queue.removeFirst() }
            queue.removeFirst()
            currentEpisode?.let { previousQueue.addFirst(it) }
            // Nearest first: the one just before the target, then the ones before that.
            skipped.forEach { previousQueue.addFirst(it) }
        }
        currentEpisode = episode
        s.episodePlayer?.queueChanged()
        s.switchFile(episode, ready.url, ready.subtitles)
        nextCancelled = false
        val saved = s.positions.get(episode.item.id)
        val at = saved?.let { Resume.resumeAt(it.positionMs, it.durationMs, it.finished) } ?: 0L
        s.player?.apply {
            setMediaItem(s.buildMediaItem(), at)
            prepare()
            play()
        }
        s.host?.updatePip()
    }

    private companion object {
        /** Fetch the next episode's links this long before the end. */
        const val PREFETCH_BEFORE_END_MS = 90_000L
        /** Show "Next: …" this long before the end (end credits). */
        const val CARD_BEFORE_END_MS = 30_000L
        /** The card counts down this long, then the next episode starts. */
        const val COUNTDOWN_MS = 10_000L
    }
}
