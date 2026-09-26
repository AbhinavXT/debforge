package com.abhinavxt.debforge.data.local

import androidx.room.Database
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
    entities = [DownloadEntity::class, ChunkEntity::class, MediaMetaEntity::class, WatchEntity::class],
    version = 5,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class DebForgeDatabase : RoomDatabase() {
    abstract fun downloadDao(): DownloadDao
    abstract fun chunkDao(): ChunkDao
    abstract fun mediaMetaDao(): MediaMetaDao
    abstract fun watchDao(): WatchDao

    companion object {
        const val NAME = "debforge.db"

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
