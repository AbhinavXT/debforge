package com.abhinavxt.debforge.di

import android.content.Context
import androidx.room.Room
import com.abhinavxt.debforge.data.local.ChunkDao
import com.abhinavxt.debforge.data.local.DebForgeDatabase
import com.abhinavxt.debforge.data.local.DownloadDao
import com.abhinavxt.debforge.data.metadata.MediaMetaDao
import com.abhinavxt.debforge.download.watch.WatchDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): DebForgeDatabase =
        Room.databaseBuilder(
            context,
            DebForgeDatabase::class.java,
            DebForgeDatabase.NAME
        )
            .addMigrations(
                DebForgeDatabase.MIGRATION_1_2,
                DebForgeDatabase.MIGRATION_2_3,
                DebForgeDatabase.MIGRATION_3_4,
                DebForgeDatabase.MIGRATION_4_5,
                DebForgeDatabase.MIGRATION_5_6,
                DebForgeDatabase.MIGRATION_6_7,
                DebForgeDatabase.MIGRATION_7_8
            )
            .build()

    @Provides
    fun provideDownloadDao(db: DebForgeDatabase): DownloadDao = db.downloadDao()

    @Provides
    fun provideChunkDao(db: DebForgeDatabase): ChunkDao = db.chunkDao()

    @Provides
    fun provideMediaMetaDao(db: DebForgeDatabase): MediaMetaDao = db.mediaMetaDao()

    @Provides
    fun provideWatchDao(db: DebForgeDatabase): WatchDao = db.watchDao()

    @Provides
    fun providePlaybackDao(db: DebForgeDatabase): com.abhinavxt.debforge.data.playback.PlaybackDao = db.playbackDao()

    @Provides
    fun provideUsageDao(db: DebForgeDatabase): com.abhinavxt.debforge.data.usage.UsageDao = db.usageDao()
}
