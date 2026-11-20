package com.abhinavxt.debforge.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioFolderTest {
    @Test fun audioGoesToAudioWhenOrganised() {
        assertEquals("Audio", AudioFolder.subfolderFor("Song.m4a", true))
        assertEquals("Audio", AudioFolder.subfolderFor("Ep 1.weba", true))
    }

    @Test fun nothingWhenNotOrganised() {
        assertEquals("", AudioFolder.subfolderFor("Song.mp3", false))
    }

    @Test fun extensionIsCaseInsensitive() {
        assertTrue(AudioFolder.isAudioName("Song.FLAC"))
        assertTrue(AudioFolder.isAudioName("a.b.Opus"))
    }

    @Test fun nonAudioIsNotAudio() {
        assertFalse(AudioFolder.isAudioName("Movie.mkv"))
        assertFalse(AudioFolder.isAudioName("Movie.en.srt"))
        assertFalse(AudioFolder.isAudioName("mp3"))
        assertFalse(AudioFolder.isAudioName("noext"))
        assertEquals("", AudioFolder.subfolderFor("Movie.mp4", true))
    }
}
