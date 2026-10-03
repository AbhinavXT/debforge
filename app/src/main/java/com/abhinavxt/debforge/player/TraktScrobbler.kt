package com.abhinavxt.debforge.player

import com.abhinavxt.debforge.data.trakt.TraktRepository
import com.abhinavxt.debforge.domain.MediaKind
import com.abhinavxt.debforge.domain.ReleaseNameParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The player's side of Trakt: start when a file plays, pause when the user
 * pauses, stop when it ends or the player closes. Calls run off the
 * activity's lifecycle (a stop sent from onStop must still go out) and in
 * order, and a repeat of the last state for the same file is skipped.
 */
@Singleton
class TraktScrobbler @Inject constructor(private val trakt: TraktRepository) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var last: Pair<String, TraktRepository.Action>? = null

    fun playing(filename: String, positionMs: Long, durationMs: Long) =
        send(TraktRepository.Action.START, filename, positionMs, durationMs)

    fun paused(filename: String, positionMs: Long, durationMs: Long) =
        send(TraktRepository.Action.PAUSE, filename, positionMs, durationMs)

    fun stopped(filename: String, positionMs: Long, durationMs: Long) =
        send(TraktRepository.Action.STOP, filename, positionMs, durationMs)

    private fun send(action: TraktRepository.Action, filename: String, positionMs: Long, durationMs: Long) {
        if (filename.isBlank() || durationMs <= 0) return
        scope.launch {
            lock.withLock {
                // Nothing playing on Trakt's side: a pause or stop has nothing to end.
                val prev = last
                if (prev == filename to action) return@withLock
                if (action != TraktRepository.Action.START && prev?.first != filename) return@withLock
                if (action == TraktRepository.Action.STOP && prev?.second == TraktRepository.Action.STOP) return@withLock
                val info = ReleaseNameParser.parse(filename)
                if (info.kind == MediaKind.OTHER) return@withLock
                if (trakt.scrobble(action, info, positionMs, durationMs)) last = filename to action
            }
        }
    }
}
