package com.abhinavxt.debforge.data.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class YouTubePlaylistTest {
    private val v1 = "dQw4w9WgXcQ"
    private val v2 = "abcdefghijk"
    private val meta = YouTubeMeta("Song", "Chan", 212)
    private val base = AudioListsState().create("p", "P")

    @Test fun parseAndFormat() {
        assertEquals(PlaylistEntry.YouTube(v1, YouTubeMode.AUDIO), PlaylistEntry.parse("yt:a:$v1"))
        assertEquals(PlaylistEntry.YouTube(v1, YouTubeMode.VIDEO), PlaylistEntry.parse("yt:v:$v1"))
        assertEquals(PlaylistEntry.Download("12345"), PlaylistEntry.parse("12345"))
        assertEquals("yt:v:$v1", PlaylistEntry.format(PlaylistEntry.YouTube(v1, YouTubeMode.VIDEO)))
        assertEquals("12345", PlaylistEntry.format(PlaylistEntry.Download("12345")))
    }

    @Test fun malformedTokensAreNotYouTube() {
        listOf("yt:x:$v1", "yt:a:", "yt:a:short", "yt:a:${v1}x", "yt:a:dQw4w9WgX!Q", "yt:a$v1", "yt::$v1").forEach {
            assertNull(it, PlaylistEntry.parseYouTube(it))
            assertNull(it, PlaylistEntry.parse(it)?.takeIf { e -> e is PlaylistEntry.YouTube })
        }
        assertNull(PlaylistEntry.parse(" "))
    }

    @Test fun downloadedYouTubeFilesStayDownloads() {
        val id = "yt:Sh4pJYJOT7M:-:140" // a downloaded YouTube audio file's row id
        assertEquals(PlaylistEntry.Download(id), PlaylistEntry.parse(id))
        assertFalse(PlaylistEntry.isYouTubeToken(id))
        assertTrue(base.toggleFavourite(id).favourites.contains(id))
    }

    @Test fun oldJsonDecodesUnchanged() {
        val s = decodeAudioLists("[\"a\"]", "[{\"i\":\"p\",\"n\":\"P\",\"t\":[\"a\",\"b\"]}]")
        assertEquals(AudioListsState(setOf("a"), listOf(Playlist("p", "P", listOf("a", "b")))), s)
        assertTrue(s.ytMeta.isEmpty())
    }

    @Test fun roundTripWithTokensAndMeta() {
        var s = base.add("p", listOf("dl1"))
        s = s.addYouTube("p", v1, YouTubeMode.AUDIO, meta).first
        s = s.addYouTube("p", v2, YouTubeMode.VIDEO, YouTubeMeta("Two", null, null)).first
        val back = decodeAudioLists(encodeFavourites(s), encodePlaylists(s), encodeYouTubeMeta(s))
        assertEquals(s, back)
        assertEquals(listOf("dl1", "yt:a:$v1", "yt:v:$v2"), back.playlists.single().trackIds)
        assertEquals(3, back.typedPlaylists().single().entries.size)
    }

    @Test fun duplicateTokenNotAddedTwice() {
        val (s, r1) = base.addYouTube("p", v1, YouTubeMode.AUDIO, meta)
        val (s2, r2) = s.addYouTube("p", v1, YouTubeMode.AUDIO, meta)
        assertEquals(YouTubeAddResult.ADDED, r1)
        assertEquals(YouTubeAddResult.ALREADY_IN_LIST, r2)
        assertEquals(s, s2)
    }

    @Test fun otherModeSwitchesInPlaceKeepingPosition() {
        var s = base.addYouTube("p", v1, YouTubeMode.AUDIO, meta).first
        s = s.addYouTube("p", v2, YouTubeMode.AUDIO, meta).first
        val (next, r) = s.addYouTube("p", v1, YouTubeMode.VIDEO, YouTubeMeta("New", null, null))
        assertEquals(YouTubeAddResult.MODE_CHANGED, r)
        assertEquals(listOf("yt:v:$v1", "yt:a:$v2"), next.playlists.single().trackIds)
        assertEquals("New", next.ytMeta[v1]?.title)
        // and back again
        assertEquals(listOf("yt:a:$v1", "yt:a:$v2"), next.addYouTube("p", v1, YouTubeMode.AUDIO, meta).first.playlists.single().trackIds)
    }

    @Test fun existingBothModesStayAndExactAddIsAlready() {
        val both = base.copy(playlists = listOf(Playlist("p", "P", listOf("yt:a:$v1", "yt:v:$v1"))))
        assertEquals(YouTubeAddResult.ALREADY_IN_LIST, both.addYouTube("p", v1, YouTubeMode.VIDEO, meta).second)
        assertEquals(YouTubeAddResult.ALREADY_IN_LIST, both.addYouTube("p", v1, YouTubeMode.AUDIO, meta).second)
    }

    @Test fun invalidIdAndMissingList() {
        assertEquals(YouTubeAddResult.INVALID_ID, base.addYouTube("p", "bad", YouTubeMode.AUDIO, meta).second)
        assertEquals(YouTubeAddResult.NO_SUCH_LIST, base.addYouTube("zz", v1, YouTubeMode.AUDIO, meta).second)
    }

    @Test fun capRefusesFurtherAdds() {
        val tokens = (0 until MAX_YOUTUBE_ITEMS).map { "yt:a:" + it.toString().padStart(11, 'x') }
        val full = base.copy(playlists = listOf(Playlist("p", "P", tokens)), ytMeta = emptyMap())
        val (s, r) = full.addYouTube("p", v1, YouTubeMode.AUDIO, meta)
        assertEquals(YouTubeAddResult.LIMIT_REACHED, r)
        assertEquals(full, s)
    }

    @Test fun metaPrunedWhenUnreferenced() {
        val s = base.copy(playlists = listOf(Playlist("p", "P", listOf("yt:a:$v1", "yt:v:$v1"))), ytMeta = mapOf(v1 to meta))
        val one = s.removeEntry("p", PlaylistEntry.YouTube(v1, YouTubeMode.AUDIO))
        assertTrue(v1 in one.ytMeta)
        assertTrue(one.removeEntry("p", PlaylistEntry.YouTube(v1, YouTubeMode.VIDEO)).ytMeta.isEmpty())
        assertTrue(s.delete("p").ytMeta.isEmpty())
    }

    @Test fun favouritesIgnoreTokens() {
        assertEquals(AudioListsState(), AudioListsState().toggleFavourite("yt:a:$v1"))
    }

    @Test fun pruneTracksKeepsTokens() {
        val s = base.add("p", listOf("dl1")).addYouTube("p", v1, YouTubeMode.AUDIO, meta).first.pruneTracks(setOf("dl1"))
        assertEquals(listOf("yt:a:$v1"), s.playlists.single().trackIds)
    }
}
