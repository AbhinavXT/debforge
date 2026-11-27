package com.abhinavxt.debforge.download.watch

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.abhinavxt.debforge.data.local.DebForgeDatabase
import com.abhinavxt.debforge.domain.ProviderId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WatchDaoTest {
    private lateinit var db: DebForgeDatabase
    private val dao get() = db.watchDao()

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), DebForgeDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After
    fun close() = db.close()

    @Test
    fun insertIgnoreKeepsAnAutoDownloadWatch() = runBlocking {
        dao.upsert(WatchEntity(ProviderId.TORBOX, "j", "auto"))
        dao.insertIgnore(WatchEntity(ProviderId.TORBOX, "j", "auto", notifyOnly = true))
        assertEquals(listOf(false), dao.getAll().map { it.notifyOnly })
    }

    @Test
    fun turningTheSettingOffDeletesOnlyNotifyOnlyRows() = runBlocking {
        dao.upsert(WatchEntity(ProviderId.TORBOX, "a", "auto"))
        dao.insertIgnore(WatchEntity(ProviderId.TORBOX, "b", "n", notifyOnly = true))
        dao.deleteNotifyOnly()
        assertEquals(listOf("a"), dao.getAll().map { it.jobRef })
    }
}
