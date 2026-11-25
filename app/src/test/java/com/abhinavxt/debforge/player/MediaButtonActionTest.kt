package com.abhinavxt.debforge.player

import android.view.KeyEvent
import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class MediaButtonActionTest {
    private fun act(key: Int, action: Int = KeyEvent.ACTION_DOWN, repeat: Int = 0, playing: Boolean = false, video: Boolean = false) =
        mediaButtonAction(key, action, repeat, playing, video)

    private val playKeys = listOf(KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK)

    @Test fun playKeysStart() = playKeys.forEach { assertEquals(WidgetAction.TOGGLE, act(it)) }

    @Test fun keyUpRepeatsAndVideoDoNothing() = playKeys.forEach {
        assertNull(act(it, action = KeyEvent.ACTION_UP))
        assertNull(act(it, action = 2))
        assertNull(act(it, repeat = 1))
        assertNull(act(it, repeat = -1))
        assertNull(act(it, video = true))
    }

    @Test fun whilePlayingOnlyTheToggleKeysAct() {
        assertNull(act(KeyEvent.KEYCODE_MEDIA_PLAY, playing = true))
        assertEquals(WidgetAction.TOGGLE, act(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, playing = true))
        assertEquals(WidgetAction.TOGGLE, act(KeyEvent.KEYCODE_HEADSETHOOK, playing = true))
    }

    @Test fun otherKeysDoNothing() = listOf(
        KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP, KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS,
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_UNKNOWN, -1
    ).forEach { assertNull(act(it)) }

    @Test fun otherAppsLoseOnlyPlaylistEdits() {
        val all = Player.Commands.Builder().addAllCommands().build()
        assertEquals(all, playerCommandsFor("own", "own"))
        val other = playerCommandsFor("com.android.bluetooth", "own")
        assertFalse(other.contains(Player.COMMAND_CHANGE_MEDIA_ITEMS))
        assertFalse(other.contains(Player.COMMAND_SET_MEDIA_ITEM))
        assertEquals(all.size() - 2, other.size())
        assertTrue(other.contains(Player.COMMAND_PLAY_PAUSE))
        assertTrue(other.contains(Player.COMMAND_SEEK_TO_NEXT))
    }
}
