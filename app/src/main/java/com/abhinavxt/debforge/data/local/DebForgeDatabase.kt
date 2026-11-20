package com.abhinavxt.debforge.data.local

import androidx.room.Database
import com.abhinavxt.debforge.data.usage.UsageDao
import com.abhinavxt.debforge.data.usage.UsageEntity
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.abhinavxt.debforge.data.metadata.MediaMetaDao
import com.abhinavxt.debforge.data.metadata.MediaMetaEntity
import com.abhinavxt.debforge.domain.DownloadState
import com.abhinavxt.debforge.download.watch.WatchDao
import com.abhinavxt.debforge.download.watch.WatchEntity
import com.abhinavxt.debforge.domain.ProviderId

class Converters {
    @TypeConverter
    fun stateToString(state: DownloadState): String = state.name

    @TypeConverter
    fun stringToState(value: String): DownloadState = DownloadState.valueOf(value)

    @TypeConverter
    fun providerToString(provider: ProviderId): String = provider.name

    // Unknown values (a provider removed in a future version) fall back to RD
    // rather than crashing the whole query.
    @TypeConverter
    fun stringToProvider(value: String): ProviderId =
        ProviderId.fromName(value) ?: ProviderId.REAL_DEBRID
}

@Database(
    entities = [
        DownloadEntity::class, ChunkEntity::class, MediaMetaEntity::class, WatchEntity::class, UsageEntity::class,
        com.abhinavxt.debforge.data.playback.PlaybackEntity::class,
        com.abhinavxt.debforge.data.history.HistoryEntity::class,
        com.abhinavxt.debforge.data.subscriptions.SubscriptionEntity::class,
        com.abhinavxt.debforge.data.subscriptions.FeedItemEntity::class,
        com.abhinavxt.debforge.data.sponsor.SponsorSegmentEntity::class
    ],
    version = 14,
    // Schemas land in app/schemas (commit them): DatabaseMigrationTest checks
    // every migration against them. v1–v7 were rebuilt from the migrations.
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class DebForgeDatabase : RoomDatabase() {
    abstract fun downloadDao(): DownloadDao
    abstract fun chunkDao(): ChunkDao
    abstract fun mediaMetaDao(): MediaMetaDao
    abstract fun watchDao(): WatchDao
    abstract fun usageDao(): UsageDao
    abstract fun playbackDao(): com.abhinavxt.debforge.data.playback.PlaybackDao
    abstract fun historyDao(): com.abhinavxt.debforge.data.history.HistoryDao
    abstract fun subscriptionDao(): com.abhinavxt.debforge.data.subscriptions.SubscriptionDao
    abstract fun sponsorSegmentDao(): com.abhinavxt.debforge.data.sponsor.SponsorSegmentDao

    companion object {
        const val NAME = "debforge.db"

        /** Every migration, oldest first. A new one goes here (and DatabaseMigrationTest checks it). Lazy: they are declared below. */
        val ALL_MIGRATIONS: Array<Migration> by lazy {
            arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14)
        }

        /**
         * v1 -> v2: add an index on `downloads.state`. The Active screen reads
         * by state (`observeByState`) and the queue puller hits `nextInState`
         * constantly — both turn into index scans instead of full table scans.
         *
         * Index name matches Room's auto-generated convention so the
         * migrated schema matches what Room would create from scratch.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_downloads_state` ON `downloads` (`state`)")
            }
        }

        /**
         * v2 -> v3: multi-provider support. Every row that exists at upgrade
         * time was queued from Real-Debrid, so the column defaults to that.
         * The DEFAULT literal must match @ColumnInfo(defaultValue) on
         * DownloadEntity.provider or Room's schema validation fails.
         */
        /**
         * v6 -> v7: where the user got to in each video (resume, Continue
         * watching). Must match PlaybackEntity exactly: nullable columns
         * only where the Kotlin type is nullable, no defaults, and Room's
         * index naming.
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `playback_positions` (" +
                        "`itemId` TEXT NOT NULL, `provider` TEXT NOT NULL, `sourceRef` TEXT NOT NULL, " +
                        "`filename` TEXT NOT NULL, `title` TEXT NOT NULL, `showKey` TEXT, " +
                        "`positionMs` INTEGER NOT NULL, `durationMs` INTEGER NOT NULL, " +
                        "`finished` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`itemId`))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_playback_positions_updatedAt` ON `playback_positions` (`updatedAt`)"
                )
            }
        }

        /** v7 -> v8: the torrent a watched file came from (nullable, no default: matches the entity). */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `playback_positions` ADD COLUMN `parentRef` TEXT")
            }
        }

        /** v8 -> v9: the in-app browser's Referer / User-Agent per download (nullable, no default: matches the entity). */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `downloads` ADD COLUMN `referer` TEXT")
                db.execSQL("ALTER TABLE `downloads` ADD COLUMN `userAgent` TEXT")
            }
        }

        /** v9 -> v10: a stream's separate audio playlist (nullable, no default: matches the entity). */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `downloads` ADD COLUMN `audioUrl` TEXT")
            }
        }

        /** v10 -> v11: human title and artist (nullable, no default: matches the entity). */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `downloads` ADD COLUMN `title` TEXT")
                db.execSQL("ALTER TABLE `downloads` ADD COLUMN `artist` TEXT")
            }
        }

        /**
         * v11 -> v12: play history. New table only; `playback_positions` and everything else are
         * untouched. Seeded from the videos already watched so History is not empty after the update
         * (a row with no download behind it still plays from the cloud).
         */
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `play_history` (`itemId` TEXT NOT NULL, `kind` TEXT NOT NULL, " +
                        "`origin` TEXT NOT NULL, `provider` TEXT, `sourceRef` TEXT NOT NULL, `parentRef` TEXT, " +
                        "`showKey` TEXT, `title` TEXT NOT NULL, `subtitle` TEXT, `artworkUrl` TEXT, " +
                        "`positionMs` INTEGER NOT NULL, `durationMs` INTEGER NOT NULL, `playedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`itemId`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_play_history_playedAt` ON `play_history` (`playedAt`)")
                db.execSQL(
                    "INSERT OR IGNORE INTO play_history (itemId, kind, origin, provider, sourceRef, parentRef, showKey, " +
                        "title, subtitle, artworkUrl, positionMs, durationMs, playedAt) " +
                        "SELECT itemId, 'VIDEO', CASE WHEN itemId LIKE 'ytstream:%' THEN 'YOUTUBE' ELSE 'CLOUD' END, " +
                        "CASE WHEN itemId LIKE 'ytstream:%' THEN NULL ELSE provider END, " +
                        "CASE WHEN itemId LIKE 'ytstream:%' THEN substr(itemId, 10) ELSE sourceRef END, " +
                        "parentRef, showKey, title, NULL, NULL, positionMs, durationMs, updatedAt " +
                        "FROM playback_positions"
                )
            }
        }

        /**
         * v12 -> v13: followed channels and podcasts, their feed items, and cached SponsorBlock
         * segments. New tables only; nothing existing is touched.
         */
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `subscriptions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`kind` TEXT NOT NULL, `url` TEXT NOT NULL, `title` TEXT NOT NULL, `artworkUrl` TEXT, " +
                        "`autoDownload` TEXT NOT NULL DEFAULT 'OFF', `since` INTEGER NOT NULL, `etag` TEXT, " +
                        "`lastModified` TEXT, `lastChecked` INTEGER NOT NULL DEFAULT 0)"
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_subscriptions_url` ON `subscriptions` (`url`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `feed_items` (`subId` INTEGER NOT NULL, `itemKey` TEXT NOT NULL, " +
                        "`title` TEXT NOT NULL, `mediaUrl` TEXT, `publishedAt` INTEGER NOT NULL, `durationSec` INTEGER, " +
                        "`thumbUrl` TEXT, `isNew` INTEGER NOT NULL, `queued` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`subId`, `itemKey`), FOREIGN KEY(`subId`) REFERENCES `subscriptions`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_feed_items_subId` ON `feed_items` (`subId`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sponsor_segments` (`videoId` TEXT NOT NULL, `json` TEXT NOT NULL, " +
                        "`fetchedAt` INTEGER NOT NULL, PRIMARY KEY(`videoId`))"
                )
            }
        }

        /** v13 -> v14: album, album artist and track number of imported audio (NULL until the tags are read). */
        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `downloads` ADD COLUMN `album` TEXT")
                db.execSQL("ALTER TABLE `downloads` ADD COLUMN `albumArtist` TEXT")
                db.execSQL("ALTER TABLE `downloads` ADD COLUMN `trackNo` INTEGER")
            }
        }

        /** v5 -> v6: data-usage stats (bytes per day, service and network kind). */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `data_usage` (" +
                        "`day` TEXT NOT NULL, `provider` TEXT NOT NULL, `metered` INTEGER NOT NULL, " +
                        "`bytes` INTEGER NOT NULL, PRIMARY KEY(`day`, `provider`, `metered`))"
                )
            }
        }

        /** v4 -> v5: "download when ready" watch list. */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `watches` (" +
                        "`provider` TEXT NOT NULL, `jobRef` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, PRIMARY KEY(`provider`, `jobRef`))"
                )
            }
        }

        /** v3 -> v4: TMDB metadata cache for the poster view. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `media_meta` (" +
                        "`cacheKey` TEXT NOT NULL, `found` INTEGER NOT NULL, `title` TEXT, " +
                        "`year` INTEGER, `posterPath` TEXT, `backdropPath` TEXT, `overview` TEXT, " +
                        "`fetchedAt` INTEGER NOT NULL, PRIMARY KEY(`cacheKey`))"
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `downloads` ADD COLUMN `provider` TEXT NOT NULL DEFAULT 'REAL_DEBRID'"
                )
            }
        }
    }
}
