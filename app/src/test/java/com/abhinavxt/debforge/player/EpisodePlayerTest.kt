package com.abhinavxt.debforge.player

import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EpisodePlayerTest {

    /** One item, playing at [positionMs]; no next / previous of its own. */
    private class FakePlayer(var positionMs: Long = 0) : SimpleBasePlayer(Looper.getMainLooper()) {
        var seekedTo: Long? = null
        override fun getState(): State = State.Builder()
            .setAvailableCommands(Player.Commands.Builder().addAll(Player.COMMAND_PLAY_PAUSE, Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM).build())
            .setPlaylist(listOf(MediaItemData.Builder("only").setDurationUs(60_000_000).build()))
            .setContentPositionMs(positionMs)
            .build()

        override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
            seekedTo = positionMs
            return Futures.immediateVoidFuture()
        }
    }

    private var next = true
    private var previous = true
    private var wentNext = 0
    private var wentBack = 0

    private fun wrap(fake: FakePlayer) = EpisodePlayer(fake, { next }, { previous }, { wentNext++ }, { wentBack++ })

    @Test fun advertisesNextOnlyWhenThereIsOne() {
        val p = wrap(FakePlayer())
        assertTrue(p.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT))
        assertTrue(p.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS))
        assertTrue(p.isCommandAvailable(Player.COMMAND_PLAY_PAUSE)) // the real player's commands stay
        next = false
        assertFalse(p.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT))
        assertFalse(p.hasNextMediaItem())
    }

    @Test fun nextGoesToTheNextEpisode() {
        val p = wrap(FakePlayer())
        p.seekToNext()
        p.seekToNextMediaItem()
        assertEquals(2, wentNext)
        next = false
        p.seekToNext()
        assertEquals(2, wentNext)
    }

    @Test fun previousNearTheStartGoesBackAnEpisode() {
        val p = wrap(FakePlayer(positionMs = 2_000))
        p.seekToPrevious()
        assertEquals(1, wentBack)
    }

    @Test fun previousLaterRestartsThisEpisode() {
        val fake = FakePlayer(positionMs = 600_000)
        val p = wrap(fake)
        p.seekToPrevious()
        org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, wentBack)
        assertEquals(0L, fake.seekedTo)
    }

    @Test fun listenersSeeEpisodeCommands() {
        val p = wrap(FakePlayer())
        var seen: Player.Commands? = null
        p.addListener(object : Player.Listener {
            override fun onAvailableCommandsChanged(availableCommands: Player.Commands) { seen = availableCommands }
        })
        p.queueChanged()
        assertTrue(seen!!.contains(Player.COMMAND_SEEK_TO_NEXT))
        next = false
        p.queueChanged()
        assertFalse(seen!!.contains(Player.COMMAND_SEEK_TO_NEXT))
    }
}
