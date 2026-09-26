package com.abhinavxt.debforge.di

import android.util.Log
import com.abhinavxt.debforge.BuildConfig
import com.squareup.moshi.Moshi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideMoshi(): Moshi = Moshi.Builder().build()

    /**
     * Base client for provider API calls. Deliberately has NO auth: each
     * provider derives its own client from this one via newBuilder() and adds
     * an AuthInterceptor bound to its own token (see ProviderModule). Derived
     * clients share this client's connection pool and dispatcher.
     *
     * Logging redacts `token=` query params — TorBox's requestdl endpoints take
     * the API key in the URL, and BASIC logging prints URLs to logcat.
     */
    @Provides
    @Singleton
    @ApiHttpClient
    fun provideApiOkHttp(): OkHttpClient {
        val logging = HttpLoggingInterceptor { message ->
            Log.d("OkHttp", TOKEN_PARAM.replace(message, "$1=***"))
        }.apply {
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BASIC
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }
        return OkHttpClient.Builder()
            .addInterceptor(logging)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Auth-FREE client for downloading file bytes. No AuthInterceptor (don't
     * leak API tokens to CDN/hoster domains). readTimeout is a per-read
     * inactivity window — a long stream is fine as long as bytes keep arriving;
     * we fail only after 60s of silence. No callTimeout (whole-call cap), since
     * a multi-GB file legitimately takes a long time.
     *
     * ConnectionPool sized to 32 idle / 5 min keepalive: with 16 parallel chunks
     * per file plus the probe call, the default pool (5 idle) overflows and
     * we'd churn TCP+TLS handshakes between files to the same host.
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

    private val TOKEN_PARAM = Regex("""\b(token|apikey|api_key)=[^&\s]+""", RegexOption.IGNORE_CASE)
}
