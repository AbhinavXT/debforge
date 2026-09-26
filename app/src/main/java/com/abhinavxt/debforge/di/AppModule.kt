package com.abhinavxt.debforge.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Bridges Hilt's @ApplicationContext into a plain Context dependency so our
 * DataStore-backed stores (TokenStore, SettingsStore) can take Context without
 * each one needing the qualifier annotation.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideContext(@ApplicationContext context: Context): Context = context
}
