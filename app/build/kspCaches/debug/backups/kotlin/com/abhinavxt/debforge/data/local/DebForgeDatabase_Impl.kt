package com.abhinavxt.debforge.`data`.local

import androidx.room.InvalidationTracker
import androidx.room.RoomOpenDelegate
import androidx.room.migration.AutoMigrationSpec
import androidx.room.migration.Migration
import androidx.room.util.TableInfo
import androidx.room.util.TableInfo.Companion.read
import androidx.room.util.dropFtsSyncTriggers
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import com.abhinavxt.debforge.`data`.metadata.MediaMetaDao
import com.abhinavxt.debforge.`data`.metadata.MediaMetaDao_Impl
import com.abhinavxt.debforge.`data`.playback.PlaybackDao
import com.abhinavxt.debforge.`data`.playback.PlaybackDao_Impl
import com.abhinavxt.debforge.`data`.usage.UsageDao
import com.abhinavxt.debforge.`data`.usage.UsageDao_Impl
import com.abhinavxt.debforge.download.watch.WatchDao
import com.abhinavxt.debforge.download.watch.WatchDao_Impl
import javax.`annotation`.processing.Generated
import kotlin.Lazy
import kotlin.String
import kotlin.Suppress
import kotlin.collections.List
import kotlin.collections.Map
import kotlin.collections.MutableList
import kotlin.collections.MutableMap
import kotlin.collections.MutableSet
import kotlin.collections.Set
import kotlin.collections.mutableListOf
import kotlin.collections.mutableMapOf
import kotlin.collections.mutableSetOf
import kotlin.reflect.KClass

@Generated(value = ["androidx.room.RoomProcessor"])
@Suppress(names = ["UNCHECKED_CAST", "DEPRECATION", "REDUNDANT_PROJECTION", "REMOVAL"])
public class DebForgeDatabase_Impl : DebForgeDatabase() {
  private val _downloadDao: Lazy<DownloadDao> = lazy {
    DownloadDao_Impl(this)
  }

  private val _chunkDao: Lazy<ChunkDao> = lazy {
    ChunkDao_Impl(this)
  }

  private val _mediaMetaDao: Lazy<MediaMetaDao> = lazy {
    MediaMetaDao_Impl(this)
  }

  private val _watchDao: Lazy<WatchDao> = lazy {
    WatchDao_Impl(this)
  }

  private val _usageDao: Lazy<UsageDao> = lazy {
    UsageDao_Impl(this)
  }

  private val _playbackDao: Lazy<PlaybackDao> = lazy {
    PlaybackDao_Impl(this)
  }

  protected override fun createOpenDelegate(): RoomOpenDelegate {
    val _openDelegate: RoomOpenDelegate = object : RoomOpenDelegate(8,
        "4ad24206738f018b9c276a0afbdd1345", "022a56bbbef59bf66bdd863e16fa3085") {
      public override fun createAllTables(connection: SQLiteConnection) {
        connection.execSQL("CREATE TABLE IF NOT EXISTS `downloads` (`rdId` TEXT NOT NULL, `provider` TEXT NOT NULL DEFAULT 'REAL_DEBRID', `filename` TEXT NOT NULL, `originalLink` TEXT NOT NULL, `downloadUrl` TEXT NOT NULL, `host` TEXT NOT NULL, `filesize` INTEGER NOT NULL, `chunksAllowed` INTEGER NOT NULL, `state` TEXT NOT NULL, `bytesDownloaded` INTEGER NOT NULL, `partFilePath` TEXT, `finalFilePath` TEXT, `supportsRanges` INTEGER, `errorMessage` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`rdId`))")
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_downloads_state` ON `downloads` (`state`)")
        connection.execSQL("CREATE TABLE IF NOT EXISTS `chunks` (`downloadId` TEXT NOT NULL, `index` INTEGER NOT NULL, `startByte` INTEGER NOT NULL, `endByte` INTEGER NOT NULL, `bytesWritten` INTEGER NOT NULL, `complete` INTEGER NOT NULL, PRIMARY KEY(`downloadId`, `index`), FOREIGN KEY(`downloadId`) REFERENCES `downloads`(`rdId`) ON UPDATE NO ACTION ON DELETE CASCADE )")
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_chunks_downloadId` ON `chunks` (`downloadId`)")
        connection.execSQL("CREATE TABLE IF NOT EXISTS `media_meta` (`cacheKey` TEXT NOT NULL, `found` INTEGER NOT NULL, `title` TEXT, `year` INTEGER, `posterPath` TEXT, `backdropPath` TEXT, `overview` TEXT, `fetchedAt` INTEGER NOT NULL, PRIMARY KEY(`cacheKey`))")
        connection.execSQL("CREATE TABLE IF NOT EXISTS `watches` (`provider` TEXT NOT NULL, `jobRef` TEXT NOT NULL, `name` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`provider`, `jobRef`))")
        connection.execSQL("CREATE TABLE IF NOT EXISTS `data_usage` (`day` TEXT NOT NULL, `provider` TEXT NOT NULL, `metered` INTEGER NOT NULL, `bytes` INTEGER NOT NULL, PRIMARY KEY(`day`, `provider`, `metered`))")
        connection.execSQL("CREATE TABLE IF NOT EXISTS `playback_positions` (`itemId` TEXT NOT NULL, `provider` TEXT NOT NULL, `sourceRef` TEXT NOT NULL, `filename` TEXT NOT NULL, `title` TEXT NOT NULL, `showKey` TEXT, `positionMs` INTEGER NOT NULL, `durationMs` INTEGER NOT NULL, `finished` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `parentRef` TEXT, PRIMARY KEY(`itemId`))")
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_playback_positions_updatedAt` ON `playback_positions` (`updatedAt`)")
        connection.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
        connection.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '4ad24206738f018b9c276a0afbdd1345')")
      }

      public override fun dropAllTables(connection: SQLiteConnection) {
        connection.execSQL("DROP TABLE IF EXISTS `downloads`")
        connection.execSQL("DROP TABLE IF EXISTS `chunks`")
        connection.execSQL("DROP TABLE IF EXISTS `media_meta`")
        connection.execSQL("DROP TABLE IF EXISTS `watches`")
        connection.execSQL("DROP TABLE IF EXISTS `data_usage`")
        connection.execSQL("DROP TABLE IF EXISTS `playback_positions`")
      }

      public override fun onCreate(connection: SQLiteConnection) {
      }

      public override fun onOpen(connection: SQLiteConnection) {
        connection.execSQL("PRAGMA foreign_keys = ON")
        internalInitInvalidationTracker(connection)
      }

      public override fun onPreMigrate(connection: SQLiteConnection) {
        dropFtsSyncTriggers(connection)
      }

      public override fun onPostMigrate(connection: SQLiteConnection) {
      }

      public override fun onValidateSchema(connection: SQLiteConnection):
          RoomOpenDelegate.ValidationResult {
        val _columnsDownloads: MutableMap<String, TableInfo.Column> = mutableMapOf()
        _columnsDownloads.put("rdId", TableInfo.Column("rdId", "TEXT", true, 1, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsDownloads.put("provider", TableInfo.Column("provider", "TEXT", true, 0,
            "'REAL_DEBRID'", TableInfo.CREATED_FROM_ENTITY))
        _columnsDownloads.put("filename", TableInfo.Column("filename", "TEXT", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsDownloads.put("originalLink", TableInfo.Column("originalLink", "TEXT", true, 0,
            null, TableInfo.CREATED_FROM_ENTITY))
        _columnsDownloads.put("downloadUrl", TableInfo.Column("downloadUrl", "TEXT", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsDownloads.put("host", TableInfo.Column("host", "TEXT", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsDownloads.put("filesize", TableInfo.Column("filesize", "INTEGER", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsDownloads.put("chunksAllowed", TableInfo.Column("chunksAllowed", "INTEGER", true, 0,
            null, TableInfo.CREATED_FROM_ENTITY))
        _columnsDownloads.put("state", TableInfo.Column("state", "TEXT", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsDownloads.put("bytesDownloaded", TableInfo.Column("bytesDownloaded", "INTEGER",
            true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsDownloads.put("partFilePath", TableInfo.Column("partFilePath", "TEXT", false, 0,
            null, TableInfo.CREATED_FROM_ENTITY))
        _columnsDownloads.put("finalFilePath", TableInfo.Column("finalFilePath", "TEXT", false, 0,
            null, TableInfo.CREATED_FROM_ENTITY))
        _columnsDownloads.put("supportsRanges", TableInfo.Column("supportsRanges", "INTEGER", false,
            0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsDownloads.put("errorMessage", TableInfo.Column("errorMessage", "TEXT", false, 0,
            null, TableInfo.CREATED_FROM_ENTITY))
        _columnsDownloads.put("createdAt", TableInfo.Column("createdAt", "INTEGER", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsDownloads.put("updatedAt", TableInfo.Column("updatedAt", "INTEGER", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        val _foreignKeysDownloads: MutableSet<TableInfo.ForeignKey> = mutableSetOf()
        val _indicesDownloads: MutableSet<TableInfo.Index> = mutableSetOf()
        _indicesDownloads.add(TableInfo.Index("index_downloads_state", false, listOf("state"),
            listOf("ASC")))
        val _infoDownloads: TableInfo = TableInfo("downloads", _columnsDownloads,
            _foreignKeysDownloads, _indicesDownloads)
        val _existingDownloads: TableInfo = read(connection, "downloads")
        if (!_infoDownloads.equals(_existingDownloads)) {
          return RoomOpenDelegate.ValidationResult(false, """
              |downloads(com.abhinavxt.debforge.data.local.DownloadEntity).
              | Expected:
              |""".trimMargin() + _infoDownloads + """
              |
              | Found:
              |""".trimMargin() + _existingDownloads)
        }
        val _columnsChunks: MutableMap<String, TableInfo.Column> = mutableMapOf()
        _columnsChunks.put("downloadId", TableInfo.Column("downloadId", "TEXT", true, 1, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsChunks.put("index", TableInfo.Column("index", "INTEGER", true, 2, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsChunks.put("startByte", TableInfo.Column("startByte", "INTEGER", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsChunks.put("endByte", TableInfo.Column("endByte", "INTEGER", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsChunks.put("bytesWritten", TableInfo.Column("bytesWritten", "INTEGER", true, 0,
            null, TableInfo.CREATED_FROM_ENTITY))
        _columnsChunks.put("complete", TableInfo.Column("complete", "INTEGER", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        val _foreignKeysChunks: MutableSet<TableInfo.ForeignKey> = mutableSetOf()
        _foreignKeysChunks.add(TableInfo.ForeignKey("downloads", "CASCADE", "NO ACTION",
            listOf("downloadId"), listOf("rdId")))
        val _indicesChunks: MutableSet<TableInfo.Index> = mutableSetOf()
        _indicesChunks.add(TableInfo.Index("index_chunks_downloadId", false, listOf("downloadId"),
            listOf("ASC")))
        val _infoChunks: TableInfo = TableInfo("chunks", _columnsChunks, _foreignKeysChunks,
            _indicesChunks)
        val _existingChunks: TableInfo = read(connection, "chunks")
        if (!_infoChunks.equals(_existingChunks)) {
          return RoomOpenDelegate.ValidationResult(false, """
              |chunks(com.abhinavxt.debforge.data.local.ChunkEntity).
              | Expected:
              |""".trimMargin() + _infoChunks + """
              |
              | Found:
              |""".trimMargin() + _existingChunks)
        }
        val _columnsMediaMeta: MutableMap<String, TableInfo.Column> = mutableMapOf()
        _columnsMediaMeta.put("cacheKey", TableInfo.Column("cacheKey", "TEXT", true, 1, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsMediaMeta.put("found", TableInfo.Column("found", "INTEGER", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsMediaMeta.put("title", TableInfo.Column("title", "TEXT", false, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsMediaMeta.put("year", TableInfo.Column("year", "INTEGER", false, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsMediaMeta.put("posterPath", TableInfo.Column("posterPath", "TEXT", false, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsMediaMeta.put("backdropPath", TableInfo.Column("backdropPath", "TEXT", false, 0,
            null, TableInfo.CREATED_FROM_ENTITY))
        _columnsMediaMeta.put("overview", TableInfo.Column("overview", "TEXT", false, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsMediaMeta.put("fetchedAt", TableInfo.Column("fetchedAt", "INTEGER", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        val _foreignKeysMediaMeta: MutableSet<TableInfo.ForeignKey> = mutableSetOf()
        val _indicesMediaMeta: MutableSet<TableInfo.Index> = mutableSetOf()
        val _infoMediaMeta: TableInfo = TableInfo("media_meta", _columnsMediaMeta,
            _foreignKeysMediaMeta, _indicesMediaMeta)
        val _existingMediaMeta: TableInfo = read(connection, "media_meta")
        if (!_infoMediaMeta.equals(_existingMediaMeta)) {
          return RoomOpenDelegate.ValidationResult(false, """
              |media_meta(com.abhinavxt.debforge.data.metadata.MediaMetaEntity).
              | Expected:
              |""".trimMargin() + _infoMediaMeta + """
              |
              | Found:
              |""".trimMargin() + _existingMediaMeta)
        }
        val _columnsWatches: MutableMap<String, TableInfo.Column> = mutableMapOf()
        _columnsWatches.put("provider", TableInfo.Column("provider", "TEXT", true, 1, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsWatches.put("jobRef", TableInfo.Column("jobRef", "TEXT", true, 2, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsWatches.put("name", TableInfo.Column("name", "TEXT", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsWatches.put("createdAt", TableInfo.Column("createdAt", "INTEGER", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        val _foreignKeysWatches: MutableSet<TableInfo.ForeignKey> = mutableSetOf()
        val _indicesWatches: MutableSet<TableInfo.Index> = mutableSetOf()
        val _infoWatches: TableInfo = TableInfo("watches", _columnsWatches, _foreignKeysWatches,
            _indicesWatches)
        val _existingWatches: TableInfo = read(connection, "watches")
        if (!_infoWatches.equals(_existingWatches)) {
          return RoomOpenDelegate.ValidationResult(false, """
              |watches(com.abhinavxt.debforge.download.watch.WatchEntity).
              | Expected:
              |""".trimMargin() + _infoWatches + """
              |
              | Found:
              |""".trimMargin() + _existingWatches)
        }
        val _columnsDataUsage: MutableMap<String, TableInfo.Column> = mutableMapOf()
        _columnsDataUsage.put("day", TableInfo.Column("day", "TEXT", true, 1, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsDataUsage.put("provider", TableInfo.Column("provider", "TEXT", true, 2, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsDataUsage.put("metered", TableInfo.Column("metered", "INTEGER", true, 3, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsDataUsage.put("bytes", TableInfo.Column("bytes", "INTEGER", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        val _foreignKeysDataUsage: MutableSet<TableInfo.ForeignKey> = mutableSetOf()
        val _indicesDataUsage: MutableSet<TableInfo.Index> = mutableSetOf()
        val _infoDataUsage: TableInfo = TableInfo("data_usage", _columnsDataUsage,
            _foreignKeysDataUsage, _indicesDataUsage)
        val _existingDataUsage: TableInfo = read(connection, "data_usage")
        if (!_infoDataUsage.equals(_existingDataUsage)) {
          return RoomOpenDelegate.ValidationResult(false, """
              |data_usage(com.abhinavxt.debforge.data.usage.UsageEntity).
              | Expected:
              |""".trimMargin() + _infoDataUsage + """
              |
              | Found:
              |""".trimMargin() + _existingDataUsage)
        }
        val _columnsPlaybackPositions: MutableMap<String, TableInfo.Column> = mutableMapOf()
        _columnsPlaybackPositions.put("itemId", TableInfo.Column("itemId", "TEXT", true, 1, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsPlaybackPositions.put("provider", TableInfo.Column("provider", "TEXT", true, 0,
            null, TableInfo.CREATED_FROM_ENTITY))
        _columnsPlaybackPositions.put("sourceRef", TableInfo.Column("sourceRef", "TEXT", true, 0,
            null, TableInfo.CREATED_FROM_ENTITY))
        _columnsPlaybackPositions.put("filename", TableInfo.Column("filename", "TEXT", true, 0,
            null, TableInfo.CREATED_FROM_ENTITY))
        _columnsPlaybackPositions.put("title", TableInfo.Column("title", "TEXT", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsPlaybackPositions.put("showKey", TableInfo.Column("showKey", "TEXT", false, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsPlaybackPositions.put("positionMs", TableInfo.Column("positionMs", "INTEGER", true,
            0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsPlaybackPositions.put("durationMs", TableInfo.Column("durationMs", "INTEGER", true,
            0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsPlaybackPositions.put("finished", TableInfo.Column("finished", "INTEGER", true, 0,
            null, TableInfo.CREATED_FROM_ENTITY))
        _columnsPlaybackPositions.put("updatedAt", TableInfo.Column("updatedAt", "INTEGER", true, 0,
            null, TableInfo.CREATED_FROM_ENTITY))
        _columnsPlaybackPositions.put("parentRef", TableInfo.Column("parentRef", "TEXT", false, 0,
            null, TableInfo.CREATED_FROM_ENTITY))
        val _foreignKeysPlaybackPositions: MutableSet<TableInfo.ForeignKey> = mutableSetOf()
        val _indicesPlaybackPositions: MutableSet<TableInfo.Index> = mutableSetOf()
        _indicesPlaybackPositions.add(TableInfo.Index("index_playback_positions_updatedAt", false,
            listOf("updatedAt"), listOf("ASC")))
        val _infoPlaybackPositions: TableInfo = TableInfo("playback_positions",
            _columnsPlaybackPositions, _foreignKeysPlaybackPositions, _indicesPlaybackPositions)
        val _existingPlaybackPositions: TableInfo = read(connection, "playback_positions")
        if (!_infoPlaybackPositions.equals(_existingPlaybackPositions)) {
          return RoomOpenDelegate.ValidationResult(false, """
              |playback_positions(com.abhinavxt.debforge.data.playback.PlaybackEntity).
              | Expected:
              |""".trimMargin() + _infoPlaybackPositions + """
              |
              | Found:
              |""".trimMargin() + _existingPlaybackPositions)
        }
        return RoomOpenDelegate.ValidationResult(true, null)
      }
    }
    return _openDelegate
  }

  protected override fun createInvalidationTracker(): InvalidationTracker {
    val _shadowTablesMap: MutableMap<String, String> = mutableMapOf()
    val _viewTables: MutableMap<String, Set<String>> = mutableMapOf()
    return InvalidationTracker(this, _shadowTablesMap, _viewTables, "downloads", "chunks",
        "media_meta", "watches", "data_usage", "playback_positions")
  }

  public override fun clearAllTables() {
    super.performClear(true, "downloads", "chunks", "media_meta", "watches", "data_usage",
        "playback_positions")
  }

  protected override fun getRequiredTypeConverterClasses(): Map<KClass<*>, List<KClass<*>>> {
    val _typeConvertersMap: MutableMap<KClass<*>, List<KClass<*>>> = mutableMapOf()
    _typeConvertersMap.put(DownloadDao::class, DownloadDao_Impl.getRequiredConverters())
    _typeConvertersMap.put(ChunkDao::class, ChunkDao_Impl.getRequiredConverters())
    _typeConvertersMap.put(MediaMetaDao::class, MediaMetaDao_Impl.getRequiredConverters())
    _typeConvertersMap.put(WatchDao::class, WatchDao_Impl.getRequiredConverters())
    _typeConvertersMap.put(UsageDao::class, UsageDao_Impl.getRequiredConverters())
    _typeConvertersMap.put(PlaybackDao::class, PlaybackDao_Impl.getRequiredConverters())
    return _typeConvertersMap
  }

  public override fun getRequiredAutoMigrationSpecClasses(): Set<KClass<out AutoMigrationSpec>> {
    val _autoMigrationSpecsSet: MutableSet<KClass<out AutoMigrationSpec>> = mutableSetOf()
    return _autoMigrationSpecsSet
  }

  public override
      fun createAutoMigrations(autoMigrationSpecs: Map<KClass<out AutoMigrationSpec>, AutoMigrationSpec>):
      List<Migration> {
    val _autoMigrations: MutableList<Migration> = mutableListOf()
    return _autoMigrations
  }

  public override fun downloadDao(): DownloadDao = _downloadDao.value

  public override fun chunkDao(): ChunkDao = _chunkDao.value

  public override fun mediaMetaDao(): MediaMetaDao = _mediaMetaDao.value

  public override fun watchDao(): WatchDao = _watchDao.value

  public override fun usageDao(): UsageDao = _usageDao.value

  public override fun playbackDao(): PlaybackDao = _playbackDao.value
}
