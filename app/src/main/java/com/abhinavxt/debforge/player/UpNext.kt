package com.abhinavxt.debforge.player

import com.abhinavxt.debforge.domain.DownloadItem
import com.abhinavxt.debforge.domain.Subtitles
import javax.inject.Inject
import javax.inject.Singleton

/** One episode waiting its turn: links are fetched only when it's about to play. */
data class QueuedEpisode(
    val item: DownloadItem,
    val title: String,
    val showKey: String?,
    /** Its subtitle files (from the same torrent), not yet resolved. */
    val subtitles: List<Subtitles.Found>
)

/**
 * Hands the episodes around the one playing from the Library to the player,
 * in memory: a few episodes with their subtitle files don't belong in an
 * Intent. Keyed by the file that started playing, so a stale list is never
 * used for another video. Lost if Android kills the process mid-episode;
 * the player then simply has no next or previous episode.
 */
@Singleton
class UpNext @Inject constructor() {

    /** [current] is the file starting; [next] and [previous] nearest first. */
    data class Queue(
        val current: QueuedEpisode? = null,
        val next: List<QueuedEpisode> = emptyList(),
        val previous: List<QueuedEpisode> = emptyList()
    )

    @Volatile private var forItemId: String? = null
    @Volatile private var queue = Queue()

    fun set(forItemId: String, queue: Queue) {
        this.forItemId = forItemId
        this.queue = queue
    }

    /** The queue for [itemId], or empty if it was set for something else. */
    fun take(itemId: String): Queue = if (itemId == forItemId) queue else Queue()
}
