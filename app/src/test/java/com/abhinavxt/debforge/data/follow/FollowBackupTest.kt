package com.abhinavxt.debforge.data.follow

import com.abhinavxt.debforge.domain.Follow
import com.abhinavxt.debforge.domain.ProviderId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FollowBackupTest {

    private val bear = Follow(ProviderId.TORBOX, "show:the bear", "The Bear", 1_700_000_000_000, "1080p", setOf("S03E01"))
    private val shogun = Follow(ProviderId.PREMIUMIZE, "show:shogun", "Shōgun", 1_700_000_500_000, null, emptySet())

    @Test fun roundTrip() {
        val json = FollowBackup.toJson(listOf(bear, shogun))
        assertEquals(listOf(bear, shogun), FollowBackup.fromJson(json))
    }

    @Test fun numbersAsDoubleLikeMoshi() {
        val raw = listOf(mapOf("provider" to "TORBOX", "showKey" to "show:x", "title" to "X", "since" to 1.7E12, "seen" to listOf("S01E01")))
        val f = FollowBackup.fromJson(raw).single()
        assertEquals(1_700_000_000_000L, f.since)
        assertEquals(setOf("S01E01"), f.seen)
        assertEquals(null, f.resolution)
    }

    @Test fun brokenEntriesSkipped() {
        val raw = listOf(
            mapOf("provider" to "NOT_A_SERVICE", "showKey" to "show:a", "title" to "A", "since" to 1.0),
            mapOf("provider" to "TORBOX", "showKey" to "", "title" to "B", "since" to 1.0),
            mapOf("provider" to "TORBOX", "title" to "C", "since" to 1.0),
            "garbage"
        )
        assertTrue(FollowBackup.fromJson(raw).isEmpty())
        assertTrue(FollowBackup.fromJson(null).isEmpty())
    }

    @Test fun mergeAddsNewAndCombinesSeen() {
        val onPhone = bear.copy(seen = setOf("S03E02"), since = 42)
        val (merged, added) = FollowBackup.merge(listOf(onPhone), listOf(bear, shogun))
        assertEquals(1, added)
        val mergedBear = merged.first { it.showKey == bear.showKey }
        assertEquals(setOf("S03E01", "S03E02"), mergedBear.seen)
        assertEquals(42L, mergedBear.since) // the phone's own entry wins
        assertEquals(2, merged.size)
    }
}
