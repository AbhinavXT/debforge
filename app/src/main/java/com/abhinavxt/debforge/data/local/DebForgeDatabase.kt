package com.abhinavxt.debforge.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.abhinavxt.debforge.domain.DownloadState
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
    entities = [DownloadEntity::class, ChunkEntity::class],
    version = 3,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class DebForgeDatabase : RoomDatabase() {
    abstract fun downloadDao(): DownloadDao
    abstract fun chunkDao(): ChunkDao

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
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `downloads` ADD COLUMN `provider` TEXT NOT NULL DEFAULT 'REAL_DEBRID'"
                )
            }
        }
    }
}
