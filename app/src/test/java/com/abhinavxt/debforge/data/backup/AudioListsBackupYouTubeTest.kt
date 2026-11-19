package com.abhinavxt.debforge.data.backup

import com.abhinavxt.debforge.data.playback.AudioListsState
import com.abhinavxt.debforge.data.playback.Playlist
import com.abhinavxt.debforge.data.playback.YouTubeMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioListsBackupYouTubeTest {
    private val v1 = "dQw4w9WgXcQ"
    private val v2 = "abcdefghijk"
    private val state = AudioListsState(
        linkedSetOf("a"),
        listOf(Playlist("p", "P", listOf("a", "yt:a:$v1", "yt:v:$v2"))),
        mapOf(v1 to YouTubeMeta("One", "Chan", 60), v2 to YouTubeMeta("Two", null, null))
    )

    @Test fun roundTrip() {
        assertEquals(state, AudioListsBackup.fromJson(AudioListsBackup.toJson(state)))
    }

    @Test fun oldBackupWithoutYmImports() {
        val old = mapOf("v" to 1, "fav" to listOf("a"), "lists" to listOf(mapOf("i" to "p", "n" to "P", "t" to listOf("a", "b"))))
        val s = AudioListsBackup.fromJson(old)
        assertEquals(listOf("a", "b"), s.playlists.single().trackIds)
        assertTrue(s.ytMeta.isEmpty())
    }

    @Test fun junkYmIsIgnored() {
        val s = AudioListsBackup.fromJson(mapOf("ym" to mapOf("bad" to mapOf("t" to "x"), v1 to 5, v2 to mapOf("t" to "ok"))))
        assertEquals(mapOf(v2 to YouTubeMeta("ok", null, null)), s.ytMeta)
    }

    @Test fun mergeUnionsMetaLocalWins() {
        val cur = AudioListsState(
            playlists = listOf(Playlist("p", "P", listOf("yt:a:$v1"))),
            ytMeta = mapOf(v1 to YouTubeMeta("Local", null, null))
        )
        val inc = AudioListsState(
            playlists = listOf(Playlist("p", "P", listOf("yt:a:$v1", "yt:v:$v2"))),
            ytMeta = mapOf(v1 to YouTubeMeta("Remote", "c", 1), v2 to YouTubeMeta("Two", null, null))
        )
        val m = AudioListsBackup.merge(cur, inc).state
        assertEquals(listOf("yt:a:$v1", "yt:v:$v2"), m.playlists.single().trackIds)
        assertEquals(YouTubeMeta("Local", null, null), m.ytMeta[v1])
        assertEquals(YouTubeMeta("Two", null, null), m.ytMeta[v2])
    }

    @Test fun mergeDropsUnreferencedMeta() {
        val m = AudioListsBackup.merge(AudioListsState(), AudioListsState(ytMeta = mapOf(v1 to YouTubeMeta("x", null, null))))
        assertTrue(m.state.ytMeta.isEmpty())
    }

    @Test fun favouriteTokensDroppedOnImport() {
        val s = AudioListsBackup.fromJson(mapOf("fav" to listOf("a", "yt:a:$v1")))
        assertEquals(setOf("a"), s.favourites)
    }
}
