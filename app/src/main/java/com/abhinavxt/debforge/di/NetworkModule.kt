package com.abhinavxt.debforge.di

import com.abhinavxt.debforge.BuildConfig
import com.abhinavxt.debforge.data.prefs.TokenStore
import com.abhinavxt.debforge.data.remote.AuthInterceptor
import com.abhinavxt.debforge.data.remote.RealDebridApi
import com.squareup.moshi.Moshi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideMoshi(): Moshi = Moshi.Builder().build()

    @Provides
    @Singleton
    fun provideAuthInterceptor(tokenStore: TokenStore): AuthInterceptor =
        AuthInterceptor(tokenStore)

    @Provides
    @Singleton
    fun provideOkHttp(authInterceptor: AuthInterceptor): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BASIC
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }
        return OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .addInterceptor(logging)
            // Generous read timeout: API calls are quick, but the same client is
            // not used for the byte-stream downloads (the engine builds its own).
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Auth-FREE client for downloading file bytes. No AuthInterceptor (don't
     * leak the RD token to CDN/hoster domains). readTimeout is a per-read
     * inactivity window — a long stream is fine as long as bytes keep arriving;
     * we fail only after 60s of silence. No callTimeout (whole-call cap), since
     * a multi-GB file legitimately takes a long time.
     *
     * ConnectionPool sized to 32 idle / 5 min keepalive: with 16 parallel chunks
     * per file plus the probe call, the default pool (5 idle) overflows and
     * we'd churn TCP+TLS handshakes between files to the same hoster. Holding
     * warm connections eliminates that for sequential downloads.
     */
    @Provides
    @Singleton
    @DownloadHttpClient
    fun provideDownloadOkHttp(): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(32, 5L, TimeUnit.MINUTES))
            .retryOnConnectionFailure(true)
            .build()

    @Provides
    @Singleton
    fun provideRetrofit(client: OkHttpClient, moshi: Moshi): Retrofit =
        Retrofit.Builder()
            .baseUrl(RealDebridApi.BASE_URL)
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()

    @Provides
    @Singleton
    fun provideApi(retrofit: Retrofit): RealDebridApi =
        retrofit.create(RealDebridApi::class.java)
}
