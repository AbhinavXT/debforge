package com.abhinavxt.debforge.di

import com.abhinavxt.debforge.data.subtitles.OpenSubtitlesApi
import com.abhinavxt.debforge.data.subtitles.OpenSubtitlesInterceptor
import com.abhinavxt.debforge.data.subtitles.OpenSubtitlesStore
import com.abhinavxt.debforge.data.trakt.TraktApi
import com.abhinavxt.debforge.data.trakt.TraktAuthInterceptor
import com.abhinavxt.debforge.data.trakt.TraktStore
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
/** Optional services the user connects with their own keys: Trakt and OpenSubtitles. */
object ConnectionsModule {

    @Provides
    @Singleton
    fun provideTraktApi(@ApiHttpClient base: OkHttpClient, moshi: Moshi, store: TraktStore): TraktApi {
        // Auth first so the logger sees the final request.
        val client = base.newBuilder()
            .apply { interceptors().add(0, TraktAuthInterceptor(store)) }
            .build()
        return Retrofit.Builder()
            .baseUrl(TraktApi.BASE_URL)
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(TraktApi::class.java)
    }

    @Provides
    @Singleton
    fun provideOpenSubtitlesApi(@ApiHttpClient base: OkHttpClient, moshi: Moshi, store: OpenSubtitlesStore): OpenSubtitlesApi {
        val client = base.newBuilder()
            .apply { interceptors().add(0, OpenSubtitlesInterceptor(store)) }
            .build()
        return Retrofit.Builder()
            .baseUrl(OpenSubtitlesApi.BASE_URL)
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(OpenSubtitlesApi::class.java)
    }
}
