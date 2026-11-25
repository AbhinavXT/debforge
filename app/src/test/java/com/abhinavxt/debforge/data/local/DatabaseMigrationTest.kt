package com.abhinavxt.debforge.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.abhinavxt.debforge.domain.DownloadState
import com.abhinavxt.debforge.domain.ProviderId
import com.abhinavxt.debforge.data.sponsor.SponsorSegmentEntity
import com.abhinavxt.debforge.data.subscriptions.AutoDownload
import com.abhinavxt.debforge.data.subscriptions.FeedItemEntity
import com.abhinavxt.debforge.data.subscriptions.SubscriptionEntity
import com.abhinavxt.debforge.data.subscriptions.SubscriptionKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Every installed version of the app must upgrade to the current database
 * without losing anything. Each old database is built from its schema in
 * app/schemas (v1–v7 were rebuilt from the migrations; Room exports the
 * current one at build time), then opened the way the app opens it. Room
 * checks every table, column, index and foreign key after migrating and
 * throws if anything differs from the entities.
 *
 * Bumping the database version: add the migration to
 * [DebForgeDatabase.ALL_MIGRATIONS], build once so Room writes the new
 * schema file, commit it, and add a data check below if the migration
 * moves data.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DatabaseMigrationTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val latest = DebForgeDatabase.ALL_MIGRATIONS.maxOf { it.endVersion }
    private val opened = mutableListOf<String>()

    @After
    fun cleanUp() {
        opened.forEach { context.deleteDatabase(it) }
    }

    @Test
    fun migrationsFormOneChainFromTheFirstVersion() {
        val steps = DebForgeDatabase.ALL_MIGRATIONS.map { it.startVersion to it.endVersion }
        assertEquals((1 until latest).map { it to it + 1 }, steps)
    }

    @Test
    fun everyVersionHasItsSchemaFile() {
        (1..latest).forEach { assertTrue("missing ${schemaFile(it)}", schemaFile(it).isFile) }
    }

    @Test
    fun everyOldVersionUpgradesToTheCurrentSchema() {
        for (version in 1 until latest) {
            val name = create(version) {}
            openWithRoom(name).close() // Room throws on any mismatch
        }
    }

    @Test
    fun queuedDownloadsFromRealDebridDaysKeepTheirProvider() {
        val name = create(2) { db ->
            db.execSQL(
                "INSERT INTO downloads (rdId, filename, originalLink, downloadUrl, host, filesize, chunksAllowed, state, " +
                    "bytesDownloaded, createdAt, updatedAt) VALUES ('a', 'Movie.mkv', 'link', 'url', 'host', 100, 8, 'PAUSED', 40, 1, 2)"
            )
        }
        val room = openWithRoom(name)
        try {
            val d = runBlocking { room.downloadDao().getById("a") }
            assertEquals(ProviderId.REAL_DEBRID, d?.provider)
            assertEquals(40L, d?.bytesDownloaded)
        } finally {
            room.close()
        }
    }

    @Test
    fun dataFromTheFirstVersionIsReadableAfterUpgrading() {
        val name = create(1) { db ->
            db.execSQL(
                "INSERT INTO downloads (rdId, filename, originalLink, downloadUrl, host, filesize, chunksAllowed, state, " +
                    "bytesDownloaded, partFilePath, createdAt, updatedAt) " +
                    "VALUES ('d1', 'Show.S01E01.mkv', 'link', 'url', 'host', 1000, 4, 'PAUSED', 500, '/tmp/x.part', 1, 2)"
            )
            db.execSQL(
                "INSERT INTO chunks (downloadId, `index`, startByte, endByte, bytesWritten, complete) VALUES ('d1', 0, 0, 999, 500, 0)"
            )
        }
        val room = openWithRoom(name)
        try {
            runBlocking {
                val d = room.downloadDao().getById("d1")
                assertNotNull(d)
                assertEquals(ProviderId.REAL_DEBRID, d!!.provider)
                assertEquals(DownloadState.PAUSED, d.state)
                assertEquals(500L, d.bytesDownloaded)
                assertEquals("/tmp/x.part", d.partFilePath)
                assertEquals(listOf(500L), room.chunkDao().getForDownload("d1").map { it.bytesWritten })
                // Tables added later start empty and work.
                assertNull(room.playbackDao().get("anything"))
            }
        } finally {
            room.close()
        }
    }

    @Test
    fun watchPositionsSavedBeforeV8KeepTheirPlace() {
        val name = create(7) { db ->
            db.execSQL(
                "INSERT INTO playback_positions (itemId, provider, sourceRef, filename, title, showKey, positionMs, durationMs, " +
                    "finished, updatedAt) VALUES ('p1', 'TORBOX', 'ref', 'Show.S01E02.mkv', 'Show · S01E02', 'show:show', 61000, 1800000, 0, 5)"
            )
        }
        val room = openWithRoom(name)
        try {
            val p = runBlocking { room.playbackDao().get("p1") }
            assertEquals(61_000L, p?.positionMs)
            assertNull(p?.parentRef)
        } finally {
            room.close()
        }
    }

    @Test
    fun downloadsFromV10GainEmptyTitleAndArtist() {
        val name = create(10) { db ->
            db.execSQL(
                "INSERT INTO downloads (rdId, provider, filename, originalLink, downloadUrl, host, filesize, chunksAllowed, state, " +
                    "bytesDownloaded, createdAt, updatedAt) VALUES ('d1', 'REAL_DEBRID', 'a.mkv', 'link', 'url', 'host', 100, 8, 'COMPLETED', 100, 1, 2)"
            )
        }
        val room = openWithRoom(name)
        try {
            val d = runBlocking { room.downloadDao().getById("d1") }
            assertEquals("a.mkv", d?.filename)
            assertNull(d?.title)
            assertNull(d?.artist)
        } finally {
            room.close()
        }
    }

    @Test
    fun v11DataSurvivesAndWatchedVideosSeedThePlayHistory() {
        val name = create(11) { db ->
            db.execSQL(
                "INSERT INTO downloads (rdId, provider, filename, originalLink, downloadUrl, host, filesize, chunksAllowed, state, " +
                    "bytesDownloaded, createdAt, updatedAt) VALUES ('d1', 'REAL_DEBRID', 'a.mkv', 'link', 'url', 'host', 100, 8, 'COMPLETED', 100, 1, 2)"
            )
            db.execSQL(
                "INSERT INTO playback_positions (itemId, provider, sourceRef, filename, title, showKey, positionMs, durationMs, " +
                    "finished, updatedAt, parentRef) VALUES ('p1', 'TORBOX', 'ref1', 'Show.S01E02.mkv', 'Show S01E02', 'show:show', " +
                    "61000, 1800000, 0, 500, 'torrent1')"
            )
            db.execSQL(
                "INSERT INTO playback_positions (itemId, provider, sourceRef, filename, title, showKey, positionMs, durationMs, " +
                    "finished, updatedAt, parentRef) VALUES ('ytstream:abc', 'DIRECT', 'https://youtu.be/abc', '', 'A video', NULL, " +
                    "9000, 60000, 0, 700, NULL)"
            )
        }
        val room = openWithRoom(name)
        try {
            runBlocking {
                // Nothing that was there is lost.
                assertEquals(100L, room.downloadDao().getById("d1")?.bytesDownloaded)
                assertEquals(61_000L, room.playbackDao().get("p1")?.positionMs)
                assertEquals(9_000L, room.playbackDao().get("ytstream:abc")?.positionMs)

                val history = room.historyDao().all()
                assertEquals(listOf("ytstream:abc", "p1"), history.map { it.itemId }) // newest first

                val cloud = history.single { it.itemId == "p1" }
                assertEquals("VIDEO", cloud.kind)
                assertEquals("CLOUD", cloud.origin)
                assertEquals("TORBOX", cloud.provider)
                assertEquals("ref1", cloud.sourceRef)
                assertEquals("torrent1", cloud.parentRef)
                assertEquals("show:show", cloud.showKey)
                assertEquals("Show S01E02", cloud.title)
                assertNull(cloud.subtitle)
                assertEquals(61_000L, cloud.positionMs)
                assertEquals(1_800_000L, cloud.durationMs)
                assertEquals(500L, cloud.playedAt)

                val yt = history.single { it.itemId == "ytstream:abc" }
                assertEquals("YOUTUBE", yt.origin)
                assertNull(yt.provider)
                assertEquals("abc", yt.sourceRef)
                assertEquals(700L, yt.playedAt)
            }
        } finally {
            room.close()
        }
    }

    @Test
    fun v12DataSurvivesAndTheNewTablesWork() {
        val name = create(12) { db ->
            db.execSQL(
                "INSERT INTO downloads (rdId, provider, filename, originalLink, downloadUrl, host, filesize, chunksAllowed, state, " +
                    "bytesDownloaded, createdAt, updatedAt) VALUES ('d1', 'REAL_DEBRID', 'a.mkv', 'link', 'url', 'host', 100, 8, 'COMPLETED', 100, 1, 2)"
            )
        }
        val room = openWithRoom(name)
        try {
            runBlocking {
                assertEquals(100L, room.downloadDao().getById("d1")?.bytesDownloaded)
                val dao = room.subscriptionDao()
                assertTrue(dao.all().isEmpty())
                val id = dao.insert(
                    SubscriptionEntity(kind = SubscriptionKind.PODCAST, url = "https://x/feed", title = "Pod", since = 5)
                )
                val dup = SubscriptionEntity(kind = SubscriptionKind.PODCAST, url = "https://x/feed", title = "Dup", since = 6)
                assertEquals(-1L, dao.insert(dup))
                assertEquals(AutoDownload.OFF, dao.get(id)?.autoDownload)
                dao.insertItems(listOf(FeedItemEntity(id, "g1", "Ep 1", "https://x/1.mp3", 10, null, null, isNew = true, queued = false)))
                assertEquals(1, dao.observeNew().first().size)
                dao.delete(id)
                assertTrue(dao.items(id).isEmpty()) // cascade
                room.sponsorSegmentDao().upsert(SponsorSegmentEntity("vid", "[]", 1))
                assertEquals("[]", room.sponsorSegmentDao().get("vid")?.json)
            }
        } finally {
            room.close()
        }
    }

    @Test
    fun v13RowsUpgradeWithNullAlbumFieldsAndBackfillQueriesWork() {
        val name = create(13) { db ->
            db.execSQL(
                "INSERT INTO downloads (rdId, provider, filename, originalLink, downloadUrl, host, filesize, chunksAllowed, state, " +
                    "bytesDownloaded, title, artist, createdAt, updatedAt) VALUES ('l1', 'LOCAL', 'a.mp3', 'p', '', '', 1, 1, 'COMPLETED', 1, 'Song', 'Art', 1, 2)"
            )
        }
        val room = openWithRoom(name)
        try {
            runBlocking {
                val dao = room.downloadDao()
                val row = dao.getById("l1")!!
                assertNull(row.album); assertNull(row.albumArtist); assertNull(row.trackNo)
                assertEquals(listOf("l1"), dao.localUnchecked(10).map { it.id })
                assertEquals(1, dao.setLocalAlbum("l1", "Alb", "AA", 3))
                val after = dao.getById("l1")!!
                assertEquals(listOf("Song", "Art", "Alb", "AA", 3), listOf(after.title, after.artist, after.album, after.albumArtist, after.trackNo))
                assertEquals(0, dao.setLocalAlbum("l1", "Other", null, null)) // already read
                assertTrue(dao.localUnchecked(10).isEmpty())
            }
        } finally {
            room.close()
        }
    }

    @Test
    fun v14DataSurvivesAndBookmarksWork() {
        val name = create(14) { db ->
            db.execSQL(
                "INSERT INTO downloads (rdId, provider, filename, originalLink, downloadUrl, host, filesize, chunksAllowed, state, " +
                    "bytesDownloaded, createdAt, updatedAt) VALUES ('a', 'LOCAL', 'a.mp3', 'p', '', '', 1, 1, 'COMPLETED', 1, 1, 2)"
            )
        }
        val room = openWithRoom(name)
        try {
            runBlocking {
                assertNotNull(room.downloadDao().getById("a"))
                val dao = room.bookmarkDao()
                dao.insert("a", 9000, null, 1)
                val id = dao.insert("a", 3000, "Intro", 2)
                dao.rename(id, null)
                assertEquals(listOf(3000L, 9000L), dao.observe("a").first().map { it.positionMs })
                dao.delete(id)
                assertEquals(1, dao.count())
            }
        } finally {
            room.close()
        }
    }

    @Test
    fun refreshingAndTrimmingFeedItemsKeepsNewAndQueuedState() {
        val room = openWithRoom(create(13) {})
        try {
            runBlocking {
                val dao = room.subscriptionDao()
                val id = dao.insert(SubscriptionEntity(kind = SubscriptionKind.PODCAST, url = "https://x/feed", title = "Pod", since = 5))
                fun item(key: String, at: Long, isNew: Boolean, queued: Boolean, title: String = key) =
                    FeedItemEntity(id, key, title, "https://x/$key.mp3", at, null, null, isNew = isNew, queued = queued)
                dao.insertItems(listOf(item("a", 10, isNew = true, queued = false), item("b", 20, isNew = false, queued = true)))

                // IGNORE insert keeps the flags of existing rows
                dao.insertItems(listOf(item("a", 10, isNew = false, queued = false), item("b", 20, isNew = true, queued = false)))
                assertEquals(listOf(true, false), listOf("a", "b").map { k -> dao.items(id).first { it.itemKey == k }.isNew })
                assertTrue(dao.items(id).first { it.itemKey == "b" }.queued)

                // metadata update leaves the flags alone
                dao.updateItemMetadata(id, "a", "Renamed", "https://x/new.mp3", "https://x/t.jpg", 99, 11)
                val a = dao.items(id).first { it.itemKey == "a" }
                assertEquals("Renamed", a.title)
                assertEquals("https://x/new.mp3", a.mediaUrl)
                assertEquals("https://x/t.jpg", a.thumbUrl)
                assertEquals(99L, a.durationSec)
                assertEquals(11L, a.publishedAt)
                assertTrue(a.isNew)
                assertEquals(false, a.queued)

                // upsert does both
                dao.upsertItems(listOf(item("a", 12, isNew = false, queued = true, title = "Again"), item("c", 30, isNew = true, queued = false)))
                val again = dao.items(id).first { it.itemKey == "a" }
                assertEquals("Again", again.title)
                assertTrue(again.isNew)
                assertEquals(false, again.queued)
                assertEquals(3, dao.items(id).size)

                // trim never deletes new or queued items
                dao.insertItems(listOf(item("old", 1, isNew = false, queued = false), item("older", 0, isNew = false, queued = false)))
                dao.trimItems(id, 1)
                assertEquals(setOf("a", "b", "c"), dao.itemKeys(id).toSet()) // b queued, c new; old ones gone
                dao.markSeen(id, "a")
                dao.markSeen(id, "c")
                dao.trimItems(id, 1)
                assertEquals(setOf("b", "c"), dao.itemKeys(id).toSet()) // newest (c) kept, b still queued

                // unseen counts
                dao.insertItems(listOf(item("d", 40, isNew = true, queued = false)))
                assertEquals(1, dao.observeUnseenCount().first())
                assertEquals(1, dao.observeUnseenCount(id).first())
                dao.markAllSeen(id)
                assertEquals(0, dao.observeUnseenCount().first())
            }
        } finally {
            room.close()
        }
    }

    @Test
    fun metadataRefreshKeepsTheStoredDateWhenTheNewOneIsUnknownAndPendingItemsFilter() {
        val room = openWithRoom(create(13) {})
        try {
            runBlocking {
                val dao = room.subscriptionDao()
                fun sub(url: String, auto: AutoDownload, since: Long) =
                    SubscriptionEntity(kind = SubscriptionKind.YT_CHANNEL, url = url, title = url, autoDownload = auto, since = since)
                val on = dao.insert(sub("on", AutoDownload.AUDIO, 100))
                val off = dao.insert(sub("off", AutoDownload.OFF, 100))
                fun item(id: Long, key: String, at: Long, isNew: Boolean = true, queued: Boolean = false) =
                    FeedItemEntity(id, key, key, null, at, null, null, isNew = isNew, queued = queued)
                dao.insertItems(listOf(
                    item(on, "new1", 300), item(on, "new2", 400), item(on, "handled", 500, queued = true),
                    item(on, "seen", 600, isNew = false), item(on, "undated", 0), item(on, "old", 100), item(on, "older", 50),
                    item(off, "o1", 300),
                ))
                assertEquals(listOf("new2", "new1"), dao.pendingItems(on).map { it.itemKey })
                assertTrue(dao.pendingItems(off).isEmpty())

                // date-keeping CASE: 0 keeps the stored date, a real date replaces it
                dao.updateItemMetadata(on, "new1", "t", null, null, null, 0)
                assertEquals(300L, dao.items(on).first { it.itemKey == "new1" }.publishedAt)
                dao.updateItemMetadata(on, "new1", "t", null, null, null, 350)
                assertEquals(350L, dao.items(on).first { it.itemKey == "new1" }.publishedAt)
                dao.updateItemMetadata(on, "undated", "t", null, null, null, 450)
                assertEquals(450L, dao.items(on).first { it.itemKey == "undated" }.publishedAt)
            }
        } finally {
            room.close()
        }
    }

    @Test
    fun staleSponsorSegmentsAreListedNotDeleted() {
        val room = openWithRoom(create(13) {})
        try {
            runBlocking {
                val dao = room.sponsorSegmentDao()
                dao.upsert(SponsorSegmentEntity("old", "[]", 1))
                dao.upsert(SponsorSegmentEntity("fresh", "[]", 100))
                assertEquals(listOf("old"), dao.stale(50).map { it.videoId })
                assertNotNull(dao.get("old"))
            }
        } finally {
            room.close()
        }
    }

    // --- helpers ---------------------------------------------------------------

    private fun schemaFile(version: Int) =
        File("schemas/${DebForgeDatabase::class.java.name}/$version.json")

    /** A database exactly as version [version] of the app left it, plus whatever [fill] writes. */
    private fun create(version: Int, fill: (SQLiteDatabase) -> Unit): String {
        val name = "v$version-${opened.size}"
        opened += name
        val schema = JSONObject(schemaFile(version).readText()).getJSONObject("database")
        val file = context.getDatabasePath(name).apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("PRAGMA foreign_keys = ON")
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val e = entities.getJSONObject(i)
                val table = e.getString("tableName")
                db.execSQL(e.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = e.optJSONArray("indices") ?: continue
                for (j in 0 until indices.length()) {
                    db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                }
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
            fill(db)
            db.version = version
        }
        return name
    }

    private fun openWithRoom(name: String): DebForgeDatabase =
        Room.databaseBuilder(context, DebForgeDatabase::class.java, name)
            .addMigrations(*DebForgeDatabase.ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()
            .also { it.openHelper.writableDatabase } // open now: runs the migrations and Room's checks
}
