package com.abhinavxt.debforge.di

import android.content.Context
import androidx.room.Room
import com.abhinavxt.debforge.data.local.ChunkDao
import com.abhinavxt.debforge.data.local.DebForgeDatabase
import com.abhinavxt.debforge.data.local.DownloadDao
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
            .addMigrations(DebForgeDatabase.MIGRATION_1_2)
            .build()

    @Provides
    fun provideDownloadDao(db: DebForgeDatabase): DownloadDao = db.downloadDao()

    @Provides
    fun provideChunkDao(db: DebForgeDatabase): ChunkDao = db.chunkDao()
}
