package com.abhinavxt.debforge.di

import com.abhinavxt.debforge.data.metadata.TmdbApi
import com.abhinavxt.debforge.data.metadata.TmdbAuthInterceptor
import com.abhinavxt.debforge.data.prefs.SettingsStore
import com.squareup.moshi.Moshi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object MetadataModule {

    @Provides
    @Singleton
    fun provideTmdbApi(
        @ApiHttpClient base: OkHttpClient,
        moshi: Moshi,
        settings: SettingsStore
    ): TmdbApi {
        // Auth first so the (redacting) logger sees the final URL.
        val client = base.newBuilder()
            .apply { interceptors().add(0, TmdbAuthInterceptor(settings)) }
            .build()
        return Retrofit.Builder()
            .baseUrl(TmdbApi.BASE_URL)
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(TmdbApi::class.java)
    }
}
