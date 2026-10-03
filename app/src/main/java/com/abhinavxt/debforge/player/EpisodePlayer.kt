package com.abhinavxt.debforge.player

import androidx.annotation.OptIn
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi

/**
 * The player as media sessions see it (headset and Bluetooth buttons,
 * watches, the lock screen and notification, Android's media output
 * switcher): "next" and "previous" go to the next or previous episode,
 * which live in [PlayerActivity]'s queue rather than in ExoPlayer's
 * one-item playlist.
 */
@OptIn(UnstableApi::class)
class EpisodePlayer(
    player: Player,
    private val hasNext: () -> Boolean,
    private val hasPrevious: () -> Boolean,
    private val onNext: () -> Unit,
    private val onPrevious: () -> Unit
) : ForwardingPlayer(player) {

    override fun getAvailableCommands(): Player.Commands {
        val next = intArrayOf(Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
        // Previous is always on: without an earlier episode it goes back to the start of this one.
        val b = super.getAvailableCommands().buildUpon()
            .addAll(Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
        if (hasNext()) b.addAll(*next) else b.removeAll(*next)
        return b.build()
    }

    override fun isCommandAvailable(command: Int): Boolean = availableCommands.contains(command)

    // The wrapped player reports its own commands when they change; listeners
    // (the sessions) must see these, with next / previous, instead.
    private val listeners = HashMap<Player.Listener, Player.Listener>()

    override fun addListener(listener: Player.Listener) {
        val fixed = object : Player.Listener by listener {
            override fun onAvailableCommandsChanged(availableCommands: Player.Commands) =
                listener.onAvailableCommandsChanged(this@EpisodePlayer.availableCommands)
        }
        listeners[listener] = fixed
        super.addListener(fixed)
    }

    override fun removeListener(listener: Player.Listener) {
        super.removeListener(listeners.remove(listener) ?: listener)
    }

    /** The episode queue changed: next / previous may have come or gone. */
    fun queueChanged() {
        val commands = availableCommands
        listeners.keys.toList().forEach { it.onAvailableCommandsChanged(commands) }
    }

    override fun hasNextMediaItem(): Boolean = hasNext()
    override fun hasPreviousMediaItem(): Boolean = hasPrevious()

    override fun seekToNext() = next()
    override fun seekToNextMediaItem() = next()
    override fun seekToPrevious() = previous()
    override fun seekToPreviousMediaItem() = previous()

    private fun next() {
        if (hasNext()) onNext()
    }

    /** Like most players: a few seconds in, "previous" restarts this episode first. */
    private fun previous() {
        if (hasPrevious() && currentPosition < RESTART_WITHIN_MS) onPrevious() else seekTo(0)
    }

    private companion object {
        const val RESTART_WITHIN_MS = 5_000L
    }
}
