package com.abhinavxt.debforge.di

import com.abhinavxt.debforge.data.prefs.TokenStore
import com.abhinavxt.debforge.data.provider.DebridProvider
import com.abhinavxt.debforge.data.provider.realdebrid.RealDebridApi
import com.abhinavxt.debforge.data.provider.realdebrid.RealDebridProvider
import com.abhinavxt.debforge.data.provider.torbox.TorBoxApi
import com.abhinavxt.debforge.data.provider.torbox.TorBoxProvider
import com.abhinavxt.debforge.data.remote.AuthInterceptor
import com.abhinavxt.debforge.domain.ProviderId
import com.squareup.moshi.Moshi
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import javax.inject.Singleton

/**
 * Wiring for every debrid provider. To add a new service:
 *   1. add an entry to [ProviderId],
 *   2. write `XyzApi` + `XyzProvider : DebridProvider` under data/provider/xyz,
 *   3. add a `provideXyzApi` below and a `@Binds @IntoSet` line in [ProviderBindings].
 * The registry, Setup picker, Settings switcher and download engine pick it
 * up automatically.
 */
@Module
@InstallIn(SingletonComponent::class)
object ProviderModule {

    @Provides
    @Singleton
    fun provideRealDebridApi(
        @ApiHttpClient base: OkHttpClient,
        moshi: Moshi,
        tokenStore: TokenStore
    ): RealDebridApi = retrofitFor(base, moshi, tokenStore, ProviderId.REAL_DEBRID, RealDebridApi.BASE_URL)
        .create(RealDebridApi::class.java)

    @Provides
    @Singleton
    fun provideTorBoxApi(
        @ApiHttpClient base: OkHttpClient,
        moshi: Moshi,
        tokenStore: TokenStore
    ): TorBoxApi = retrofitFor(base, moshi, tokenStore, ProviderId.TORBOX, TorBoxApi.BASE_URL)
        .create(TorBoxApi::class.java)

    private fun retrofitFor(
        base: OkHttpClient,
        moshi: Moshi,
        tokenStore: TokenStore,
        provider: ProviderId,
        baseUrl: String
    ): Retrofit {
        // Auth goes FIRST so the logging interceptor (already on `base`) sees
        // the final request. BASIC level never prints headers.
        val client = base.newBuilder()
            .apply { interceptors().add(0, AuthInterceptor(tokenStore, provider)) }
            .build()
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
    }

}

/** Multibinding: every provider lands in the Set<DebridProvider> the registry consumes. */
@Module
@InstallIn(SingletonComponent::class)
abstract class ProviderBindings {
    @Binds @IntoSet
    abstract fun realDebrid(impl: RealDebridProvider): DebridProvider

    @Binds @IntoSet
    abstract fun torBox(impl: TorBoxProvider): DebridProvider
}
