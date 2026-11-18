package com.abhinavxt.debforge.data.history

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.abhinavxt.debforge.data.local.DebForgeDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HistoryDaoTest {

    private lateinit var db: DebForgeDatabase
    private val dao get() = db.historyDao()

    @Before
    fun open() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, DebForgeDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun close() = db.close()

    private fun entry(id: String, at: Long) = HistoryEntity(
        itemId = id, kind = HistoryKind.VIDEO, origin = HistoryOrigin.CLOUD, provider = "TORBOX", sourceRef = "ref-$id",
        parentRef = null, showKey = null, title = "t-$id", subtitle = null, artworkUrl = null,
        positionMs = 10_000, durationMs = 100_000, playedAt = at
    )

    @Test
    fun replayMovesTheItemToTheTopInsteadOfDuplicating() = runBlocking {
        dao.record(entry("a", 1))
        dao.record(entry("b", 2))
        dao.record(entry("a", 3))
        assertEquals(listOf("a", "b"), dao.all().map { it.itemId })
    }

    @Test
    fun the501stRowPrunesTheOldest() = runBlocking {
        for (i in 1..HISTORY_LIMIT) dao.record(entry("i$i", i.toLong()))
        assertEquals(HISTORY_LIMIT, dao.all().size)
        dao.record(entry("new", 10_000))
        val ids = dao.all().map { it.itemId }
        assertEquals(HISTORY_LIMIT, ids.size)
        assertEquals("new", ids.first())
        assertFalse("i1" in ids)
        assertTrue("i2" in ids)
    }

    @Test
    fun removeAndClear() = runBlocking {
        dao.record(entry("a", 1))
        dao.record(entry("b", 2))
        dao.delete("a")
        assertEquals(listOf("b"), dao.all().map { it.itemId })
        dao.clear()
        assertTrue(dao.all().isEmpty())
    }

    @Test
    fun insertIfNewerKeepsTheNewerRow() = runBlocking {
        dao.record(entry("a", 5))
        dao.insertIfNewer(entry("a", 3).copy(title = "old"))
        assertEquals("t-a", dao.get("a")?.title)
        dao.insertIfNewer(entry("a", 9).copy(title = "newer"))
        assertEquals("newer", dao.get("a")?.title)
    }

    @Test
    fun pausedHistoryWritesNothingAndResumingWritesAgain() = runBlocking {
        val paused = MutableStateFlow(true)
        val history = PlayHistory(dao, paused, CoroutineScope(Dispatchers.IO))
        history.record(entry("a", 1))
        // A write is fire-and-forget: give a would-be write time to land.
        Thread.sleep(300)
        assertTrue(dao.all().isEmpty())

        paused.value = false
        history.record(entry("b", 2))
        withTimeout(5_000) { while (dao.all().isEmpty()) kotlinx.coroutines.delay(20) }
        assertEquals(listOf("b"), dao.all().map { it.itemId })

        paused.value = true
        history.clear() // clearing still works while paused
        assertTrue(dao.all().isEmpty())
    }

    @Test
    fun shortPlaysAreNotRecordedUnlessTheyEnded() {
        assertFalse(shouldRecord(4_999, ended = false))
        assertTrue(shouldRecord(5_000, ended = false))
        assertTrue(shouldRecord(0, ended = true))
    }

    @Test
    fun originFollowsWhereItPlaysFrom() {
        assertEquals(HistoryOrigin.YOUTUBE, historyOriginFor(isYouTube = true, isLocalFile = false))
        assertEquals(HistoryOrigin.LOCAL, historyOriginFor(isYouTube = false, isLocalFile = true))
        assertEquals(HistoryOrigin.CLOUD, historyOriginFor(isYouTube = false, isLocalFile = false))
    }
}
