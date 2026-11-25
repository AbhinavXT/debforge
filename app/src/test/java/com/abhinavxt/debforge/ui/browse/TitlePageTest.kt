package com.abhinavxt.debforge.ui.browse

import com.abhinavxt.debforge.data.playback.PlaybackEntity
import com.abhinavxt.debforge.domain.DownloadItem
import com.abhinavxt.debforge.domain.ProviderId
import com.abhinavxt.debforge.domain.ReleaseNameParser
import com.abhinavxt.debforge.domain.TraktMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TitlePageTest {
    private fun file(name: String) = ParsedItem(
        DownloadItem(
            id = name, provider = ProviderId.TORBOX, filename = name, sourceRef = name, downloadUrl = null,
            host = "", filesize = 1, maxConnections = 1, addedAt = null
        ),
        ReleaseNameParser.parse(name)
    )

    private fun pos(f: ParsedItem, ms: Long, finished: Boolean = false, at: Long = 1) = PlaybackEntity(
        itemId = f.item.id, provider = ProviderId.TORBOX, sourceRef = "", filename = f.item.filename, title = "",
        showKey = null, positionMs = ms, durationMs = 3_600_000, finished = finished, updatedAt = at
    )

    private val e1 = file("The.Bear.S01E01.1080p.WEB-DL.mkv")
    private val e2 = file("The.Bear.S01E02.1080p.WEB-DL.mkv")
    private val e3 = file("The.Bear.S01E03.1080p.WEB-DL.mkv")
    private val shows = listOf(e1, e2, e3)

    @Test fun freshShowPlaysFirstEpisode() =
        assertEquals(PlayTarget(e1, false), playTarget(shows, emptyMap(), emptySet()))

    @Test fun showSkipsWatchedEpisodes() {
        val p = mapOf(e1.item.id to pos(e1, 3_000_000, finished = true))
        assertEquals(PlayTarget(e2, false), playTarget(shows, p, emptySet()))
    }

    @Test fun savedPositionResumes() {
        val p = mapOf(e2.item.id to pos(e2, 600_000))
        assertEquals(PlayTarget(e2, true), playTarget(shows, p, emptySet()))
    }

    @Test fun latestStartedEpisodeWins() {
        val p = mapOf(e1.item.id to pos(e1, 600_000, at = 1), e3.item.id to pos(e3, 600_000, at = 9))
        assertEquals(PlayTarget(e3, true), playTarget(shows, p, emptySet()))
    }

    @Test fun peekIsNotAResume() {
        val p = mapOf(e1.item.id to pos(e1, 5_000))
        assertEquals(PlayTarget(e1, false), playTarget(shows, p, emptySet()))
    }

    @Test fun allWatchedStartsOver() {
        val p = shows.associate { it.item.id to pos(it, 3_500_000, finished = true) }
        assertEquals(PlayTarget(e1, false), playTarget(shows, p, emptySet()))
    }

    @Test fun nothingPlayableGivesNoTarget() =
        assertNull(playTarget(listOf(file("notes.txt")), emptyMap(), emptySet()))

    @Test fun watchedOnTrakt() {
        val key = TraktMatch.keyOf(e1.info)!!
        assertTrue(isWatched(e1.info, null, setOf(key)))
        assertFalse(isWatched(e1.info, pos(e1, 600_000), setOf(key)))
        assertFalse(isWatched(e2.info, null, setOf(key)))
    }
}
