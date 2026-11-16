package com.abhinavxt.debforge.data.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// org.json is a stub in plain JVM unit tests, so this needs Robolectric.
@RunWith(RobolectricTestRunner::class)
class AudioListsTest {
    private val base = AudioListsState().create("p1", "  Road  ")

    @Test fun createTrimsAndIgnoresBlank() {
        assertEquals("Road", base.playlists.single().name)
        assertEquals(base, base.create("p2", "   "))
    }

    @Test fun renameAndDelete() {
        assertEquals("Gym", base.rename("p1", "Gym").playlists.single().name)
        assertEquals("Road", base.rename("p1", " ").playlists.single().name)
        assertTrue(base.delete("p1").playlists.isEmpty())
    }

    @Test fun addKeepsOrderWithoutDuplicates() {
        val s = base.add("p1", listOf("b", "a")).add("p1", listOf("a", "c", "c"))
        assertEquals(listOf("b", "a", "c"), s.playlists.single().trackIds)
    }

    @Test fun removeDropsTrack() {
        assertEquals(listOf("a"), base.add("p1", listOf("a", "b")).remove("p1", "b").playlists.single().trackIds)
    }

    @Test fun toggleFavouriteOnAndOff() {
        val on = AudioListsState().toggleFavourite("x")
        assertEquals(setOf("x"), on.favourites)
        assertTrue(on.toggleFavourite("x").favourites.isEmpty())
    }

    @Test fun roundTrip() {
        val s = base.add("p1", listOf("a", "b")).toggleFavourite("f1").toggleFavourite("f2")
        assertEquals(s, decodeAudioLists(encodeFavourites(s), encodePlaylists(s)))
    }

    @Test fun corruptOrMissingJsonIsEmpty() {
        assertEquals(AudioListsState(), decodeAudioLists("{nope", "[{\"i\":1"))
        assertEquals(AudioListsState(), decodeAudioLists(null, null))
    }
}
