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

    @Test fun duplicateNamesGetNumericSuffix() {
        val s = base.create("p2", " road ").create("p3", "ROAD")
        assertEquals(listOf("Road", "road (2)", "ROAD (3)"), s.playlists.map { it.name })
    }

    @Test fun renameAvoidsOthersButNotItself() {
        val s = base.create("p2", "Gym")
        assertEquals("road (2)", s.rename("p2", "road").playlists[1].name)
        assertEquals("ROAD", s.rename("p1", "ROAD").playlists[0].name)
    }

    @Test fun pruneDropsIdsEverywhere() {
        val s = base.add("p1", listOf("a", "b")).toggleFavourite("a").toggleFavourite("c").pruneTracks(setOf("a", "c"))
        assertEquals(listOf("b"), s.playlists.single().trackIds)
        assertTrue(s.favourites.isEmpty())
    }

    @Test fun oneBadPlaylistDoesNotWipeTheOthers() {
        val raw = """[{"i":"p1","n":"Good","t":["a"]},{"i":"p2"},"junk",{"i":"p3","n":"Also","t":["b"]}]"""
        val s = decodeAudioLists("[]", raw)
        assertEquals(listOf("Good", "Also"), s.playlists.map { it.name })
        assertEquals(listOf("a"), s.playlists[0].trackIds)
    }

    @Test fun oneBadFavouriteDoesNotWipeTheOthers() {
        val s = decodeAudioLists("""["a",{"x":1},"b"]""", "[]")
        assertEquals(setOf("a", "b"), s.favourites)
    }
}
