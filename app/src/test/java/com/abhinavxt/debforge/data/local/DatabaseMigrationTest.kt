package com.abhinavxt.debforge.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.abhinavxt.debforge.domain.DownloadState
import com.abhinavxt.debforge.domain.ProviderId
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
